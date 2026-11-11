package com.mes.shipping;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.inventory.api.BatchMove;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.masterdata.api.ProductInfo;
import com.mes.platform.audit.OperLogService;
import com.mes.platform.scope.OrgScopeService;
import com.mes.sales.api.SalesOrderPort;
import com.mes.security.CurrentUser;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发货与签收。
 *
 * <p>发货单本身只拥有 shipment / shipment_item / shipment_batch 三张表。
 * 订单号与客户名以前靠 join {@code sales_order} / {@code customer} 拿，现在：
 * 名称走主数据契约（{@link MasterDataViews}），订单状态与已发数量走销售契约
 * （{@link SalesOrderPort}）—— 因为「发完没有」和「还能发多少」是销售订单的口径，
 * 连带着把订单推进到「已发货 / 已完成」也该由销售侧决定，而不是发货单顺手 update。</p>
 */
@Service
public class ShippingService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final OperLogService operLogService;
    private final OrgScopeService orgScope;
    private final SalesOrderPort salesOrders;
    private final MasterDataPort masterData;
    private final TaskPort taskService;

    public ShippingService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator, InventoryPort inventoryService,
                           OperLogService operLogService,
                           OrgScopeService orgScope,
                           SalesOrderPort salesOrders,
                           MasterDataPort masterData,
                           TaskPort taskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.operLogService = operLogService;
        this.orgScope = orgScope;
        this.salesOrders = salesOrders;
        this.masterData = masterData;
        this.taskService = taskService;
    }

    public record ShipmentItemRequest(Long orderItemId, BigDecimal qty) {
    }

    public record ShipmentRequest(Long salesOrderId, String carrier, String trackingNo, String address,
                                  List<ShipmentItemRequest> items) {
    }

    public PageResult list(String keyword, String status, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        // 订单号与客户名都在别人家里：先换成 id 集合，发货单侧只用 in 条件
        Set<Long> orderIds = salesOrders.searchIds(keyword);
        Set<Long> customerIds = masterData.searchCustomerIds(keyword);
        StringBuilder where = new StringBuilder(" where (s.shipment_no ilike ? ");
        args.add(like);
        if (!orderIds.isEmpty()) {
            where.append(" or s.sales_order_id in (").append(marks(orderIds)).append(") ");
            args.addAll(orderIds);
        }
        if (!customerIds.isEmpty()) {
            where.append(" or s.customer_id in (").append(marks(customerIds)).append(") ");
            args.addAll(customerIds);
        }
        where.append(") ");
        if (status != null && !status.isBlank()) {
            where.append(" and s.status = ? ");
            args.add(status);
        }
        // 多工厂：发货单跟着销售订单的工厂走
        appendOrderScope(where, args);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from shipment s" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select s.id, s.shipment_no as "shipmentNo", s.sales_order_id as "salesOrderId",
                       s.customer_id as "customerId", s.status, s.carrier,
                       s.tracking_no as "trackingNo",
                       (select coalesce(sum(i.qty), 0) from shipment_item i where i.shipment_id = s.id) as "totalQty",
                       to_char(s.created_at, 'YYYY-MM-DD') as "createdAt",
                       to_char(s.shipped_at, 'YYYY-MM-DD HH24:MI') as "shippedAt",
                       to_char(s.signed_at, 'YYYY-MM-DD HH24:MI') as "signedAt"
                from shipment s
                """ + where + " order by s.id desc limit ? offset ?", pageArgs.toArray());
        fillOrderAndCustomer(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select s.id, s.shipment_no as "shipmentNo", s.sales_order_id as "salesOrderId",
                       s.customer_id as "customerId",
                       s.status, s.carrier, s.tracking_no as "trackingNo", s.address,
                       to_char(s.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(s.shipped_at, 'YYYY-MM-DD HH24:MI') as "shippedAt",
                       to_char(s.signed_at, 'YYYY-MM-DD HH24:MI') as "signedAt"
                from shipment s
                where s.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException("发货单不存在");
        }
        fillOrderAndCustomer(rows);
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.sales_order_item_id as "orderItemId", i.product_id as "productId", i.qty
                from shipment_item i
                where i.shipment_id = ? order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        detail.put("items", items);
        return detail;
    }

    @Transactional
    public long create(ShipmentRequest request) {
        if (request.salesOrderId() == null) {
            throw new BizException(1002, "请选择销售订单");
        }
        SalesOrderPort.SalesOrderRef order = salesOrders.order(request.salesOrderId());
        if (order == null) {
            throw new BizException("销售订单不存在");
        }
        if (!"READY_TO_SHIP".equals(order.status())) {
            throw new BizException(2012, "只有待发货订单才能创建发货单");
        }
        if (order.customerId() == null) {
            throw new BizException("销售订单没有客户，无法发货");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请填写发货明细");
        }
        Long customerId = order.customerId();
        String shipmentNo = codeGenerator.next("SH");
        Long shipmentId = jdbcTemplate.queryForObject("""
                insert into shipment (shipment_no, sales_order_id, customer_id, status, carrier, tracking_no, address)
                values (?, ?, ?, 'DRAFT', ?, ?, ?) returning id
                """, Long.class, shipmentNo, request.salesOrderId(), customerId,
                request.carrier(), request.trackingNo(), request.address());

        for (ShipmentItemRequest item : request.items()) {
            if (item.orderItemId() == null || item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "发货明细数量必须大于 0");
            }
            SalesOrderPort.OrderItemRef orderItem = salesOrders.item(item.orderItemId());
            if (orderItem == null || orderItem.orderId() != request.salesOrderId()) {
                throw new BizException(1002, "发货明细与销售订单不匹配");
            }
            if (orderItem.cancelled()) {
                throw new BizException(2019, "该明细行已被取消，不能发货");
            }
            BigDecimal remaining = orderItem.qty().subtract(orderItem.shippedQty());
            if (item.qty().compareTo(remaining) > 0) {
                throw new BizException(2013, "发货数量超过未发数量，最多可发 "
                        + remaining.stripTrailingZeros().toPlainString());
            }
            jdbcTemplate.update("""
                    insert into shipment_item (shipment_id, sales_order_item_id, product_id, qty)
                    values (?, ?, ?, ?)
                    """, shipmentId, item.orderItemId(), orderItem.productId(), item.qty());
        }
        // 发货单开出来了，销售的「待发货」任务就算被接住
        taskService.close(TaskTypes.SHIP_READY, order.orderNo(), CurrentUser.name(),
                "已开发货单 " + shipmentNo);
        return shipmentId;
    }

    @Transactional
    public void ship(long id) {
        Map<String, Object> shipment = loadShipment(id);
        if (!"DRAFT".equals(String.valueOf(shipment.get("status")))) {
            throw new BizException(2014, "只有草稿发货单可以执行发货");
        }
        String shipmentNo = String.valueOf(shipment.get("shipment_no"));
        long salesOrderId = ((Number) shipment.get("sales_order_id")).longValue();
        long finishedWarehouse = inventoryService.warehouseOfType("FINISHED");
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.sales_order_item_id as "orderItemId", i.product_id as "productId", i.qty
                from shipment_item i
                where i.shipment_id = ?
                """, id);
        if (items.isEmpty()) {
            throw new BizException(2015, "发货单没有明细");
        }
        List<Long> productIds = items.stream().map(row -> ((Number) row.get("productId")).longValue())
                .distinct().toList();
        Map<Long, ProductInfo> products = masterData.products(productIds);
        for (Map<String, Object> item : items) {
            long productId = ((Number) item.get("productId")).longValue();
            BigDecimal qty = new BigDecimal(String.valueOf(item.get("qty")));
            BigDecimal stock = inventoryService.stock(productId, finishedWarehouse);
            if (stock.compareTo(qty) < 0) {
                ProductInfo product = products.get(productId);
                throw new BizException(3004, (product == null ? "物料 " + productId : product.name())
                        + " 成品库存不足，可用 " + stock.stripTrailingZeros().toPlainString()
                        + "，需要 " + qty.stripTrailingZeros().toPlainString());
            }
        }
        for (Map<String, Object> item : items) {
            long productId = ((Number) item.get("productId")).longValue();
            BigDecimal qty = new BigDecimal(String.valueOf(item.get("qty")));
            long shipmentItemId = ((Number) item.get("id")).longValue();
            // 发货按批次先进先出，实际发走的成品批次落在明细上，追溯才能查到客户
            List<BatchMove> moves =
                    inventoryService.ship(productId, finishedWarehouse, qty, shipmentNo);
            for (BatchMove move : moves) {
                jdbcTemplate.update("""
                        insert into shipment_batch (shipment_item_id, batch_no, qty) values (?, ?, ?)
                        """, shipmentItemId, move.batchNo(), move.qty());
            }
            salesOrders.addShippedQty(((Number) item.get("orderItemId")).longValue(), qty);
        }
        jdbcTemplate.update("""
                update shipment set status = 'SHIPPED', shipped_at = now(), version = version + 1 where id = ?
                """, id);

        salesOrders.markShippedIfComplete(salesOrderId);
        operLogService.touch("SHIPMENT", id, shipmentNo, "发货单 " + shipmentNo + " 出库完成");
        operLogService.action("发货");
    }

    @Transactional
    public void sign(long id) {
        Map<String, Object> shipment = loadShipment(id);
        if (!"SHIPPED".equals(String.valueOf(shipment.get("status")))) {
            throw new BizException(2016, "只有已发货单据可以签收");
        }
        jdbcTemplate.update("""
                update shipment set status = 'SIGNED', signed_at = now(), version = version + 1 where id = ?
                """, id);
        long salesOrderId = ((Number) shipment.get("sales_order_id")).longValue();
        salesOrders.markCompletedIfComplete(salesOrderId);
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> shipment = loadShipment(id);
        if (!"DRAFT".equals(String.valueOf(shipment.get("status")))) {
            throw new BizException(2017, "只有草稿发货单可以删除");
        }
        jdbcTemplate.update("delete from shipment_item where shipment_id = ?", id);
        jdbcTemplate.update("delete from shipment where id = ?", id);
    }

    @Transactional
    public void updateTracking(long id, String carrier, String trackingNo) {
        Map<String, Object> shipment = loadShipment(id);
        if (!"DRAFT".equals(String.valueOf(shipment.get("status")))) {
            throw new BizException(2018, "已发货单据不能修改物流信息");
        }
        jdbcTemplate.update("update shipment set carrier = ?, tracking_no = ? where id = ?", carrier, trackingNo, id);
    }

    private Map<String, Object> loadShipment(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, shipment_no, status, sales_order_id from shipment where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException("发货单不存在");
        }
        return rows.get(0);
    }

    /** 补订单号与客户名：两者都不在发货单自己的表里 */
    private void fillOrderAndCustomer(List<Map<String, Object>> rows) {
        Set<Long> orderIds = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (row.get("salesOrderId") instanceof Number number) {
                orderIds.add(number.longValue());
            }
        }
        Map<Long, String> orderNos = salesOrders.orderNos(orderIds);
        for (Map<String, Object> row : rows) {
            row.put("orderNo", row.get("salesOrderId") instanceof Number number
                    ? orderNos.get(number.longValue()) : null);
        }
        MasterDataViews.fillCustomerNames(masterData, rows, "customerId", "customerName");
    }

    /** 发货单没有工厂列，隔离条件落在订单上：换来一份「本厂 + 未归属」的订单 id 集合 */
    private void appendOrderScope(StringBuilder where, List<Object> args) {
        Long factory = orgScope.listFilter();
        if (factory == null) {
            return;
        }
        Set<Long> orderIds = salesOrders.idsInFactory(factory);
        if (orderIds.isEmpty()) {
            where.append(" and 1 = 0 ");
            return;
        }
        where.append(" and s.sales_order_id in (").append(marks(orderIds)).append(") ");
        args.addAll(orderIds);
    }

    private static String marks(Collection<Long> ids) {
        return String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    }
}
