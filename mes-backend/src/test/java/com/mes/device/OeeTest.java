package com.mes.device;

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
 * OEE：计划时间、停机扣减、标准工时达成、质量率与综合 OEE 的乘法关系。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OeeTest {

    @Autowired
    private OeeService service;

    @Autowired
    private JdbcTemplate jdbc;

    private long centerId;
    private long deviceId;
    private long processId;
    private long productId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = String.valueOf(System.nanoTime());
        centerId = jdbc.queryForObject("""
                insert into work_center (code, name, type, daily_capacity_minutes)
                values (?, ?, 'MACHINE', 480) returning id
                """, Long.class, "OC" + tag, "OEE测试中心");
        deviceId = jdbc.queryForObject("""
                insert into work_center_device (code, name, work_center_id, capacity_minutes, status)
                values (?, ?, ?, 480, 'RUNNING') returning id
                """, Long.class, "OD" + tag, "OEE测试设备", centerId);
        processId = jdbc.queryForObject("""
                insert into process (code, name, category, default_minutes, work_center_id)
                values (?, ?, 'MACHINING', 1, ?) returning id
                """, Long.class, "OP" + tag, "OEE测试工序" + tag, centerId);
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit) values (?, ?, 'FINISHED', '件') returning id
                """, Long.class, "OG" + tag, "OEE测试件");
    }

    /** 造一张工单 + 一道工序 + 若干报工，标准工时 = 报工量 × 1 分钟 */
    private void report(BigDecimal qty, BigDecimal qualified) {
        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, ?, 'IN_PROGRESS') returning id
                """, Long.class, "OW" + tag + System.nanoTime(), productId, qty);
        long operationId = jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, done_qty,
                                                  standard_minutes, unit_price, process_id)
                values (?, 1, '测试工序', ?, ?, ?, 1, ?) returning id
                """, Long.class, workOrderId, qty, qty, qty, processId);
        jdbc.update("""
                insert into work_report (work_order_id, operation_id, qty, qualified_qty, defect_qty, worker)
                values (?, ?, ?, ?, ?, 'OEE测试')
                """, workOrderId, operationId, qty, qualified, qty.subtract(qualified));
    }

    private Map<String, Object> rowOf(Map<String, Object> result) {
        List<Map<String, Object>> rows = castList(result.get("rows"));
        return rows.stream()
                .filter(row -> ((Number) row.get("workCenterId")).longValue() == centerId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("结果里没有测试工作中心"));
    }

    @Test
    @DisplayName("一天 480 分钟产能、无停机、标准工时 60 分、全部合格 → 可用率 100%")
    void oeeBasic() {
        report(new BigDecimal("60"), new BigDecimal("60"));
        // 指定单日区间，计划时间就是该中心的日产能
        Map<String, Object> result = service.calc(new OeeService.OeeQuery(centerId,
                String.valueOf(LocalDate.now()), String.valueOf(LocalDate.now())));
        Map<String, Object> row = rowOf(result);

        assertEquals(0, new BigDecimal("480.00").compareTo(new BigDecimal(String.valueOf(row.get("plannedMinutes")))));
        assertEquals(0, new BigDecimal("0").compareTo(new BigDecimal(String.valueOf(row.get("downtimeMinutes")))));
        assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(String.valueOf(row.get("availability")))));
        assertEquals(0, new BigDecimal("60.00").compareTo(new BigDecimal(String.valueOf(row.get("standardMinutes")))));
        assertTrue(new BigDecimal(String.valueOf(row.get("quality"))).compareTo(new BigDecimal("100")) == 0);
        assertTrue(new BigDecimal(String.valueOf(row.get("oee"))).signum() > 0, "OEE 应大于 0");
    }

    @Test
    @DisplayName("停机时间从计划时间里扣掉，可用率随之下降")
    void downtimeLowersAvailability() {
        jdbc.update("""
                insert into device_fault (fault_no, device_id, occurred_at, recovered_at, symptom, status,
                                          downtime_minutes)
                values (?, ?, now(), now() + interval '8 hours', '测试故障', 'CLOSED', 480)
                """, "OF" + tag, deviceId);

        Map<String, Object> result = service.calc(new OeeService.OeeQuery(centerId, null, null));
        Map<String, Object> row = rowOf(result);
        BigDecimal availability = new BigDecimal(String.valueOf(row.get("availability")));
        assertTrue(availability.compareTo(new BigDecimal("100")) < 0, "有停机时可用率应小于 100");
        assertTrue(new BigDecimal(String.valueOf(row.get("downtimeMinutes"))).signum() > 0);

        List<?> faults = (List<?>) result.get("faults");
        assertTrue(faults.size() >= 1, "停机明细应包含故障单");
    }

    @Test
    @DisplayName("质量率按报工合格比例算")
    void qualityRate() {
        report(new BigDecimal("10"), new BigDecimal("8"));
        Map<String, Object> row = rowOf(service.calc(new OeeService.OeeQuery(centerId, null, null)));
        assertEquals(0, new BigDecimal("80.00").compareTo(new BigDecimal(String.valueOf(row.get("quality")))));
    }

    @Test
    @DisplayName("综合 OEE = 可用率 × 性能 × 质量")
    void oeeFormula() {
        report(new BigDecimal("100"), new BigDecimal("90"));
        Map<String, Object> result = service.calc(new OeeService.OeeQuery(centerId, null, null));
        Map<String, Object> row = rowOf(result);
        BigDecimal availability = new BigDecimal(String.valueOf(row.get("availability")));
        BigDecimal performance = new BigDecimal(String.valueOf(row.get("performance")));
        BigDecimal quality = new BigDecimal(String.valueOf(row.get("quality")));
        BigDecimal oee = new BigDecimal(String.valueOf(row.get("oee")));
        BigDecimal expect = availability.multiply(performance).multiply(quality)
                .divide(new BigDecimal("10000"), 2, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expect.compareTo(oee), "OEE 应为三项乘积，实际 " + oee + " 期望 " + expect);

        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertTrue(((Number) summary.get("days")).intValue() >= 1);
        assertTrue(summary.get("plannedMinutes") != null);
    }

    @Test
    @DisplayName("非法日期与倒置区间被拒")
    void invalidDate() {
        BizException bad = assertThrows(BizException.class,
                () -> service.calc(new OeeService.OeeQuery(null, "2026-13-40", null)));
        assertEquals(1002, bad.getCode());
        BizException reversed = assertThrows(BizException.class,
                () -> service.calc(new OeeService.OeeQuery(null,
                        String.valueOf(LocalDate.now()), String.valueOf(LocalDate.now().minusDays(3)))));
        assertEquals(1002, reversed.getCode());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
