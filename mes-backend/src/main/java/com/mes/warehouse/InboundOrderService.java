package com.mes.warehouse;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.platform.scope.OrgScopeService;
import com.mes.purchase.api.PurchaseDocPort;
import com.mes.security.CurrentUser;
import com.mes.warehouse.api.InboundOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入库单：采购入库只能由采购订单收货生成；无单据的入库走「例外」通道，
 * 必须说明原因并留痕；采购退货单独走采购退货单，不在这里建单。
 *
 * <p>采购单号与退货金额通过 {@link PurchaseDocPort} 取，本模块不再 join 采购的票据表。</p>
 */
@Service
public class InboundOrderService implements InboundOrderPort {

    private static final List<String> TYPES = List.of("PURCHASE", "RETURN", "OTHER");

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final WarehouseService warehouseService;
    private final com.mes.notice.api.NoticePort notice;
    private final OrgScopeService orgScope;
    private final MasterDataPort masterData;
    private final PurchaseDocPort purchaseDoc;

    public InboundOrderService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                               WarehouseService warehouseService,
                               NoticePort notice,
                               OrgScopeService orgScope,
                               MasterDataPort masterData,
                               PurchaseDocPort purchaseDoc) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.warehouseService = warehouseService;
        this.notice = notice;
        this.orgScope = orgScope;
        this.masterData = masterData;
        this.purchaseDoc = purchaseDoc;
    }

    public record InboundItem(Long productId, BigDecimal qty, String batchNo, BigDecimal unitPrice,
                              String locationCode) {
        /** 兼容老调用：不带库位 */
        public InboundItem(Long productId, BigDecimal qty, String batchNo, BigDecimal unitPrice) {
            this(productId, qty, batchNo, unitPrice, null);
        }
    }

    public record InboundRequest(String type, Long warehouseId, String supplier, Boolean needInspection,
                                 String remark, String noOrderReason, List<InboundItem> items) {
    }

    public PageResult list(String keyword, String status, String type, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(
                " where (o.inbound_no ilike ? or coalesce(o.supplier, '') ilike ?) ");
        args.add(like);
        args.add(like);
        if (status != null && !status.isBlank()) {
            where.append(" and o.status = ? ");
            args.add(status);
        }
        if (type != null && !type.isBlank()) {
            where.append(" and o.type = ? ");
            args.add(type);
        }
        MasterDataViews.appendWarehouseScope(orgScope, masterData, where, args, "o.warehouse_id");
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from inbound_order o
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select o.id, o.inbound_no as "inboundNo", o.type, o.warehouse_id as "warehouseId",
                       o.supplier, o.status,
                       o.source, o.no_order_reason as "noOrderReason",
                       o.supplier_id as "supplierId",
                       o.purchase_order_id as "purchaseOrderId",
                       o.need_inspection as "needInspection",
                       (select count(*) from inbound_order_item i where i.inbound_id = o.id) as "itemCount",
                       (select coalesce(sum(i.qty), 0) from inbound_order_item i where i.inbound_id = o.id) as "totalQty",
                       o.remark, o.created_by as "createdBy",
                       to_char(o.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(o.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from inbound_order o
                """ + where + " order by o.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillWarehouseNames(masterData, records,
                "warehouseId", "warehouseName");
        fillPurchaseOrderNos(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    /** 入库异常概览：待检、本月无单入库、本月采购退货 */
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                select (select count(*) from inbound_order where status = 'DRAFT') as "draft",
                       (select count(*) from inbound_order where status = 'PENDING_QC') as "pendingQc",
                       (select count(*) from inbound_order
                         where source = 'MANUAL' and status <> 'CANCELLED'
                           and created_at >= date_trunc('month', current_date)) as "manualMonth"
                """));
        // 本月采购退货是采购自己的口径，问一次就够
        PurchaseDocPort.ReturnStat returns = purchaseDoc.completedReturnsSince(LocalDate.now().withDayOfMonth(1));
        result.put("returnMonth", returns.count());
        result.put("returnAmount", returns.amount());
        return result;
    }

    /** 补采购单号：采购单归采购模块 */
    private void fillPurchaseOrderNos(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> row.get("purchaseOrderId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> orderNos = purchaseDoc.orderNos(ids);
        for (Map<String, Object> row : rows) {
            Object id = row.get("purchaseOrderId");
            row.put("purchaseOrderNo", id == null ? null : orderNos.get(((Number) id).longValue()));
        }
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id, o.inbound_no as "inboundNo", o.type, o.warehouse_id as "warehouseId",
                       o.supplier, o.status,
                       o.source, o.no_order_reason as "noOrderReason",
                       o.supplier_id as "supplierId",
                       o.purchase_order_id as "purchaseOrderId",
                       o.need_inspection as "needInspection",
                       (select coalesce(sum(i.qty), 0) from inbound_order_item i where i.inbound_id = o.id) as "totalQty",
                       o.remark, o.created_by as "createdBy",
                       to_char(o.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(o.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from inbound_order o
                where o.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4001, "入库单不存在");
        }
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        fillPurchaseOrderNos(rows);
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.qty, i.batch_no as "batchNo",
                       i.unit_price as "unitPrice", i.location_code as "locationCode",
                       round(i.qty * i.unit_price, 2) as "amount"
                from inbound_order_item i
                where i.inbound_id = ?
                order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        detail.put("items", items);
        return detail;
    }

    @Transactional
    public long create(InboundRequest request) {
        validate(request);
        String type = normalizeType(request.type());
        assertManualType(type);
        String source = "RETURN".equals(type) ? "RETURN" : "MANUAL";
        String reason = requireReason(request.noOrderReason());
        String inboundNo = codeGenerator.next("IN");
        boolean needInspection = request.needInspection() == null || request.needInspection();
        Long id = jdbcTemplate.queryForObject("""
                insert into inbound_order (inbound_no, type, warehouse_id, supplier, status,
                                           need_inspection, remark, created_by, source, no_order_reason)
                values (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, inboundNo, type, request.warehouseId(), request.supplier(),
                needInspection, request.remark(), CurrentUser.name(), source, reason);
        insertItems(id, request.items());
        return id;
    }

    @Transactional
    public void update(long id, InboundRequest request) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿入库单可以修改");
        if ("PO".equals(String.valueOf(order.get("source")))) {
            throw new BizException(4006, "采购收货生成的入库单不支持修改，如需调整请先取消再重新收货");
        }
        validate(request);
        String type = normalizeType(request.type());
        assertManualType(type);
        String source = "RETURN".equals(type) ? "RETURN" : "MANUAL";
        String reason = requireReason(request.noOrderReason());
        jdbcTemplate.update("""
                update inbound_order
                set type = ?, warehouse_id = ?, supplier = ?, need_inspection = ?, remark = ?,
                    source = ?, no_order_reason = ?
                where id = ?
                """, type, request.warehouseId(), request.supplier(), request.needInspection() == null
                        || request.needInspection(), request.remark(), source, reason, id);
        jdbcTemplate.update("delete from inbound_order_item where inbound_id = ?", id);
        insertItems(id, request.items());
    }

    @Transactional
    public void submit(long id) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿入库单可以提交");
        Integer items = jdbcTemplate.queryForObject(
                "select count(*) from inbound_order_item where inbound_id = ?", Integer.class, id);
        if (items == null || items == 0) {
            throw new BizException(4003, "入库单没有明细，不能提交");
        }
        Object needInspection = order.get("need_inspection");
        if (Boolean.TRUE.equals(needInspection)) {
            jdbcTemplate.update("update inbound_order set status = 'PENDING_QC' where id = ?", id);
            // 待检入库单推给质检，免得货堆在收货区没人知道
            String inboundNo = String.valueOf(order.get("inbound_no"));
            notice.pushToRoles(List.of("QC"), NoticeMessage.of("TASK",
                    "入库单 " + inboundNo + " 待来料检验",
                    "仓库已提交入库单，检验合格后自动入库，请安排来料检验", "INBOUND", inboundNo));
            return;
        }
        warehouseService.completeInbound(id, null);
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> order = loadOrder(id);
        String status = String.valueOf(order.get("status"));
        if (!"DRAFT".equals(status) && !"PENDING_QC".equals(status)) {
            throw new BizException(4004, "当前状态不允许取消，只有草稿或待检入库单可以取消");
        }
        jdbcTemplate.update("update inbound_order set status = 'CANCELLED' where id = ?", id);
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿入库单可以删除");
        jdbcTemplate.update("delete from inbound_order_item where inbound_id = ?", id);
        jdbcTemplate.update("delete from inbound_order where id = ?", id);
    }

    // ------------------------------------------------------------------
    // 对外契约 InboundOrderPort：采购收货建单 + 回读。写接口与调用方同事务
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public CreatedInbound createPurchaseInbound(PurchaseInboundRequest request) {
        if (request.warehouseId() <= 0) {
            throw new BizException(1002, "请选择入库仓库");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new BizException(1002, "入库单没有明细");
        }
        if (!masterData.warehouseEnabled(request.warehouseId())) {
            throw new BizException(1002, "入库仓库不存在或已停用");
        }
        String inboundNo = codeGenerator.next("IN");
        Long id = jdbcTemplate.queryForObject("""
                insert into inbound_order (inbound_no, type, warehouse_id, supplier, status, need_inspection,
                                           remark, created_by, supplier_id, purchase_order_id, source)
                values (?, 'PURCHASE', ?, ?, 'DRAFT', ?, ?, ?, ?, ?, 'PO')
                returning id
                """, Long.class, inboundNo, request.warehouseId(), request.supplierName(),
                request.needInspection(), request.remark(), CurrentUser.name(),
                request.supplierId(), request.purchaseOrderId());
        for (PurchaseInboundLine line : request.lines()) {
            jdbcTemplate.update("""
                    insert into inbound_order_item (inbound_id, product_id, qty, batch_no, purchase_item_id,
                                                    unit_price)
                    values (?, ?, ?, ?, ?, ?)
                    """, id, line.productId(), line.qty(), line.batchNo(), line.purchaseItemId(),
                    line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice());
        }
        return new CreatedInbound(id, inboundNo);
    }

    @Override
    public InboundOrderView inbound(long inboundOrderId) {
        return inbounds(List.of(inboundOrderId)).get(inboundOrderId);
    }

    @Override
    public Map<Long, InboundOrderView> inbounds(Collection<Long> inboundOrderIds) {
        Map<Long, InboundOrderView> map = new LinkedHashMap<>();
        List<Long> ids = distinctIds(inboundOrderIds);
        if (ids.isEmpty()) {
            return map;
        }
        String marks = placeholders(ids.size());
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, inbound_no, type, status, source, purchase_order_id, supplier_id, warehouse_id,
                       (select coalesce(sum(x.qty), 0) from inbound_order_item x where x.inbound_id = inbound_order.id) as "totalQty",
                       completed_at::date as "completedDate",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from inbound_order
                where id in (""" + marks + ") order by id desc", ids.toArray())) {
            InboundOrderView view = viewOf(row);
            map.put(view.id(), view);
        }
        return map;
    }

    @Override
    public Long purchaseOrderIdOf(long inboundOrderId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "select purchase_order_id from inbound_order where id = ?", Long.class, inboundOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public int countByPurchaseOrder(long purchaseOrderId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from inbound_order where purchase_order_id = ?", Integer.class,
                purchaseOrderId);
        return count == null ? 0 : count;
    }

    @Override
    public int countOpenByPurchaseOrder(long purchaseOrderId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from inbound_order
                where purchase_order_id = ? and status in ('DRAFT', 'PENDING_QC')
                """, Integer.class, purchaseOrderId);
        return count == null ? 0 : count;
    }

    @Override
    public Map<Long, Integer> openCountByPurchaseOrder(Collection<Long> purchaseOrderIds) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        List<Long> ids = distinctIds(purchaseOrderIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select purchase_order_id as "orderId", count(*) as "openCount"
                from inbound_order
                where purchase_order_id in (""" + placeholders(ids.size()) + """
                  ) and status in ('DRAFT', 'PENDING_QC')
                group by purchase_order_id
                """, ids.toArray())) {
            map.put(longOf(row.get("orderId")), ((Number) row.get("openCount")).intValue());
        }
        return map;
    }

    @Override
    public List<InboundOrderView> byPurchaseOrder(long purchaseOrderId) {
        List<InboundOrderView> views = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, inbound_no, type, status, source, purchase_order_id, supplier_id, warehouse_id,
                       (select coalesce(sum(x.qty), 0) from inbound_order_item x where x.inbound_id = inbound_order.id) as "totalQty",
                       completed_at::date as "completedDate",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from inbound_order
                where purchase_order_id = ?
                order by id desc
                """, purchaseOrderId)) {
            views.add(viewOf(row));
        }
        return views;
    }

    @Override
    public Map<Long, BigDecimal> receivingQtyByPurchaseItem(long purchaseOrderId) {
        return qtyByPurchaseItem("""
                select x.purchase_item_id as "itemId", sum(x.qty) as "qty"
                from inbound_order_item x
                join inbound_order o on o.id = x.inbound_id
                where o.purchase_order_id = ? and o.status in ('DRAFT', 'PENDING_QC')
                  and x.purchase_item_id is not null
                group by x.purchase_item_id
                """, purchaseOrderId);
    }

    @Override
    public Map<Long, BigDecimal> receivedQtyByPurchaseItem(long inboundOrderId) {
        return qtyByPurchaseItem("""
                select purchase_item_id as "itemId", sum(qty) as "qty"
                from inbound_order_item
                where inbound_id = ? and purchase_item_id is not null
                group by purchase_item_id
                """, inboundOrderId);
    }

    @Override
    public List<InboundOrderView> completedPurchaseInbounds(int limit) {
        List<InboundOrderView> views = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, inbound_no, type, status, source, purchase_order_id, supplier_id, warehouse_id,
                       (select coalesce(sum(x.qty), 0) from inbound_order_item x where x.inbound_id = inbound_order.id) as "totalQty",
                       completed_at::date as "completedDate",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from inbound_order
                where status = 'COMPLETED' and purchase_order_id is not null
                order by id desc
                limit ?
                """, Math.max(1, limit))) {
            views.add(viewOf(row));
        }
        return views;
    }

    @Override
    public List<InboundLine> lines(long inboundOrderId) {
        return linesByInbound(List.of(inboundOrderId)).getOrDefault(inboundOrderId, List.of());
    }

    @Override
    public Map<Long, List<InboundLine>> linesByInbound(Collection<Long> inboundOrderIds) {
        Map<Long, List<InboundLine>> map = new LinkedHashMap<>();
        List<Long> ids = distinctIds(inboundOrderIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, inbound_id, product_id, qty, batch_no, purchase_item_id, unit_price
                from inbound_order_item
                where inbound_id in (""" + placeholders(ids.size()) + ") order by id", ids.toArray())) {
            InboundLine line = new InboundLine(longOf(row.get("id")), longOf(row.get("inbound_id")),
                    longOf(row.get("product_id")), decimal(row.get("qty")),
                    row.get("batch_no") == null ? null : String.valueOf(row.get("batch_no")),
                    row.get("purchase_item_id") == null ? null : longOf(row.get("purchase_item_id")),
                    decimal(row.get("unit_price")));
            map.computeIfAbsent(line.inboundOrderId(), key -> new ArrayList<>()).add(line);
        }
        return map;
    }

    @Override
    public List<InboundOrderView> completedInbounds(Long supplierId, LocalDate from, LocalDate to) {
        List<InboundOrderView> views = new ArrayList<>();
        String sql = """
                select id, inbound_no, type, status, source, purchase_order_id, supplier_id, warehouse_id,
                       (select coalesce(sum(x.qty), 0) from inbound_order_item x where x.inbound_id = inbound_order.id) as "totalQty",
                       completed_at::date as "completedDate",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from inbound_order
                where status = 'COMPLETED' and completed_at::date between ? and ?
                """ + (supplierId == null ? "" : " and supplier_id = ? ") + " order by id desc";
        List<Object> args = new ArrayList<>(List.of(from, to));
        if (supplierId != null) {
            args.add(supplierId);
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args.toArray())) {
            views.add(viewOf(row));
        }
        return views;
    }

    @Override
    public Set<Long> searchIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "select id from inbound_order where inbound_no ilike ? order by id desc limit 500",
                Long.class, "%" + keyword.trim() + "%"));
    }

    private Map<Long, BigDecimal> qtyByPurchaseItem(String sql, long arg) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, arg)) {
            map.merge(longOf(row.get("itemId")), decimal(row.get("qty")), BigDecimal::add);
        }
        return map;
    }

    private static InboundOrderView viewOf(Map<String, Object> row) {
        return new InboundOrderView(longOf(row.get("id")), String.valueOf(row.get("inbound_no")),
                row.get("type") == null ? null : String.valueOf(row.get("type")),
                row.get("status") == null ? null : String.valueOf(row.get("status")),
                row.get("source") == null ? null : String.valueOf(row.get("source")),
                row.get("purchase_order_id") == null ? null : longOf(row.get("purchase_order_id")),
                row.get("supplier_id") == null ? null : longOf(row.get("supplier_id")),
                row.get("warehouse_id") == null ? null : longOf(row.get("warehouse_id")),
                decimal(row.get("totalQty")),
                row.get("completedDate") == null ? null : ((java.sql.Date) row.get("completedDate")).toLocalDate(),
                row.get("createdAt") == null ? null : String.valueOf(row.get("createdAt")),
                row.get("completedAt") == null ? null : String.valueOf(row.get("completedAt")));
    }

    private static List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static long longOf(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private void insertItems(long id, List<InboundItem> items) {
        for (InboundItem item : items) {
            jdbcTemplate.update("""
                    insert into inbound_order_item (inbound_id, product_id, qty, batch_no, unit_price,
                                                    location_code)
                    values (?, ?, ?, ?, ?, ?)
                    """, id, item.productId(), item.qty(), item.batchNo(),
                    item.unitPrice() == null ? BigDecimal.ZERO : item.unitPrice(),
                    item.locationCode());
        }
    }

    private Map<String, Object> loadOrder(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, inbound_no, status, need_inspection, type, source
                from inbound_order where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4001, "入库单不存在");
        }
        return rows.get(0);
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? "OTHER" : type.trim();
    }

    /** 采购入库只能从采购订单收货生成，手工建单只允许退货入库与其他入库 */
    private void assertManualType(String type) {
        if ("PURCHASE".equals(type)) {
            throw new BizException(4006, "采购入库请从采购订单收货生成，不支持手工建单");
        }
    }

    /** 无来源单据的入库必须说明原因，便于事后追溯 */
    private String requireReason(String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.length() < 2) {
            throw new BizException(4007, "无采购单据的入库必须填写原因说明（如期初建账、赠品、退货返修等）");
        }
        return text;
    }

    private void assertStatus(Map<String, Object> order, String expected, String message) {
        if (!expected.equals(String.valueOf(order.get("status")))) {
            throw new BizException(4005, message);
        }
    }

    private void validate(InboundRequest request) {
        if (request.warehouseId() == null) {
            throw new BizException(1002, "请选择入库仓库");
        }
        String type = normalizeType(request.type());
        if (!TYPES.contains(type)) {
            throw new BizException(1002, "入库类型不正确");
        }
        if (!masterData.warehouseEnabled(request.warehouseId())) {
            throw new BizException(1002, "入库仓库不存在或已停用");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少添加一条入库明细");
        }
        for (InboundItem item : request.items()) {
            if (item.productId() == null || item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "入库明细的产品与数量不能为空，数量必须大于 0");
            }
            if (item.unitPrice() != null && item.unitPrice().signum() < 0) {
                throw new BizException(1002, "入库单价不能为负数");
            }
            if (!masterData.productExists(item.productId())) {
                throw new BizException(1002, "产品不存在");
            }
        }
    }
}

