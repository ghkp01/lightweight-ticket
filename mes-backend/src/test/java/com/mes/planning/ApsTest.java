package com.mes.planning;

import com.mes.common.BizException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * APS：甘特按中心分格、负荷率计算、插单模拟按工艺路线消耗产能、受影响工单与入参校验。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApsTest {

    @Autowired
    private ApsService service;

    @Autowired
    private JdbcTemplate jdbc;

    private long centerId;
    private long processId;
    private long productId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = String.valueOf(System.nanoTime());
        centerId = jdbc.queryForObject("""
                insert into work_center (code, name, type, daily_capacity_minutes)
                values (?, ?, 'MACHINE', 480) returning id
                """, Long.class, "AC" + tag, "甘特测试中心");
        processId = jdbc.queryForObject("""
                insert into process (code, name, category, default_minutes, work_center_id)
                values (?, ?, 'MACHINING', 1, ?) returning id
                """, Long.class, "AP" + tag, "甘特测试工序" + tag, centerId);
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit) values (?, ?, 'FINISHED', '件') returning id
                """, Long.class, "AG" + tag, "甘特测试件");
    }

    private long scheduledWorkOrder(String workNo, int minutes, BigDecimal qty) {
        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status, schedule_date, schedule_seq)
                values (?, ?, ?, 'SCHEDULED', current_date, 10) returning id
                """, Long.class, workNo, productId, qty);
        jdbc.update("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, standard_minutes,
                                                 unit_price, process_id, schedule_date)
                values (?, 1, '测试工序', ?, ?, 1, ?, current_date)
                """, workOrderId, qty, minutes, processId);
        return workOrderId;
    }

    @Test
    @DisplayName("甘特返回按中心分格的每天负荷与工序条")
    void ganttCells() {
        scheduledWorkOrder("GA" + tag, 240, new BigDecimal("10"));
        Map<String, Object> result = service.gantt(null, null);
        assertTrue(((List<?>) result.get("days")).size() >= 1);
        List<Map<String, Object>> centers = castList(result.get("centers"));
        Map<String, Object> center = centers.stream()
                .filter(row -> ((Number) row.get("centerId")).longValue() == centerId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("甘特里没有测试工作中心"));
        List<Map<String, Object>> cells = castList(center.get("cells"));
        Map<String, Object> first = cells.get(0);
        assertEquals(0, new BigDecimal("240").compareTo(new BigDecimal(String.valueOf(first.get("minutes")))));
        assertEquals(0, new BigDecimal("50").compareTo(new BigDecimal(String.valueOf(first.get("rate")))),
                "240 / 480 = 50%");
        assertTrue(((List<?>) first.get("bars")).size() >= 1, "当天应有工序条");
    }

    @Test
    @DisplayName("甘特区间校验：倒置与超长都被拒")
    void ganttValidation() {
        BizException reversed = assertThrows(BizException.class,
                () -> service.gantt("2026-12-31", "2026-01-01"));
        assertEquals(1002, reversed.getCode());
        BizException tooLong = assertThrows(BizException.class,
                () -> service.gantt(String.valueOf(LocalDate.now()),
                        String.valueOf(LocalDate.now().plusDays(90))));
        assertEquals(1002, tooLong.getCode());
    }

    @Test
    @DisplayName("插单模拟按工时消耗产能，逐道工序给出完成日")
    void simulateSteps() {
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 1.5, 2)
                """, productId, processId);
        Map<String, Object> result = service.simulate(
                new ApsService.SimulateRequest(productId, new BigDecimal("100"), null));
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(0, new BigDecimal("150").compareTo(new BigDecimal(String.valueOf(summary.get("totalMinutes")))),
                "100 件 × 1.5 分钟 = 150 分钟");
        List<?> steps = (List<?>) result.get("steps");
        assertEquals(1, steps.size());
        Map<?, ?> step = (Map<?, ?>) steps.get(0);
        assertEquals(String.valueOf(LocalDate.now()), String.valueOf(step.get("finishDate")));
        assertTrue(String.valueOf(summary.get("finishDate")).compareTo(String.valueOf(LocalDate.now())) >= 0);
    }

    @Test
    @DisplayName("交期晚于预计完成日时给出延误天数，并列受影响工单")
    void simulateDelayAndAffected() {
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 2.4, 2)
                """, productId, processId);
        scheduledWorkOrder("GB" + tag, 470, new BigDecimal("10"));

        Map<String, Object> result = service.simulate(new ApsService.SimulateRequest(
                productId, new BigDecimal("400"), LocalDate.now()));
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        // 400 × 2.4 = 960 分钟，今天只剩 10 分钟可用 → 至少排到明天
        assertTrue(String.valueOf(summary.get("finishDate")).compareTo(String.valueOf(LocalDate.now())) > 0,
                "产能不足时应排到后面的日期");
        assertTrue(((Number) summary.get("dueDelayDays")).intValue() >= 1);
        assertTrue(((Number) summary.get("affectedOrders")).intValue() >= 1, "应有受影响工单");
    }

    @Test
    @DisplayName("没有工艺路线或数量非法时模拟被拒")
    void simulateValidation() {
        BizException noRouting = assertThrows(BizException.class,
                () -> service.simulate(new ApsService.SimulateRequest(productId, BigDecimal.ONE, null)));
        assertEquals(2014, noRouting.getCode());

        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 1, 2)
                """, productId, processId);
        BizException badQty = assertThrows(BizException.class,
                () -> service.simulate(new ApsService.SimulateRequest(productId, BigDecimal.ZERO, null)));
        assertEquals(1002, badQty.getCode());

        BizException missingProduct = assertThrows(BizException.class,
                () -> service.simulate(new ApsService.SimulateRequest(999999L, BigDecimal.ONE, null)));
        assertEquals(1002, missingProduct.getCode());
    }

    @Test
    @DisplayName("只做了整单排产的单：甘特用一条灰条兜底，并给出未到工序级的数量")
    void orderLevelPlanFallsBackToGreyRow() {
        orderLevelWorkOrder("GO" + tag, 120, new BigDecimal("5"));

        Map<String, Object> result = service.gantt(null, null);
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(1, ((Number) summary.get("orderLevelCount")).intValue(),
                "一键排产过的单没细分到工序，要单独计数");

        List<Map<String, Object>> centers = castList(result.get("centers"));
        Map<String, Object> row = centers.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("orderLevel")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("甘特里没有整单排产行"));
        assertEquals("整单排产（未到工序级）", row.get("name"));
        Map<String, Object> cell = castList(row.get("cells")).get(0);
        assertEquals(0, new BigDecimal("120").compareTo(new BigDecimal(String.valueOf(cell.get("minutes")))),
                "整单工时也要计入当天分钟数");
        assertEquals(null, cell.get("rate"), "没落到工作中心，不算负荷率");
        List<Map<String, Object>> bars = castList(cell.get("bars"));
        assertEquals(1, bars.size());
        assertEquals(Boolean.TRUE, bars.get(0).get("orderLevel"));
        assertEquals("整单", bars.get(0).get("operationName"));
        assertEquals("GO" + tag, bars.get(0).get("workNo"));
        // 工作中心计数不把这条兜底行算进去
        assertEquals(centers.size() - 1, ((Number) summary.get("centers")).intValue());
    }

    @Test
    @DisplayName("细分到工序后：整单灰条消失，落到工作中心那一行")
    void orderLevelPlanDisappearsAfterOperationScheduled() {
        long workOrderId = orderLevelWorkOrder("GP" + tag, 120, new BigDecimal("5"));
        jdbc.update("update work_order_operation set schedule_date = current_date where work_order_id = ?",
                workOrderId);

        Map<String, Object> result = service.gantt(null, null);
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(0, ((Number) summary.get("orderLevelCount")).intValue());
        List<Map<String, Object>> centers = castList(result.get("centers"));
        assertTrue(centers.stream().noneMatch(item -> Boolean.TRUE.equals(item.get("orderLevel"))));

        Map<String, Object> center = centers.stream()
                .filter(item -> item.get("centerId") != null
                        && ((Number) item.get("centerId")).longValue() == centerId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("甘特里没有测试工作中心"));
        assertEquals(0, new BigDecimal("120").compareTo(
                new BigDecimal(String.valueOf(castList(center.get("cells")).get(0).get("minutes")))));
    }

    /** 只在工单级排过产、工序还没写排产日的单（一键排产 / 单张排产的结果） */
    private long orderLevelWorkOrder(String workNo, int minutes, BigDecimal qty) {
        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status, schedule_date, schedule_seq)
                values (?, ?, ?, 'SCHEDULED', current_date, 10) returning id
                """, Long.class, workNo, productId, qty);
        jdbc.update("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, standard_minutes,
                                                 unit_price, process_id)
                values (?, 1, '测试工序', ?, ?, 1, ?)
                """, workOrderId, qty, minutes, processId);
        return workOrderId;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    @DisplayName("拖动模拟：工单移到空闲日，可行且当天完成")
    void simulateMoveToFreeDay() {
        long workOrderId = scheduledWorkOrder("GM" + tag, 240, new BigDecimal("10"));

        Map<String, Object> result = service.simulateMove(
                new ApsService.SimulateMoveRequest(workOrderId, LocalDate.now(), null));
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(Boolean.TRUE, summary.get("feasible"), "240 分钟在 480 产能内应可行");
        assertEquals(String.valueOf(LocalDate.now()), String.valueOf(summary.get("newFinishDate")));
        assertEquals(0, ((Number) summary.get("conflictCount")).intValue());

        List<?> steps = (List<?>) result.get("steps");
        assertEquals(1, steps.size());
        assertEquals(String.valueOf(LocalDate.now()),
                String.valueOf(((Map<?, ?>) steps.get(0)).get("finishDate")));
    }

    @Test
    @DisplayName("拖动模拟：目标日已超负荷，产生冲突并顺延")
    void simulateMoveToBusyDayConflicts() {
        // 另一张工单占 500 分钟，超过 480 日产能，当天已超负荷
        scheduledWorkOrder("GN" + tag, 500, new BigDecimal("10"));
        long workOrderId = scheduledWorkOrder("GO" + tag, 240, new BigDecimal("10"));

        Map<String, Object> result = service.simulateMove(
                new ApsService.SimulateMoveRequest(workOrderId, LocalDate.now(), null));
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        // 当天已超负荷 → 有冲突，本工单顺延到后面
        assertTrue(((Number) summary.get("conflictCount")).intValue() >= 1);
        assertTrue(String.valueOf(summary.get("newFinishDate"))
                .compareTo(String.valueOf(LocalDate.now())) > 0);

        List<?> conflicts = (List<?>) result.get("conflicts");
        assertTrue(conflicts.size() >= 1);
    }

    @Test
    @DisplayName("拖动模拟：换工作中心后用新中心的产能算")
    void simulateMoveToDifferentCenter() {
        long otherCenter = jdbc.queryForObject("""
                insert into work_center (code, name, type, daily_capacity_minutes)
                values (?, ?, 'MACHINE', 960) returning id
                """, Long.class, "AO" + tag, "双班中心");
        long workOrderId = scheduledWorkOrder("GP" + tag, 240, new BigDecimal("10"));

        Map<String, Object> result = service.simulateMove(
                new ApsService.SimulateMoveRequest(workOrderId, LocalDate.now(), otherCenter));
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(Boolean.TRUE, summary.get("feasible"));
        List<?> steps = (List<?>) result.get("steps");
        assertEquals(otherCenter, ((Number) ((Map<?, ?>) steps.get(0)).get("centerId")).longValue());
    }

    @Test
    @DisplayName("拖动模拟：入参校验")
    void simulateMoveValidation() {
        long workOrderId = scheduledWorkOrder("GQ" + tag, 100, new BigDecimal("5"));
        assertThrows(BizException.class,
                () -> service.simulateMove(new ApsService.SimulateMoveRequest(null, LocalDate.now(), null)));
        assertThrows(BizException.class,
                () -> service.simulateMove(new ApsService.SimulateMoveRequest(workOrderId, null, null)));
        BizException missing = assertThrows(BizException.class,
                () -> service.simulateMove(new ApsService.SimulateMoveRequest(999999L, LocalDate.now(), null)));
        assertEquals(1002, missing.getCode());
    }
}
