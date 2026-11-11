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
 * 设备点检与保养：点检任务幂等生成、点检结果与异常提醒、保养周期滚动、报修停机与关闭。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeviceMaintenanceTest {

    @Autowired
    private DeviceMaintenanceService service;

    @Autowired
    private JdbcTemplate jdbc;

    private long deviceId;
    private long itemId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = String.valueOf(System.nanoTime());
        long centerId = jdbc.queryForObject("""
                insert into work_center (code, name, type) values (?, ?, 'MACHINE') returning id
                """, Long.class, "WC" + tag, "点检测试中心");
        deviceId = jdbc.queryForObject("""
                insert into work_center_device (code, name, work_center_id, capacity_minutes, status)
                values (?, ?, ?, 480, 'RUNNING') returning id
                """, Long.class, "DEV" + tag, "点检测试设备", centerId);
        itemId = service.saveCheckItem(new DeviceMaintenanceService.CheckItemRequest(
                null, deviceId, "液压油位", "目视", "油位在刻度 1/2 以上", 1, true));
    }

    private long taskOf(LocalDate date) {
        return jdbc.queryForObject("""
                select id from device_check_task where device_id = ? and plan_date = ?
                """, Long.class, deviceId, java.sql.Date.valueOf(date));
    }

    @Test
    @DisplayName("点检项按设备与通用项合并，停用的不出现")
    void checkItemsMerged() {
        service.saveCheckItem(new DeviceMaintenanceService.CheckItemRequest(
                null, null, "安全防护罩", "目视", "齐全有效", 2, true));
        service.saveCheckItem(new DeviceMaintenanceService.CheckItemRequest(
                null, deviceId, "停用项", "目视", "-", 3, false));

        List<Map<String, Object>> items = service.checkItems(deviceId);
        assertEquals(2, items.size(), "设备项 + 通用项，停用项不算");
        assertTrue(items.stream().anyMatch(item -> "液压油位".equals(item.get("name"))));
        assertTrue(items.stream().anyMatch(item -> "安全防护罩".equals(item.get("name"))));

        BizException badDevice = assertThrows(BizException.class,
                () -> service.saveCheckItem(new DeviceMaintenanceService.CheckItemRequest(
                        null, 999999L, "不存在设备", "目视", null, 0, true)));
        assertEquals(1002, badDevice.getCode());
    }

    @Test
    @DisplayName("同一天重复生成点检任务不会重复建单")
    void generateIdempotent() {
        LocalDate today = LocalDate.now();
        service.generateTasks(today);
        long taskId = taskOf(today);
        service.generateTasks(today);
        Integer count = jdbc.queryForObject("""
                select count(*) from device_check_task where device_id = ? and plan_date = ?
                """, Integer.class, deviceId, java.sql.Date.valueOf(today));
        assertEquals(1, count, "设备 + 日期唯一");
        assertTrue(taskId > 0);
    }

    @Test
    @DisplayName("提交点检写入结果，异常项推预警且不能重复提交")
    void submitCheckWithNg() {
        LocalDate today = LocalDate.now();
        service.generateTasks(today);
        long taskId = taskOf(today);
        Map<String, Object> result = service.submitCheck(taskId,
                new DeviceMaintenanceService.SubmitCheckRequest(
                        List.of(new DeviceMaintenanceService.CheckResultLine(
                                itemId, "液压油位", "目视", "油位在刻度 1/2 以上", "NG", "油位偏低")),
                        "补油后复检"));
        assertEquals(1, ((Number) result.get("ng")).intValue());

        Map<String, Object> detail = service.checkDetail(taskId);
        assertEquals("DONE", detail.get("status"));
        assertEquals(1, ((List<?>) detail.get("results")).size());

        Integer notices = jdbc.queryForObject("""
                select count(*) from sys_notice where ref_type = 'DEVICE_CHECK' and ref_no = ?
                """, Integer.class, detail.get("taskNo"));
        assertTrue(notices != null && notices > 0, "异常点检应推预警");

        BizException repeat = assertThrows(BizException.class, () -> service.submitCheck(taskId,
                new DeviceMaintenanceService.SubmitCheckRequest(
                        List.of(new DeviceMaintenanceService.CheckResultLine(
                                itemId, "液压油位", "目视", null, "OK", null)), null)));
        assertEquals(4021, repeat.getCode());

        service.resetCheck(taskId);
        assertEquals("PENDING", service.checkDetail(taskId).get("status"), "撤销后回到待点检");
    }

    @Test
    @DisplayName("保养执行后下次日期按周期滚动，并留下保养记录")
    void executePlanRollsNextDate() {
        LocalDate today = LocalDate.now();
        long planId = service.savePlan(new DeviceMaintenanceService.PlanRequest(
                null, deviceId, "更换液压油", 30, today.minusDays(30), null, "32# 抗磨液压油", true));
        Map<String, Object> before = service.plans(false).stream()
                .filter(row -> ((Number) row.get("id")).longValue() == planId)
                .findFirst().orElseThrow();
        assertEquals(today.toString(), String.valueOf(before.get("nextDate")), "上次 + 30 天应正好是今天");

        Map<String, Object> executed = service.executePlan(planId,
                new DeviceMaintenanceService.ExecutePlanRequest(today, "张三", new BigDecimal("120"), "换油 20L"));
        assertEquals(today.plusDays(30).toString(), executed.get("nextDate"));

        List<Map<String, Object>> logs = service.planLogs(planId, null);
        assertEquals(1, logs.size());
        assertEquals(0, new BigDecimal("120").compareTo(new BigDecimal(String.valueOf(logs.get(0).get("cost")))));
        assertTrue(service.plans(true).stream().noneMatch(row -> ((Number) row.get("id")).longValue() == planId),
                "刚保养完的计划不在到期待办里");
    }

    @Test
    @DisplayName("报修把设备置停机，关闭时算停机时长并恢复待机")
    void faultLifecycle() {
        long faultId = service.openFault(new DeviceMaintenanceService.FaultRequest(deviceId, "主轴异响", "李四"));
        assertEquals("OFFLINE", jdbc.queryForObject(
                "select status from work_center_device where id = ?", String.class, deviceId));

        Map<String, Object> closed = service.closeFault(faultId, new DeviceMaintenanceService.CloseFaultRequest(
                "轴承磨损", "更换轴承", null, "王五"));
        assertEquals("CLOSED", closed.get("status"));
        assertTrue(((Number) closed.get("downtimeMinutes")).intValue() >= 0);
        assertEquals("IDLE", jdbc.queryForObject(
                "select status from work_center_device where id = ?", String.class, deviceId));

        BizException repeat = assertThrows(BizException.class,
                () -> service.closeFault(faultId, null));
        assertEquals(4021, repeat.getCode());

        BizException noSymptom = assertThrows(BizException.class,
                () -> service.openFault(new DeviceMaintenanceService.FaultRequest(deviceId, " ", null)));
        assertEquals(1002, noSymptom.getCode());
    }

    @Test
    @DisplayName("概览统计带今日任务、异常与未关闭故障")
    void summaryCounts() {
        LocalDate today = LocalDate.now();
        service.generateTasks(today);
        service.openFault(new DeviceMaintenanceService.FaultRequest(deviceId, "启动失败", null));
        Map<String, Object> summary = service.summary();
        assertTrue(((Number) summary.get("todayTasks")).intValue() >= 1);
        assertTrue(((Number) summary.get("openFaults")).intValue() >= 1);
        assertTrue(((Number) summary.get("devices")).intValue() >= 1);
    }

    @Test
    @DisplayName("每日维护任务会生成当天点检任务")
    void dailyUpkeepGenerates() {
        int affected = service.dailyUpkeep();
        assertTrue(affected >= 1, "至少生成一台设备的点检任务");
        Integer count = jdbc.queryForObject("""
                select count(*) from device_check_task where device_id = ? and plan_date = current_date
                """, Integer.class, deviceId);
        assertEquals(1, count);
    }
}
