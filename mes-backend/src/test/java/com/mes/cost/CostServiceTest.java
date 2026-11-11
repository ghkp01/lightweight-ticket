package com.mes.cost;

import com.mes.cost.api.WorkOrderCost;
import com.mes.inventory.InventoryService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成本核算：入库移动加权均价、出库按均价计价、工单料工费结算与重算。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CostServiceTest {

    @Autowired
    private CostService costService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
    }

    private long product(String code, String category, BigDecimal cost, BigDecimal price) {
        return jdbc.queryForObject("""
                insert into product (code, name, category, unit, cost, price)
                values (?, ?, ?, '件', ?, ?) returning id
                """, Long.class, code, "成本测试-" + code, category, cost, price);
    }

    private long rawWarehouse() {
        return jdbc.queryForObject(
                "select id from warehouse where type = 'RAW' and enabled = true order by id limit 1", Long.class);
    }

    private BigDecimal money(Object value) {
        return new BigDecimal(String.valueOf(value)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("入库按移动加权刷新均价，出库按均价计价记金额")
    void movingAverage() {
        long productId = product("CST" + suffix, "RAW", BigDecimal.ZERO, BigDecimal.ZERO);
        long warehouseId = rawWarehouse();

        inventoryService.inboundWithBatch(productId, warehouseId, new BigDecimal("10"), "INBOUND", "T1",
                "第一次入库", "B1", "INBOUND", null, new BigDecimal("12"));
        assertEquals(0, new BigDecimal("12").compareTo(
                jdbc.queryForObject("select avg_cost from product where id = ?", BigDecimal.class, productId)),
                "首批入库后均价应等于入库单价");

        // 第二批 18 元：均价 =（10×12 + 10×18）/ 20 = 15
        inventoryService.inboundWithBatch(productId, warehouseId, new BigDecimal("10"), "INBOUND", "T2",
                "第二次入库", "B2", "INBOUND", null, new BigDecimal("18"));
        assertEquals(0, new BigDecimal("15").compareTo(
                jdbc.queryForObject("select avg_cost from product where id = ?", BigDecimal.class, productId)));

        inventoryService.outbound(productId, warehouseId, new BigDecimal("5"), "OUTBOUND", "T3", "出库");
        Map<String, Object> txn = jdbc.queryForMap("""
                select unit_cost, amount, qty from stock_txn
                where product_id = ? and txn_type = 'OUT' order by id desc limit 1
                """, productId);
        assertEquals(0, new BigDecimal("15").compareTo(money(txn.get("unit_cost"))), "出库按移动平均成本计价");
        assertEquals(0, new BigDecimal("-75.00").compareTo(money(txn.get("amount"))), "出库金额为负");
    }

    @Test
    @DisplayName("不带单价的入库退回档案成本，不会把均价冲成 0")
    void inboundWithoutPriceFallsBackToProductCost() {
        long productId = product("CSF" + suffix, "RAW", new BigDecimal("8"), BigDecimal.ZERO);
        long warehouseId = rawWarehouse();
        inventoryService.inboundWithBatch(productId, warehouseId, new BigDecimal("10"), "INBOUND", "T9",
                "无单价入库", "BF", "INBOUND", null, null);
        assertEquals(0, new BigDecimal("8").compareTo(
                jdbc.queryForObject("select avg_cost from product where id = ?", BigDecimal.class, productId)));
    }

    @Test
    @DisplayName("工单成本按料 + 工 + 费结算，单位成本按合格数量摊")
    void workOrderCost() {
        long materialId = product("CMM" + suffix, "RAW", new BigDecimal("5"), BigDecimal.ZERO);
        long finishedId = product("CMF" + suffix, "FINISHED", BigDecimal.ZERO, new BigDecimal("100"));
        long warehouseId = rawWarehouse();
        inventoryService.inboundWithBatch(materialId, warehouseId, new BigDecimal("100"), "INBOUND", "M1",
                "原料入库", "MB1", "INBOUND", null, new BigDecimal("5"));

        String workNo = "WO" + suffix;
        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, qualified_qty, status)
                values (?, ?, 10, 10, 'IN_PROGRESS') returning id
                """, Long.class, workNo, finishedId);
        long operationId = jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, done_qty, unit_price)
                values (?, 1, '组装', 10, 10, 2) returning id
                """, Long.class, workOrderId);
        jdbc.update("""
                insert into work_report (work_order_id, operation_id, qty, qualified_qty, defect_qty, worker)
                values (?, ?, 10, 10, 0, '张三')
                """, workOrderId, operationId);

        // 领 20 件原料（单价 5）→ 料 100；退 2 件 → 料剩 90
        inventoryService.issue(materialId, warehouseId, new BigDecimal("20"), workNo);
        inventoryService.returnMaterial(materialId, warehouseId, new BigDecimal("2"), workNo);

        WorkOrderCost result = costService.recalc(workOrderId);
        assertEquals(0, new BigDecimal("90").compareTo(money(result.materialCost())), "退料要抵减材料成本");
        assertEquals(0, new BigDecimal("20").compareTo(money(result.laborCost())), "人工 = 报工量 × 工序单价");
        assertEquals(0, new BigDecimal("2").compareTo(money(result.overheadCost())), "制造费用 = 人工 × 10%");
        assertEquals(0, new BigDecimal("112").compareTo(money(result.totalCost())));
        assertEquals(0, new BigDecimal("11.2").compareTo(money(result.unitCost())));

        // 快照落到工单上，报表能直接读到
        Map<String, Object> row = jdbc.queryForMap("""
                select material_cost, labor_cost, overhead_cost, total_cost, unit_cost, costed_at
                from work_order where id = ?
                """, workOrderId);
        assertTrue(row.get("costed_at") != null, "结算时间要写入");
        assertEquals(0, new BigDecimal("112").compareTo(money(row.get("total_cost"))));

        Map<String, Object> detail = costService.detail(workOrderId);
        List<?> materials = (List<?>) detail.get("materials");
        List<?> labors = (List<?>) detail.get("labors");
        assertEquals(1, materials.size());
        assertEquals(1, labors.size());
    }

    @Test
    @DisplayName("成本报表按区间与关键字过滤，未结算工单可批量补算")
    void reportAndRecalcAll() {
        long finishedId = product("CRP" + suffix, "FINISHED", BigDecimal.ZERO, new BigDecimal("50"));
        String workNo = "WOC" + suffix;
        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, qualified_qty, status)
                values (?, ?, 5, 5, 'FINISHED') returning id
                """, Long.class, workNo, finishedId);
        long operationId = jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, done_qty, unit_price)
                values (?, 1, '包装', 5, 5, 4) returning id
                """, Long.class, workOrderId);
        jdbc.update("""
                insert into work_report (work_order_id, operation_id, qty, qualified_qty, defect_qty, worker)
                values (?, ?, 5, 5, 0, '李四')
                """, workOrderId, operationId);

        int count = costService.recalcAll(null);
        assertTrue(count > 0, "应至少补算一张工单");

        var page = costService.workOrders(workNo, "FINISHED", null, null, 1, 10);
        assertEquals(1, page.total());
        Map<String, Object> row = (Map<String, Object>) page.records().get(0);
        assertEquals(0, new BigDecimal("20").compareTo(money(row.get("laborCost"))));
        // 总成本 = 人工 20 + 制造费用 2 = 22，5 件摊下来 4.4
        assertEquals(0, new BigDecimal("4.4").compareTo(money(row.get("unitCost"))));

        Map<String, Object> summary = costService.summary(null, null);
        assertTrue(new BigDecimal(String.valueOf(summary.get("totalCost"))).signum() > 0);
        assertEquals(1, costService.products("CRP" + suffix).size(), "产品成本列表按编码能查到");
    }
}
