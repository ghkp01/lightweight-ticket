package com.mes.job;

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
 * 后台定时任务：任务清单、手工执行与留痕、交期预警当天去重、未知任务被拒。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobServiceTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
    }

    @Test
    @DisplayName("任务清单带说明与最近执行状态")
    void jobsListed() {
        List<Map<String, Object>> jobs = jobService.jobs();
        assertEquals(13, jobs.size());
        assertTrue(jobs.stream().anyMatch(job -> "DELIVERY_ALERT".equals(job.get("code"))));
        assertTrue(jobs.stream().anyMatch(job -> "DEVICE_UPKEEP".equals(job.get("code"))));
        assertTrue(jobs.stream().anyMatch(job -> "APPROVAL_TIMEOUT".equals(job.get("code"))));
        assertTrue(jobs.stream().anyMatch(job -> "DOMAIN_EVENT_CLEANUP".equals(job.get("code"))));
        assertTrue(jobs.stream().anyMatch(job -> "TASK_OVERDUE".equals(job.get("code"))));
        assertTrue(jobs.stream().anyMatch(job -> "QUOTE_BOM_REMINDER".equals(job.get("code"))));
        assertTrue(jobs.stream().anyMatch(job -> "MRP_ALERT".equals(job.get("code"))));
        assertTrue(jobs.stream().anyMatch(job -> "DAILY_REPORT".equals(job.get("code"))));
        assertTrue(jobs.stream().anyMatch(job -> "RETENTION_CLEANUP".equals(job.get("code"))));
        assertTrue(jobs.stream().allMatch(job -> job.get("name") != null && job.get("cron") != null));
    }

    @Test
    @DisplayName("手工执行写执行记录，未知任务被拒")
    void runWritesLog() {
        Map<String, Object> result = jobService.run("NOTICE_CLEANUP", "MANUAL");
        assertEquals("SUCCESS", result.get("status"));
        Map<String, Object> log = jdbc.queryForMap("""
                select job_code, trigger_type, status, operator from sys_job_log
                where job_code = 'NOTICE_CLEANUP' order by id desc limit 1
                """);
        assertEquals("MANUAL", log.get("trigger_type"));
        assertEquals("SUCCESS", log.get("status"));

        BizException ex = assertThrows(BizException.class, () -> jobService.run("NOT_EXISTS", "MANUAL"));
        assertTrue(ex.getMessage().contains("任务不存在"));
    }

    @Test
    @DisplayName("交期预警：临期未发完的订单推一条，同一天不重复推")
    void deliveryAlertDeduped() {
        long customerId = jdbc.queryForObject("select id from customer order by id limit 1", Long.class);
        String orderNo = "SOJT" + suffix;
        jdbc.update("""
                insert into sales_order (order_no, customer_id, order_date, delivery_date, status, total_qty)
                values (?, ?, ?, ?, 'CONFIRMED', 10)
                """, orderNo, customerId, LocalDate.now(), LocalDate.now().plusDays(1));

        Map<String, Object> first = jobService.run("DELIVERY_ALERT", "MANUAL");
        assertEquals("SUCCESS", first.get("status"));
        assertTrue(((Number) first.get("affected")).intValue() >= 1, "至少提醒一张临期订单");

        Integer notices = jdbc.queryForObject("""
                select count(*) from sys_notice where ref_type = 'SALES_ORDER' and ref_no = ?
                """, Integer.class, orderNo);
        assertTrue(notices != null && notices > 0, "销售与计划都要收到提醒");

        Map<String, Object> second = jobService.run("DELIVERY_ALERT", "MANUAL");
        assertEquals(0, ((Number) second.get("affected")).intValue(), "同一天不重复提醒");
    }

    @Test
    @DisplayName("成本补算只挑没有快照的工单")
    void costRefreshOnlyUncosted() {
        long productId = jdbc.queryForObject("select id from product order by id limit 1", Long.class);
        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, qualified_qty, status)
                values (?, ?, 1, 1, 'FINISHED') returning id
                """, Long.class, "WOJOB" + suffix, productId);

        jobService.run("COST_REFRESH", "MANUAL");
        BigDecimal costed = jdbc.queryForObject(
                "select total_cost from work_order where id = ?", BigDecimal.class, workOrderId);
        assertTrue(costed != null, "补算后应写入成本快照（无料工费时为 0 也算已结算）");
        Object costedAt = jdbc.queryForObject(
                "select costed_at from work_order where id = ?", Object.class, workOrderId);
        assertTrue(costedAt != null, "补算后应写入结算时间");
    }

    @Test
    @DisplayName("执行记录按任务过滤")
    void logsFiltered() {
        jobService.run("NOTICE_CLEANUP", "MANUAL");
        List<Map<String, Object>> logs = jobService.logs("NOTICE_CLEANUP", 10);
        assertTrue(logs.size() >= 1);
        assertTrue(logs.stream().allMatch(row -> "NOTICE_CLEANUP".equals(row.get("jobCode"))));
    }
}
