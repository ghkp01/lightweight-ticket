package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.masterdata.api.ProductInfo;
import com.mes.platform.audit.OperLogService;
import com.mes.platform.scope.OrgScopeService;
import com.mes.security.CurrentUser;
import com.mes.warehouse.api.InboundOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 采购订单：草稿 → 确认 → 收货（生成入库单）→ 入库完成回写实收数量。
 * 已确认但未入库的数量会扣掉「在途」部分，避免重复收货。
 *
 * <p>入库单本身归仓储模块，这里只通过 {@link InboundOrderPort} 建单与回读，
 * 不再直接写 {@code inbound_order}。</p>
 */
@Service
public class PurchaseOrderService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final OperLogService operLogService;
    private final ApService apService;
    private final OrgScopeService orgScope;
    private final MasterDataPort masterData;
    private final InboundOrderPort inboundOrderPort;

    public PurchaseOrderService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                                OperLogService operLogService, ApService apService,
                                OrgScopeService orgScope,
                                MasterDataPort masterData,
                                InboundOrderPort inboundOrderPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.operLogService = operLogService;
        this.apService = apService;
        this.orgScope = orgScope;
        this.masterData = masterData;
        this.inboundOrderPort = inboundOrderPort;
    }

    public record ItemRequest(Long productId, BigDecimal qty, BigDecimal price, String remark,
                              Long requisitionItemId) {
        public ItemRequest(Long productId, BigDecimal qty, BigDecimal price, String remark) {
            this(productId, qty, price, remark, null);
        }
    }

    public record OrderRequest(Long supplierId, Long warehouseId, LocalDate orderDate, LocalDate expectedDate,
                               Boolean needInspection, String remark, List<ItemRequest> items,
                               Long requisitionId) {
        public OrderRequest(Long supplierId, Long warehouseId, LocalDate orderDate, LocalDate expectedDate,
                            Boolean needInspection, String remark, List<ItemRequest> items) {
            this(supplierId, warehouseId, orderDate, expectedDate, needInspection, remark, items, null);
        }
    }

    public record ReceiveLine(Long itemId, BigDecimal qty, String batchNo) {
    }

    public record ReceiveRequest(List<ReceiveLine> items, Boolean needInspection, String remark) {
    }

    public PageResult list(String keyword, String status, Long supplierId, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder where = new StringBuilder("""
                 where (o.order_no ilike ? or s.name ilike ? or coalesce(o.remark, '') ilike ?)
                """);
        List<Object> args = new ArrayList<>(List.of(like, like, like));
        if (status != null && !status.isBlank()) {
            where.append(" and o.status = ?");
            args.add(status.trim());
        }
        if (supplierId != null) {
            where.append(" and o.supplier_id = ?");
            args.add(supplierId);
        }
        // 多工厂：采购单跟着收货仓库的工厂走
        MasterDataViews.appendWarehouseScope(orgScope, masterData, where, args, "o.warehouse_id");
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from purchase_order o
                join supplier s on s.id = o.supplier_id
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select o.id, o.order_no as "orderNo", o.supplier_id as "supplierId",
                       s.name as "supplierName", s.code as "supplierCode",
                       o.warehouse_id as "warehouseId", o.status,
                       rq.req_no as "requisitionNo",
                       to_char(o.order_date, 'YYYY-MM-DD') as "orderDate",
                       to_char(o.expected_date, 'YYYY-MM-DD') as "expectedDate",
                       o.need_inspection as "needInspection", o.total_amount as "totalAmount",
                       (select count(*) from purchase_order_item i where i.order_id = o.id) as "itemCount",
                       (select coalesce(sum(i.qty), 0) from purchase_order_item i
                         where i.order_id = o.id) as "totalQty",
                       (select coalesce(sum(i.received_qty), 0) from purchase_order_item i
                         where i.order_id = o.id) as "receivedQty",
                       o.remark, o.created_by as "createdBy",
                       to_char(o.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from purchase_order o
                join supplier s on s.id = o.supplier_id
                left join purchase_requisition rq on rq.id = o.requisition_id
                """ + where + " order by o.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillWarehouseNames(masterData, records, "warehouseId", "warehouseName");
        fillOpenInboundCounts(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> summary() {
        return jdbcTemplate.queryForMap("""
                select count(*) filter (where status = 'DRAFT') as "draft",
                       count(*) filter (where status in ('CONFIRMED', 'PARTIAL')) as "open",
                       count(*) filter (where status = 'RECEIVED') as "received",
                       count(*) filter (where status = 'CANCELLED') as "cancelled",
                       coalesce(sum(total_amount) filter (
                           where status <> 'CANCELLED'
                             and order_date >= date_trunc('month', current_date)), 0) as "monthAmount",
                       coalesce(sum(total_amount) filter (where status in ('CONFIRMED', 'PARTIAL')), 0) as "openAmount"
                from purchase_order
                """);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id, o.order_no as "orderNo", o.supplier_id as "supplierId",
                       s.name as "supplierName", s.code as "supplierCode", s.contact as "supplierContact",
                       s.phone as "supplierPhone", s.payment_term as "paymentTerm", s.lead_days as "leadDays",
                       o.warehouse_id as "warehouseId", o.status,
                       rq.req_no as "requisitionNo",
                       to_char(o.order_date, 'YYYY-MM-DD') as "orderDate",
                       to_char(o.expected_date, 'YYYY-MM-DD') as "expectedDate",
                       o.need_inspection as "needInspection", o.total_amount as "totalAmount",
                       o.remark, o.created_by as "createdBy", o.confirmed_by as "confirmedBy",
                       to_char(o.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(o.confirmed_at, 'YYYY-MM-DD HH24:MI') as "confirmedAt"
                from purchase_order o
                join supplier s on s.id = o.supplier_id
                left join purchase_requisition rq on rq.id = o.requisition_id
                where o.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2007, "采购单不存在");
        }
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = detailItems(id);
        detail.put("items", items);
        detail.put("inboundOrders", inboundMaps(inboundOrderPort.byPurchaseOrder(id)));
        return detail;
    }

    @Transactional
    public long create(OrderRequest request) {
        validate(request);
        long supplierId = resolveSupplierId(request);
        Map<String, Object> supplier = loadSupplier(supplierId);
        long warehouseId = request.warehouseId();
        LocalDate orderDate = request.orderDate() == null ? LocalDate.now() : request.orderDate();
        LocalDate expectedDate = request.expectedDate() == null
                ? orderDate.plusDays(((Number) supplier.get("lead_days")).longValue())
                : request.expectedDate();
        BigDecimal total = totalOf(request.items());
        String orderNo = codeGenerator.next("PO");
        Long id = jdbcTemplate.queryForObject("""
                insert into purchase_order (order_no, supplier_id, warehouse_id, status, order_date,
                                            expected_date, need_inspection, total_amount, remark, created_by,
                                            requisition_id)
                values (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, orderNo, supplierId, warehouseId, orderDate, expectedDate,
                request.needInspection() == null || request.needInspection(), total,
                blankToNull(request.remark()), CurrentUser.name(), request.requisitionId());
        insertItems(id, request.items());
        operLogService.touch("PURCHASE", id, orderNo,
                "新建采购单 " + orderNo + "，供应商 " + supplier.get("name"));
        return id;
    }

    @Transactional
    public void update(long id, OrderRequest request) {
        Map<String, Object> order = loadForUpdate(id);
        assertStatus(order, "DRAFT", "只有草稿采购单可以修改");
        validate(request);
        long supplierId = resolveSupplierId(request);
        Map<String, Object> supplier = loadSupplier(supplierId);
        LocalDate orderDate = request.orderDate() == null ? LocalDate.now() : request.orderDate();
        LocalDate expectedDate = request.expectedDate() == null
                ? orderDate.plusDays(((Number) supplier.get("lead_days")).longValue())
                : request.expectedDate();
        jdbcTemplate.update("""
                update purchase_order set supplier_id = ?, warehouse_id = ?, order_date = ?, expected_date = ?,
                       need_inspection = ?, total_amount = ?, remark = ?
                where id = ?
                """, supplierId, request.warehouseId(), orderDate, expectedDate,
                request.needInspection() == null || request.needInspection(),
                totalOf(request.items()), blankToNull(request.remark()), id);
        jdbcTemplate.update("delete from purchase_order_item where order_id = ?", id);
        insertItems(id, request.items());
    }

    @Transactional
    public void confirm(long id) {
        Map<String, Object> order = loadForUpdate(id);
        assertStatus(order, "DRAFT", "只有草稿采购单可以确认");
        Map<String, Object> supplier = loadSupplier(longOf(order.get("supplier_id")));
        if (!Boolean.TRUE.equals(supplier.get("enabled"))) {
            throw new BizException(2008, "供应商已停用，不能确认采购单");
        }
        Integer items = jdbcTemplate.queryForObject(
                "select count(*) from purchase_order_item where order_id = ?", Integer.class, id);
        if (items == null || items == 0) {
            throw new BizException(1002, "采购单没有明细，不能确认");
        }
        jdbcTemplate.update("""
                update purchase_order set status = 'CONFIRMED', confirmed_by = ?, confirmed_at = now()
                where id = ?
                """, CurrentUser.name(), id);
        operLogService.touch("PURCHASE", id, String.valueOf(order.get("order_no")),
                "确认采购单 " + order.get("order_no") + "，供应商 " + supplier.get("name"));
        operLogService.action("确认");
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> order = loadForUpdate(id);
        String status = String.valueOf(order.get("status"));
        if (!List.of("DRAFT", "CONFIRMED", "PARTIAL").contains(status)) {
            throw new BizException(2014, "当前状态不允许取消");
        }
        BigDecimal received = jdbcTemplate.queryForObject("""
                select coalesce(sum(received_qty), 0) from purchase_order_item where order_id = ?
                """, BigDecimal.class, id);
        if (received != null && received.signum() > 0) {
            throw new BizException(2014, "已经有到货数量，不能取消采购单");
        }
        if (inboundOrderPort.countOpenByPurchaseOrder(id) > 0) {
            throw new BizException(2014, "还有未完成的入库单，请先取消入库单");
        }
        jdbcTemplate.update("update purchase_order set status = 'CANCELLED' where id = ?", id);
        releaseRequisition(id);
        operLogService.touch("PURCHASE", id, String.valueOf(order.get("order_no")),
                "取消采购单 " + order.get("order_no"));
        operLogService.action("取消");
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> order = loadForUpdate(id);
        assertStatus(order, "DRAFT", "只有草稿采购单可以删除");
        if (inboundOrderPort.countByPurchaseOrder(id) > 0) {
            throw new BizException(2004, "该采购单已生成入库单，不能删除");
        }
        releaseRequisition(id);
        jdbcTemplate.update("delete from purchase_order_item where order_id = ?", id);
        jdbcTemplate.update("delete from purchase_order where id = ?", id);
    }

    /**
     * 收货：按待收数量生成一张入库单（默认全收），入库单完成后由 applyReceipt 回写实收。
     */
    @Transactional
    public Map<String, Object> receive(long id, ReceiveRequest request) {
        Map<String, Object> order = loadForUpdate(id);
        String status = String.valueOf(order.get("status"));
        if (!List.of("CONFIRMED", "PARTIAL").contains(status)) {
            throw new BizException(2014, "只有已确认或部分到货的采购单可以收货");
        }
        String orderNo = String.valueOf(order.get("order_no"));
        Map<Long, ReceiveLine> requested = new LinkedHashMap<>();
        if (request != null && request.items() != null) {
            for (ReceiveLine line : request.items()) {
                if (line != null && line.itemId() != null) {
                    requested.put(line.itemId(), line);
                }
            }
        }
        List<Map<String, Object>> items = detailItems(id);
        List<Map<String, Object>> toReceive = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        int index = 0;
        for (Map<String, Object> item : items) {
            index++;
            long itemId = longOf(item.get("id"));
            BigDecimal available = decimal(item.get("availableQty"));
            if (available.signum() <= 0) {
                continue;
            }
            BigDecimal qty = available;
            String batchNo = orderNo + "-" + index;
            if (requested.containsKey(itemId)) {
                ReceiveLine line = requested.get(itemId);
                if (line.qty() != null) {
                    qty = line.qty();
                }
                if (line.batchNo() != null && !line.batchNo().isBlank()) {
                    batchNo = line.batchNo().trim();
                }
            }
            if (qty.signum() <= 0) {
                continue;
            }
            if (qty.compareTo(available) > 0) {
                throw new BizException(1002, item.get("productName") + " 收货数量超过待收数量 "
                        + available.stripTrailingZeros().toPlainString());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", itemId);
            row.put("productId", item.get("productId"));
            row.put("qty", qty);
            row.put("batchNo", batchNo);
            // 收货单价跟着采购价走，入库时按它刷新产品移动平均成本
            row.put("price", item.get("price"));
            toReceive.add(row);
            totalQty = totalQty.add(qty);
        }
        if (toReceive.isEmpty()) {
            throw new BizException(1002, "没有可收货的明细，请检查是否已全部收货或在途");
        }

        boolean needInspection = request != null && request.needInspection() != null
                ? request.needInspection()
                : Boolean.TRUE.equals(order.get("need_inspection"));
        String remark = request != null && request.remark() != null && !request.remark().isBlank()
                ? request.remark().trim()
                : "采购单 " + orderNo + " 收货";
        List<InboundOrderPort.PurchaseInboundLine> lines = new ArrayList<>();
        for (Map<String, Object> row : toReceive) {
            lines.add(new InboundOrderPort.PurchaseInboundLine(longOf(row.get("itemId")),
                    longOf(row.get("productId")), decimal(row.get("qty")),
                    row.get("batchNo") == null ? null : String.valueOf(row.get("batchNo")),
                    decimal(row.get("price"))));
        }
        InboundOrderPort.CreatedInbound inbound = inboundOrderPort.createPurchaseInbound(
                new InboundOrderPort.PurchaseInboundRequest(longOf(order.get("warehouse_id")),
                        String.valueOf(order.get("supplier_name")), longOf(order.get("supplier_id")),
                        id, needInspection, remark, lines));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", id);
        result.put("orderNo", orderNo);
        result.put("inboundId", inbound.id());
        result.put("inboundNo", inbound.inboundNo());
        result.put("qty", totalQty);
        result.put("needInspection", needInspection);
        operLogService.touch("PURCHASE", id, orderNo,
                "采购单 " + orderNo + " 收货，生成入库单 " + inbound.inboundNo());
        operLogService.action("收货");
        return result;
    }

    /** 入库单完成时调用：把实收数量写回采购明细，并刷新采购单状态 */
    @Transactional
    public void applyReceipt(long inboundOrderId) {
        Long purchaseOrderId = inboundOrderPort.purchaseOrderIdOf(inboundOrderId);
        if (purchaseOrderId == null) {
            return;
        }
        long orderId = purchaseOrderId;
        // 按采购明细回写实收：入库明细属于仓储模块，数量从契约里取
        for (Map.Entry<Long, BigDecimal> entry
                : inboundOrderPort.receivedQtyByPurchaseItem(inboundOrderId).entrySet()) {
            jdbcTemplate.update("""
                    update purchase_order_item set received_qty = received_qty + ? where id = ?
                    """, entry.getValue(), entry.getKey());
        }
        jdbcTemplate.update("""
                update purchase_order o
                set status = case when s.received >= s.total then 'RECEIVED'
                                  else 'PARTIAL' end
                from (select coalesce(sum(qty), 0) as total, coalesce(sum(received_qty), 0) as received
                      from purchase_order_item where order_id = ?) s
                where o.id = ? and o.status in ('CONFIRMED', 'PARTIAL')
                """, orderId, orderId);
        List<Map<String, Object>> order = jdbcTemplate.queryForList(
                "select order_no, status from purchase_order where id = ?", orderId);
        if (!order.isEmpty()) {
            operLogService.touch("PURCHASE", orderId, String.valueOf(order.get(0).get("order_no")),
                    "采购单 " + order.get(0).get("order_no") + " 到货回写，当前状态 " + order.get(0).get("status"));
        }
        // 入库完成即生成暂估应付，发票到了再做三单匹配
        apService.createAccrualForInbound(inboundOrderId);
    }

    /** 采购单取消或删除时，把已转数量退回采购申请 */
    private void releaseRequisition(long orderId) {
        Long requisitionId = jdbcTemplate.queryForObject(
                "select requisition_id from purchase_order where id = ?", Long.class, orderId);
        if (requisitionId == null) {
            return;
        }
        jdbcTemplate.update("""
                update purchase_requisition_item i
                set converted_qty = greatest(i.converted_qty - x.qty, 0)
                from (select requisition_item_id, sum(qty) as qty
                      from purchase_order_item
                      where order_id = ? and requisition_item_id is not null
                      group by requisition_item_id) x
                where i.id = x.requisition_item_id
                """, orderId);
        jdbcTemplate.update("""
                update purchase_requisition r
                set status = case when s.converted >= s.total then 'CONVERTED'
                                  when s.converted > 0 then 'PARTIAL'
                                  else 'APPROVED' end
                from (select coalesce(sum(qty), 0) as total, coalesce(sum(converted_qty), 0) as converted
                      from purchase_requisition_item where req_id = ?) s
                where r.id = ? and r.status in ('APPROVED', 'PARTIAL', 'CONVERTED')
                """, requisitionId, requisitionId);
    }

    /** 参考价：同供应商同物料最近一次未取消的采购价 */
    public Map<String, Object> lastPrice(long supplierId, long productId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.price, o.order_no as "orderNo", to_char(o.order_date, 'YYYY-MM-DD') as "orderDate"
                from purchase_order_item i
                join purchase_order o on o.id = i.order_id
                where o.supplier_id = ? and i.product_id = ? and o.status <> 'CANCELLED'
                order by o.order_date desc, o.id desc
                limit 1
                """, supplierId, productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> detailItems(long id) {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId",
                       i.qty, i.received_qty as "receivedQty", i.price, i.amount, i.remark
                from purchase_order_item i
                where i.order_id = ?
                order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        // 在途数量从入库单契约取：采购不该自己去数 inbound_order_item
        Map<Long, BigDecimal> receiving = inboundOrderPort.receivingQtyByPurchaseItem(id);
        for (Map<String, Object> item : items) {
            BigDecimal qty = decimal(item.get("qty"));
            BigDecimal received = decimal(item.get("receivedQty"));
            BigDecimal inTransit = receiving.getOrDefault(longOf(item.get("id")), BigDecimal.ZERO);
            item.put("receivingQty", inTransit);
            item.put("availableQty", qty.subtract(received).subtract(inTransit));
        }
        return items;
    }

    /** 列表页补「在途入库单张数」：一页只问仓储一次，避免每行一次查询 */
    private void fillOpenInboundCounts(List<Map<String, Object>> records) {
        List<Long> ids = records.stream()
                .map(row -> row.get("id"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, Integer> counts = inboundOrderPort.openCountByPurchaseOrder(ids);
        for (Map<String, Object> row : records) {
            row.put("openInboundCount", counts.getOrDefault(longOf(row.get("id")), 0));
        }
    }

    private static List<Map<String, Object>> inboundMaps(List<InboundOrderPort.InboundOrderView> views) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InboundOrderPort.InboundOrderView view : views) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", view.id());
            row.put("inboundNo", view.inboundNo());
            row.put("status", view.status());
            row.put("totalQty", view.totalQty());
            row.put("createdAt", view.createdAt());
            row.put("completedAt", view.completedAt());
            rows.add(row);
        }
        return rows;
    }

    private void insertItems(long orderId, List<ItemRequest> items) {
        for (ItemRequest item : items) {
            BigDecimal price = item.price() == null ? BigDecimal.ZERO : item.price();
            BigDecimal amount = item.qty().multiply(price).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update("""
                    insert into purchase_order_item (order_id, product_id, qty, price, amount, remark,
                                                     requisition_item_id)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, orderId, item.productId(), item.qty(), price, amount, blankToNull(item.remark()),
                    item.requisitionItemId());
        }
    }

    private BigDecimal totalOf(List<ItemRequest> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (ItemRequest item : items) {
            BigDecimal price = item.price() == null ? BigDecimal.ZERO : item.price();
            total = total.add(item.qty().multiply(price).setScale(2, RoundingMode.HALF_UP));
        }
        return total;
    }

    /**
     * 采购单的供应商：没指定时按明细物料的默认供应商兜底。
     *
     * <p>整单只有一个供应商，所以明细指向多个不同供应商时必须人工选 ——
     * 与其替用户猜一个，不如让他明确。全都没维护默认供应商也报错，
     * 而不是静默建出一张没有供应商的单。</p>
     */
    private long resolveSupplierId(OrderRequest request) {
        if (request.supplierId() != null) {
            return request.supplierId();
        }
        List<Long> productIds = request.items().stream()
                .map(ItemRequest::productId).filter(Objects::nonNull).distinct().toList();
        Set<Long> suppliers = masterData.products(productIds).values().stream()
                .map(ProductInfo::defaultSupplierId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (suppliers.isEmpty()) {
            throw new BizException(1002, "请选择供应商（明细物料没有维护默认供应商）");
        }
        if (suppliers.size() > 1) {
            throw new BizException(1002, "明细物料的默认供应商不一致，请手工选择供应商");
        }
        return suppliers.iterator().next();
    }

    private void validate(OrderRequest request) {
        if (request.warehouseId() == null) {
            throw new BizException(1002, "请选择收货仓库");
        }
        if (!masterData.warehouseEnabled(request.warehouseId())) {
            throw new BizException(1002, "收货仓库不存在或已停用");
        }
        if (request.expectedDate() != null && request.orderDate() != null
                && request.expectedDate().isBefore(request.orderDate())) {
            throw new BizException(1002, "预计到货日不能早于下单日期");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少添加一条采购明细");
        }
        for (ItemRequest item : request.items()) {
            if (item.productId() == null) {
                throw new BizException(1002, "采购明细必须选择物料");
            }
            if (item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "采购数量必须大于 0");
            }
            if (item.price() != null && item.price().signum() < 0) {
                throw new BizException(1002, "采购单价不能为负数");
            }
            if (!masterData.productExists(item.productId())) {
                throw new BizException(1002, "采购明细中的物料不存在");
            }
        }
    }

    private Map<String, Object> loadSupplier(long supplierId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, code, lead_days, enabled
                from supplier where id = ?
                """, supplierId);
        if (rows.isEmpty()) {
            throw new BizException(2006, "供应商不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> loadForUpdate(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id, o.order_no, o.status, o.supplier_id, o.warehouse_id,
                       o.need_inspection, s.name as supplier_name
                from purchase_order o join supplier s on s.id = o.supplier_id
                where o.id = ?
                for update of o
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2007, "采购单不存在");
        }
        return rows.get(0);
    }

    private void assertStatus(Map<String, Object> order, String expected, String message) {
        if (!expected.equals(String.valueOf(order.get("status")))) {
            throw new BizException(2014, message);
        }
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static long longOf(Object value) {
        return ((Number) value).longValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
