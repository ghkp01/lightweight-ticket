package com.mes.trace;

import com.mes.common.BizException;
import com.mes.inventory.InventoryService;
import com.mes.quality.QualityService;
import com.mes.shipping.ShippingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 批次追溯：领料按批次先进先出、成品批次=工单号、发货记批次，
 * 最终能顺着「原料批次 → 工单 → 成品批次 → 客户」走完整条链。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BatchTraceTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private QualityService qualityService;

    @Autowired
    private ShippingService shippingService;

    @Autowired
    private TraceService traceService;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long rawProductId;
    private long rawWarehouseId;
    private long finishedWarehouseId;
    private long workOrderId;
    private long customerId;
    private long salesOrderItemId;
    private String workNo;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 100) returning id
                """, Long.class, "BT" + suffix, "批次追溯成品" + suffix);
        rawProductId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 2) returning id
                """, Long.class, "BTR" + suffix, "批次追溯原料" + suffix);
        rawWarehouseId = inventoryService.warehouseOfType("RAW");
        finishedWarehouseId = inventoryService.warehouseOfType("FINISHED");

        workNo = "WO" + suffix;
        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 6, 'IN_PROGRESS') returning id
                """, Long.class, workNo, productId);

        customerId = jdbc.queryForObject("""
                insert into customer (code, name) values (?, ?) returning id
                """, Long.class, "BTC" + suffix, "批次追溯客户" + suffix);
        long salesOrderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, status)
                values (?, ?, current_date, 'SHIPPED') returning id
                """, Long.class, "BTX" + suffix, customerId);
        salesOrderItemId = jdbc.queryForObject("""
                insert into sales_order_item (order_id, product_id, qty, price, amount)
                values (?, ?, 6, 100, 600) returning id
                """, Long.class, salesOrderId, productId);
    }

    private void stockRaw(String batchNo, String qty) {
        inventoryService.inboundWithBatch(rawProductId, rawWarehouseId, new BigDecimal(qty),
                "INBOUND", "IN-" + batchNo, "来料入库", batchNo, "INBOUND", null);
    }

    private BigDecimal batchQty(String batchNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select coalesce(sum(qty), 0) as qty from inventory_batch where batch_no = ?
                """, batchNo);
        return new BigDecimal(String.valueOf(rows.get(0).get("qty")));
    }

    @Test
    @DisplayName("领料按批次先进先出，消耗明细记到工单")
    void issueConsumesBatchesInFifoOrder() {
        stockRaw("RAW-A", "3");
        stockRaw("RAW-B", "5");

        inventoryService.issue(rawProductId, rawWarehouseId, new BigDecimal("5"), workNo);

        assertEquals(0, batchQty("RAW-A").compareTo(BigDecimal.ZERO));
        assertEquals(0, batchQty("RAW-B").compareTo(new BigDecimal("3")));

        List<Map<String, Object>> consumed = jdbc.queryForList("""
                select batch_no, qty from work_order_material_batch
                where work_order_id = ? order by id
                """, workOrderId);
        assertEquals(2, consumed.size());
        assertEquals("RAW-A", consumed.get(0).get("batch_no"));
        assertEquals(0, new BigDecimal("3").compareTo(new BigDecimal(String.valueOf(consumed.get(0).get("qty")))));
        assertEquals("RAW-B", consumed.get(1).get("batch_no"));
        assertEquals(0, new BigDecimal("2").compareTo(new BigDecimal(String.valueOf(consumed.get(1).get("qty")))));

        // 汇总库存与批次合计一致
        BigDecimal total = inventoryService.stock(rawProductId, rawWarehouseId);
        assertEquals(0, new BigDecimal("3").compareTo(total));

        // 流水按批次逐条记
        Integer txnRows = jdbc.queryForObject("""
                select count(*) from stock_txn where ref_no = ? and txn_type = 'ISSUE'
                """, Integer.class, workNo);
        assertEquals(2, txnRows);
    }

    @Test
    @DisplayName("完工检合格入库：成品批次号 = 工单号")
    void finishedInspectionCreatesWorkOrderBatch() {
        jdbc.update("update work_order set status = 'FINISHED' where id = ?", workOrderId);

        qualityService.create(new QualityService.InspectionRequest("FINAL", "WORK_ORDER", workNo, workOrderId,
                productId, new BigDecimal("6"), BigDecimal.ZERO, "qc", "", null, null, null, null,
                null, null, null));

        Map<String, Object> batch = jdbc.queryForMap("""
                select qty, source, work_order_id from inventory_batch
                where product_id = ? and warehouse_id = ? and batch_no = ?
                """, productId, finishedWarehouseId, workNo);
        assertEquals(0, new BigDecimal("6").compareTo(new BigDecimal(String.valueOf(batch.get("qty")))));
        assertEquals("PRODUCTION", batch.get("source"));
        assertEquals(workOrderId, ((Number) batch.get("work_order_id")).longValue());
    }

    @Test
    @DisplayName("原料批次 → 工单 → 成品批次 → 客户，整条链能走通")
    void traceReachesCustomerFromMaterialBatch() {
        stockRaw("RAW-A", "3");
        stockRaw("RAW-B", "5");
        inventoryService.issue(rawProductId, rawWarehouseId, new BigDecimal("5"), workNo);

        jdbc.update("update work_order set status = 'FINISHED' where id = ?", workOrderId);
        qualityService.create(new QualityService.InspectionRequest("FINAL", "WORK_ORDER", workNo, workOrderId,
                productId, new BigDecimal("6"), BigDecimal.ZERO, "qc", "", null, null, null, null,
                null, null, null));

        long shipmentId = jdbc.queryForObject("""
                insert into shipment (shipment_no, sales_order_id, customer_id, status)
                select ?, id, ?, 'DRAFT' from sales_order where id = (
                    select order_id from sales_order_item where id = ?)
                returning id
                """, Long.class, "SH" + System.nanoTime(), customerId, salesOrderItemId);
        String shipmentNo = jdbc.queryForObject("select shipment_no from shipment where id = ?", String.class, shipmentId);
        jdbc.update("""
                insert into shipment_item (shipment_id, sales_order_item_id, product_id, qty)
                values (?, ?, ?, 4)
                """, shipmentId, salesOrderItemId, productId);
        shippingService.ship(shipmentId);

        // 向上：原料批次被哪张工单用掉
        Map<String, Object> rawTrace = traceService.traceBatch("RAW-A");
        List<?> usedIn = (List<?>) rawTrace.get("usedIn");
        assertEquals(1, usedIn.size());
        assertEquals(workNo, ((Map<?, ?>) usedIn.get(0)).get("workNo"));

        // 向下：这批原料最终随哪张发货单到了客户
        List<?> derived = (List<?>) rawTrace.get("derivedShipments");
        assertEquals(1, derived.size());
        assertEquals(shipmentNo, ((Map<?, ?>) derived.get(0)).get("shipmentNo"));
        assertNotNull(((Map<?, ?>) derived.get(0)).get("customerName"));

        // 成品批次：库存剩 2，用料批次两条
        Map<String, Object> fgTrace = traceService.traceBatch(workNo);
        assertEquals(2, ((List<?>) fgTrace.get("materials")).size());
        assertEquals(1, ((List<?>) fgTrace.get("stocks")).size());
        assertEquals(1, ((List<?>) fgTrace.get("shipments")).size());

        // 工单视角与发货单视角
        Map<String, Object> workTrace = traceService.traceWorkOrder(workNo);
        assertEquals(2, ((List<?>) workTrace.get("materials")).size());
        assertEquals(1, ((List<?>) workTrace.get("shipments")).size());

        Map<String, Object> shipTrace = traceService.traceShipment(shipmentNo);
        List<?> items = (List<?>) shipTrace.get("items");
        assertEquals(1, items.size());
        List<?> batches = (List<?>) ((Map<?, ?>) items.get(0)).get("batches");
        assertEquals(1, batches.size());
        assertEquals(workNo, ((Map<?, ?>) batches.get(0)).get("batchNo"));
        List<?> shipBatches = (List<?>) shipTrace.get("batches");
        assertEquals(2, ((List<?>) ((Map<?, ?>) shipBatches.get(0)).get("materials")).size());

        // 统一入口按前缀分派
        assertEquals("WORK_ORDER", traceService.query(workNo).get("kind"));
        assertEquals("SHIPMENT", traceService.query(shipmentNo).get("kind"));
        assertEquals("BATCH", traceService.query("RAW-B").get("kind"));
    }

    @Test
    @DisplayName("调拨保留批次号，不把批次洗掉")
    void transferKeepsBatchNumber() {
        stockRaw("RAW-A", "4");
        long otherWarehouseId = jdbc.queryForObject("""
                select id from warehouse where type = 'GENERAL' order by id limit 1
                """, Long.class);

        inventoryService.transfer(rawProductId, rawWarehouseId, otherWarehouseId, new BigDecimal("3"), "TFTEST");

        assertEquals(0, batchQty("RAW-A").compareTo(new BigDecimal("4")));
        Map<String, Object> row = jdbc.queryForMap("""
                select qty from inventory_batch
                where product_id = ? and warehouse_id = ? and batch_no = 'RAW-A'
                """, rawProductId, otherWarehouseId);
        assertEquals(0, new BigDecimal("3").compareTo(new BigDecimal(String.valueOf(row.get("qty")))));
        assertEquals(0, inventoryService.stock(rawProductId, rawWarehouseId).compareTo(BigDecimal.ONE));
    }

    @Test
    @DisplayName("批次缺失时自动补兜底批次，不阻塞领料")
    void reconcilesMissingBatchBeforeConsume() {
        stockRaw("RAW-A", "2");
        // 人为把汇总库存抬高，模拟「汇总有、批次没有」的历史数据
        jdbc.update("""
                update inventory set qty = qty + 5 where product_id = ? and warehouse_id = ?
                """, rawProductId, rawWarehouseId);

        inventoryService.issue(rawProductId, rawWarehouseId, new BigDecimal("4"), workNo);

        // 先把 RAW-A 扣完，差额从自动补的兜底批次里扣
        assertEquals(0, batchQty("RAW-A").compareTo(BigDecimal.ZERO));
        BigDecimal auto = jdbc.queryForObject("""
                select coalesce(sum(qty), 0) from inventory_batch
                where product_id = ? and warehouse_id = ? and batch_no like 'AUTO-%'
                """, BigDecimal.class, rawProductId, rawWarehouseId);
        assertEquals(0, auto.compareTo(new BigDecimal("3")));
        assertEquals(0, inventoryService.stock(rawProductId, rawWarehouseId).compareTo(new BigDecimal("3")));
    }
}
