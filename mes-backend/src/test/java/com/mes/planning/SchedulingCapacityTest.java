package com.mes.planning;

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
 * 产能口径测试：外协中心不占厂内产能；维护设备后按可用设备折算容量。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchedulingCapacityTest {

    @Autowired
    private SchedulingService schedulingService;

    @Autowired
    private JdbcTemplate jdbc;

    private long createCenter(String suffix, String type, int capacity) {
        return jdbc.queryForObject("""
                insert into work_center (code, name, org_id, type, daily_capacity_minutes, enabled)
                values (?, ?, (select id from sys_org where type = 'WORKSHOP' order by id limit 1), ?, ?, true)
                returning id
                """, Long.class, "TWC" + suffix, "测试中心" + suffix, type, capacity);
    }

    private long createProcess(String suffix, long centerId) {
        return jdbc.queryForObject("""
                insert into process (code, name, category, need_inspection, inspection_mode,
                                     enabled, work_center_id, default_minutes, default_price)
                values (?, ?, 'MACHINING', false, 'NONE', true, ?, 2, 1)
                returning id
                """, Long.class, "TP" + suffix, "测试工序" + suffix, centerId);
    }

    private long createWorkOrderWithOperation(String suffix, long processId, int minutes) {
        long productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 10) returning id
                """, Long.class, "TPD" + suffix, "测试产品" + suffix);
        long workId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'DRAFT') returning id
                """, Long.class, "TWO" + suffix, productId);
        return jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty,
                                                  standard_minutes, unit_price, process_id)
                values (?, 1, ?, 10, ?, 1, ?) returning id
                """, Long.class, workId, "测试工序", BigDecimal.valueOf(minutes), processId);
    }

    private Map<String, Object> assignmentOf(Map<String, Object> result, long operationId) {
        List<Map<String, Object>> assignments = (List<Map<String, Object>>) result.get("assignments");
        return assignments.stream()
                .filter(line -> ((Number) line.get("operationId")).longValue() == operationId)
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("外协中心不占厂内产能，负荷单独标注")
    void outsourceCenterDoesNotConsumeCapacity() {
        String suffix = Long.toString(System.nanoTime());
        long centerId = createCenter(suffix, "OUTSOURCE", 480);
        long processId = createProcess(suffix, centerId);
        long operationId = createWorkOrderWithOperation(suffix, processId, 300);

        Map<String, Object> result = schedulingService.apsSchedule(
                new SchedulingService.ApsRequest(LocalDate.now(), 3));
        Map<String, Object> line = assignmentOf(result, operationId);
        assertNotNull(line, "外协工序应被排上");
        assertTrue(Boolean.TRUE.equals(line.get("outsource")), "外协工序应标记为外协");

        List<Map<String, Object>> dailyLoad = (List<Map<String, Object>>) result.get("dailyLoad");
        boolean hasOutsourceRow = dailyLoad.stream().anyMatch(row ->
                row.get("workCenterId") != null
                        && ((Number) row.get("workCenterId")).longValue() == centerId
                        && Boolean.TRUE.equals(row.get("outsource")));
        assertTrue(hasOutsourceRow, "外协负荷应单独成行");
        assertTrue(dailyLoad.stream().noneMatch(row ->
                        row.get("workCenterId") != null
                                && ((Number) row.get("workCenterId")).longValue() == centerId
                                && Boolean.FALSE.equals(row.get("outsource"))),
                "外协不应出现在厂内产能行里");
    }

    @Test
    @DisplayName("维护设备后按可用设备产能折算中心容量")
    void deviceCapacityNarrowsCenterCapacity() {
        String suffix = Long.toString(System.nanoTime());
        long centerId = createCenter(suffix, "MACHINE", 480);
        long processId = createProcess(suffix, centerId);
        long operationId = createWorkOrderWithOperation(suffix, processId, 100);
        jdbc.update("""
                insert into work_center_device (code, name, work_center_id, capacity_minutes, status)
                values (?, ?, ?, 120, 'RUNNING')
                """, "TD1" + suffix, "测试设备1", centerId);
        jdbc.update("""
                insert into work_center_device (code, name, work_center_id, capacity_minutes, status)
                values (?, ?, ?, 120, 'RUNNING')
                """, "TD2" + suffix, "测试设备2", centerId);

        Map<String, Object> result = schedulingService.apsSchedule(
                new SchedulingService.ApsRequest(LocalDate.now(), 3));
        Map<String, Object> line = assignmentOf(result, operationId);
        assertNotNull(line);
        assertEquals(240, ((Number) line.get("capacity")).intValue(), "两台可用设备合计 240 分钟");
        assertEquals(2, ((Number) line.get("deviceCount")).intValue());
    }

    @Test
    @DisplayName("设备维修或停用不计入可用产能")
    void maintenanceDeviceExcluded() {
        String suffix = Long.toString(System.nanoTime());
        long centerId = createCenter(suffix, "MACHINE", 480);
        long processId = createProcess(suffix, centerId);
        long operationId = createWorkOrderWithOperation(suffix, processId, 100);
        jdbc.update("""
                insert into work_center_device (code, name, work_center_id, capacity_minutes, status)
                values (?, ?, ?, 200, 'RUNNING')
                """, "TD3" + suffix, "测试设备3", centerId);
        jdbc.update("""
                insert into work_center_device (code, name, work_center_id, capacity_minutes, status)
                values (?, ?, ?, 200, 'MAINTENANCE')
                """, "TD4" + suffix, "测试设备4", centerId);

        Map<String, Object> result = schedulingService.apsSchedule(
                new SchedulingService.ApsRequest(LocalDate.now(), 3));
        Map<String, Object> line = assignmentOf(result, operationId);
        assertNotNull(line);
        assertEquals(200, ((Number) line.get("capacity")).intValue(), "维修设备不计入可用产能");
        assertEquals(1, ((Number) line.get("deviceCount")).intValue());
    }

    @Test
    @DisplayName("没有维护设备时沿用中心日产能")
    void fallbackToCenterCapacityWithoutDevices() {
        String suffix = Long.toString(System.nanoTime());
        long centerId = createCenter(suffix, "MACHINE", 360);
        long processId = createProcess(suffix, centerId);
        long operationId = createWorkOrderWithOperation(suffix, processId, 100);

        Map<String, Object> result = schedulingService.apsSchedule(
                new SchedulingService.ApsRequest(LocalDate.now(), 3));
        Map<String, Object> line = assignmentOf(result, operationId);
        assertNotNull(line);
        assertEquals(360, ((Number) line.get("capacity")).intValue());
        assertEquals(0, ((Number) line.get("deviceCount")).intValue());
    }
}
