package com.mes.planning;

import com.mes.purchase.RequisitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物料需求计算（MRP）测试：毛需求汇总、扣减库存/在途/已申请、安全库存加成、
 * 建议下单日与逾期标记、一键生成采购申请。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MrpTest {

    @Autowired
    private MrpService mrpService;

    @Autowired
    private MultiLevelMrpService multiLevelMrpService;

    @Autowired
    private RequisitionService requisitionService;

    @Autowired
    private JdbcTemplate jdbc;

    private long materialId;
    private long warehouseId;
    private long supplierId;
    private long workOrderId;
    private String workNo;
    private String materialCode;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        materialCode = "MP" + suffix;
        materialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price, safety_stock)
                values (?, ?, 'RAW', 'kg', 10, 0) returning id
                """, Long.class, materialCode, "MRP 测试料" + suffix);
        long productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 100) returning id
                """, Long.class, "MF" + suffix, "MRP 成品" + suffix);
        warehouseId = jdbc.queryForObject("""
                insert into warehouse (code, name, type) values (?, ?, 'RAW') returning id
                """, Long.class, "MW" + suffix, "MRP 仓" + suffix);
        supplierId = jdbc.queryForObject("""
                insert into supplier (code, name, category, lead_days)
                values (?, ?, 'MATERIAL', 5) returning id
                """, Long.class, "MS" + suffix, "MRP 供应商" + suffix);

        workNo = "MWO" + suffix;
        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status, plan_start)
                values (?, ?, 10, 'RELEASED', current_date + 5) returning id
                """, Long.class, workNo, productId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty, source)
                values (?, ?, 100, 30, 'STANDARD')
                """, workOrderId, materialId);

        setStock(new BigDecimal("40"));
    }

    private void setStock(BigDecimal qty) {
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, ?)
                on conflict (product_id, warehouse_id) do update set qty = excluded.qty
                """, materialId, warehouseId, qty);
    }

    private Map<String, Object> runOne(boolean includeScheduled, boolean onlyShortage) {
        Map<String, Object> result = mrpService.run(
                new MrpService.MrpQuery(includeScheduled, onlyShortage, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        return rows.stream()
                .filter(row -> materialId == ((Number) row.get("productId")).longValue())
                .findFirst().orElse(null);
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    @Test
    @DisplayName("毛需求取 BOM 待领量，缺口扣掉库存可用")
    void computesGapFromDemandAndStock() {
        Map<String, Object> row = runOne(false, true);
        assertNotNull(row, "应有该物料的缺料行");
        assertEquals(0, new BigDecimal("70").compareTo(decimal(row.get("grossNeed"))));
        assertEquals(0, new BigDecimal("40").compareTo(decimal(row.get("available"))));
        assertEquals(0, new BigDecimal("30").compareTo(decimal(row.get("gapQty"))));
        assertEquals(1, ((Number) row.get("workOrderCount")).intValue());
        assertTrue(String.valueOf(row.get("workOrders")).contains(workNo));
    }

    @Test
    @DisplayName("安全库存计入缺口")
    void addsSafetyStock() {
        jdbc.update("update product set safety_stock = 50 where id = ?", materialId);
        Map<String, Object> row = runOne(false, true);
        assertEquals(0, new BigDecimal("80").compareTo(decimal(row.get("gapQty"))));
    }

    @Test
    @DisplayName("采购在途与已申请未转单都抵扣缺口")
    void deductsOnWayAndPendingRequisition() {
        long orderId = jdbc.queryForObject("""
                insert into purchase_order (order_no, supplier_id, warehouse_id, status)
                values (?, ?, ?, 'CONFIRMED') returning id
                """, Long.class, "MPO" + System.nanoTime(), supplierId, warehouseId);
        jdbc.update("""
                insert into purchase_order_item (order_id, product_id, qty, received_qty)
                values (?, ?, 20, 0)
                """, orderId, materialId);

        Map<String, Object> withOnWay = runOne(false, true);
        assertEquals(0, new BigDecimal("20").compareTo(decimal(withOnWay.get("onWay"))));
        assertEquals(0, new BigDecimal("10").compareTo(decimal(withOnWay.get("gapQty"))));

        long reqId = jdbc.queryForObject("""
                insert into purchase_requisition (req_no, source_type, requester, status)
                values (?, 'PRODUCTION', 'mrp-test', 'SUBMITTED') returning id
                """, Long.class, "MPR" + System.nanoTime());
        jdbc.update("""
                insert into purchase_requisition_item (req_id, product_id, qty, converted_qty)
                values (?, ?, 10, 0)
                """, reqId, materialId);

        assertNullRow(runOne(false, true));
        Map<String, Object> full = runOne(false, false);
        assertNotNull(full);
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(full.get("gapQty"))));
        assertEquals(0, new BigDecimal("10").compareTo(decimal(full.get("pendingRequisition"))));
    }

    private void assertNullRow(Map<String, Object> row) {
        assertTrue(row == null, "缺口已被在途覆盖时，只看缺料不应出现该行");
    }

    @Test
    @DisplayName("建议下单日 = 需求日 - 交期，过期标逾期")
    void suggestsOrderDate() {
        Map<String, Object> row = runOne(false, true);
        LocalDate needDate = LocalDate.now().plusDays(5);
        assertEquals(needDate.toString(), row.get("needDate"));
        // 没有价格库记录时按默认 7 天交期，下单日早于今天即为逾期
        assertEquals(needDate.minusDays(7).toString(), row.get("suggestOrderDate"));
        assertEquals(Boolean.TRUE, row.get("overdue"));
    }

    @Test
    @DisplayName("含已排产时把未下达工单的需求也算进来")
    void includesScheduledWorkOrders() {
        long scheduled = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status, plan_start)
                values (?, (select product_id from work_order where id = ?), 5, 'SCHEDULED', current_date + 3)
                returning id
                """, Long.class, "MWS" + System.nanoTime(), workOrderId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty, source)
                values (?, ?, 10, 0, 'STANDARD')
                """, scheduled, materialId);

        Map<String, Object> without = runOne(false, true);
        assertEquals(0, new BigDecimal("70").compareTo(decimal(without.get("grossNeed"))));

        Map<String, Object> with = runOne(true, true);
        assertEquals(0, new BigDecimal("80").compareTo(decimal(with.get("grossNeed"))));
        assertEquals(0, new BigDecimal("40").compareTo(decimal(with.get("gapQty"))));
    }

    @Test
    @DisplayName("一键生成采购申请：草稿、来源工单缺料、明细带建议供应商")
    void createsRequisitionFromSuggestion() {
        Map<String, Object> result = mrpService.createRequisition(new MrpService.CreateRequest(
                LocalDate.now().plusDays(3), "采购部", "MRP 测试",
                List.of(new MrpService.CreateLine(materialId, new BigDecimal("30"), supplierId,
                        new BigDecimal("9.5"), "需求日测试"))));

        String reqNo = String.valueOf(result.get("reqNo"));
        assertTrue(reqNo.startsWith("PR"), reqNo);
        Map<String, Object> doc = jdbc.queryForMap("""
                select source_type, status, department, purpose, total_amount from purchase_requisition
                where req_no = ?
                """, reqNo);
        assertEquals("PRODUCTION", doc.get("source_type"));
        assertEquals("DRAFT", doc.get("status"));
        assertEquals("采购部", doc.get("department"));
        assertEquals("MRP 补料", doc.get("purpose"));
        assertEquals(0, new BigDecimal("285.00").compareTo((BigDecimal) doc.get("total_amount")));

        Map<String, Object> item = jdbc.queryForMap("""
                select i.qty, i.target_price, i.suggest_supplier_id
                from purchase_requisition_item i
                join purchase_requisition r on r.id = i.req_id
                where r.req_no = ?
                """, reqNo);
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) item.get("qty")));
        assertEquals(0, new BigDecimal("9.5").compareTo((BigDecimal) item.get("target_price")));
        assertEquals(supplierId, ((Number) item.get("suggest_supplier_id")).longValue());

        // 草稿申请也算计划内供给：同一批料再跑一次 MRP 不会被重复申请
        long reqId = ((Number) result.get("reqId")).longValue();
        Map<String, Object> afterDraft = runOne(false, false);
        assertNotNull(afterDraft);
        assertEquals(0, new BigDecimal("30").compareTo(decimal(afterDraft.get("pendingRequisition"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(afterDraft.get("gapQty"))));
        assertNullRow(runOne(false, true));

        // 草稿取消后供给消失，缺口重新出现
        jdbc.update("update purchase_requisition set status = 'CANCELLED' where id = ?", reqId);
        Map<String, Object> afterCancel = runOne(false, true);
        assertNotNull(afterCancel);
        assertEquals(0, new BigDecimal("30").compareTo(decimal(afterCancel.get("gapQty"))));
    }

    @Test
    @DisplayName("算料任务只关被本轮 BOM 需求覆盖到的订单，没算到料的订单留着提醒")
    void closesMrpTaskOnlyForCoveredOrders() {
        String suffix = Long.toString(System.nanoTime());
        String coveredNo = "MSO" + suffix;
        long coveredOrderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, status)
                values (?, 1, current_date, 'IN_PRODUCTION') returning id
                """, Long.class, coveredNo);
        jdbc.update("update work_order set sales_order_id = ? where id = ?", coveredOrderId, workOrderId);
        openMrpTask(coveredOrderId, coveredNo);

        // 这张订单还没下达工单，本次 MRP 根本没算到它的料
        String untouchedNo = "MSO2" + suffix;
        long untouchedOrderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, status)
                values (?, 1, current_date, 'IN_PRODUCTION') returning id
                """, Long.class, untouchedNo);
        openMrpTask(untouchedOrderId, untouchedNo);

        mrpService.run(new MrpService.MrpQuery(false, false, null));

        assertEquals("DONE", jdbc.queryForObject(
                "select status from biz_task where ref_no = ?", String.class, coveredNo));
        assertEquals("OPEN", jdbc.queryForObject(
                "select status from biz_task where ref_no = ?", String.class, untouchedNo));
    }

    private void openMrpTask(long orderId, String orderNo) {
        jdbc.update("""
                insert into biz_task (task_type, title, ref_type, ref_id, ref_no, assignee_role)
                values ('MRP_RUN', ?, 'SALES_ORDER', ?, ?, 'PLANNER')
                """, "订单 " + orderNo + " 已转生产，请算料", orderId, orderNo);
    }

    @Test
    @DisplayName("多层 MRP 展开也算一次料，覆盖到的订单算料任务同步收口")
    void multiLevelExplodeClosesCoveredMrpTask() {
        String suffix = Long.toString(System.nanoTime());
        String coveredNo = "MSO-ML" + suffix;
        long coveredOrderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, status)
                values (?, 1, current_date, 'IN_PRODUCTION') returning id
                """, Long.class, coveredNo);
        jdbc.update("update work_order set sales_order_id = ? where id = ?", coveredOrderId, workOrderId);
        openMrpTask(coveredOrderId, coveredNo);

        String untouchedNo = "MSO-ML2" + suffix;
        long untouchedOrderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, status)
                values (?, 1, current_date, 'IN_PRODUCTION') returning id
                """, Long.class, untouchedNo);
        openMrpTask(untouchedOrderId, untouchedNo);

        multiLevelMrpService.explode(new MultiLevelMrpService.ExplodeQuery(false, null, 3));

        assertEquals("DONE", jdbc.queryForObject(
                "select status from biz_task where ref_no = ?", String.class, coveredNo));
        assertEquals("OPEN", jdbc.queryForObject(
                "select status from biz_task where ref_no = ?", String.class, untouchedNo));
    }

    @Test
    @DisplayName("缺口预警：已过建议下单日才推，同一天重跑不重复推")
    void alertsOverdueShortagesOncePerDay() {
        // 需求日提前到 2 天前，按 5 天交期倒推，建议下单日早就过了
        jdbc.update("update work_order set plan_start = current_date - 2 where id = ?", workOrderId);

        mrpService.alertOverdueShortages();
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from mrp_alert_log where product_code = ?", Integer.class, materialCode));

        // 同一天同一物料不重复推
        assertEquals(0, mrpService.alertOverdueShortages());
    }

    @Test
    @DisplayName("缺口预警：按供应商采购员细分到人，没指定采购员的回退给计划")
    void alertsOverdueShortagesToBuyer() {
        // 给供应商指定采购员，并在价格库里建立「物料→供应商」关系，
        // 这样 MRP 建议供应商就能落到该采购员头上
        jdbc.update("update supplier set buyer = ? where id = ?", "buyer01", supplierId);
        jdbc.update("""
                insert into supplier_price (product_id, supplier_id, price, valid_from)
                values (?, ?, 10, current_date)
                """, materialId, supplierId);
        // 需求日提前到 2 天前，建议下单日已过
        jdbc.update("update work_order set plan_start = current_date - 2 where id = ?", workOrderId);

        int pushed = mrpService.alertOverdueShortages();
        assertTrue(pushed >= 1);

        // 预警日志记到具体采购员，而不是计划角色
        String pushedTo = jdbc.queryForObject(
                "select pushed_to from mrp_alert_log where product_code = ?",
                String.class, materialCode);
        assertEquals("buyer01", pushedTo);

        // 站内信直接推给采购员本人
        Integer noticeCount = jdbc.queryForObject(
                "select count(*) from sys_notice where receiver = ? and category = 'ALERT'",
                Integer.class, "buyer01");
        assertEquals(1, noticeCount);
    }

    @Test
    @DisplayName("缺口预警：供应商未指定采购员时回退给计划角色")
    void alertsOverdueShortagesFallsBackToPlanner() {
        // 不设 buyer，价格库指定供应商
        jdbc.update("""
                insert into supplier_price (product_id, supplier_id, price, valid_from)
                values (?, ?, 10, current_date)
                """, materialId, supplierId);
        jdbc.update("update work_order set plan_start = current_date - 2 where id = ?", workOrderId);

        mrpService.alertOverdueShortages();

        assertEquals("PLANNER", jdbc.queryForObject(
                "select pushed_to from mrp_alert_log where product_code = ?",
                String.class, materialCode));
    }

    @Test
    @DisplayName("生成采购申请后派生转采购单任务，按批准行列转完后自动关闭")
    void convertsRequisitionClosesTask() {
        Map<String, Object> result = mrpService.createRequisition(new MrpService.CreateRequest(
                LocalDate.now().plusDays(3), "采购部", "MRP 测试",
                List.of(new MrpService.CreateLine(materialId, new BigDecimal("30"), supplierId,
                        new BigDecimal("9.5"), "需求日测试"))));
        long reqId = ((Number) result.get("reqId")).longValue();
        String reqNo = String.valueOf(result.get("reqNo"));

        Map<String, Object> task = jdbc.queryForMap("""
                select task_type, assignee_role, status, due_date from biz_task where ref_no = ?
                """, reqNo);
        assertEquals("REQ_CONVERT", task.get("task_type"));
        assertEquals("PLANNER", task.get("assignee_role"));
        assertEquals("OPEN", task.get("status"));
        assertEquals(LocalDate.now().plusDays(3).toString(), String.valueOf(task.get("due_date")));

        requisitionService.submit(reqId);
        requisitionService.approve(reqId);
        requisitionService.toOrder(reqId, new RequisitionService.ConvertRequest(
                supplierId, warehouseId, LocalDate.now().plusDays(3), false, null, List.of()));

        Map<String, Object> closed = jdbc.queryForMap("""
                select status, done_note from biz_task where ref_no = ? and task_type = 'REQ_CONVERT'
                """, reqNo);
        assertEquals("DONE", closed.get("status"));
        assertTrue(String.valueOf(closed.get("done_note")).contains("转完采购单"));
    }
}
