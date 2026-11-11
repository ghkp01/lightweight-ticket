package com.mes.task;

import com.mes.task.api.TaskDraft;
import com.mes.task.api.TaskTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务任务测试：幂等开单、角色池与点名指派、开单即通知、按单据关闭、我的任务可见范围。
 *
 * <p>这些断言对应的是「全流程每个交接点都得有人负责」这条规则：
 * 单子开了没人认领、或者同一次交接重复开五条待办，都算实现有问题。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BizTaskTest {

    @Autowired
    private BizTaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    private String orderNo;

    @BeforeEach
    void setUp() {
        orderNo = "SO-TASK-" + System.nanoTime();
    }

    private TaskDraft draft() {
        return TaskDraft.of(TaskTypes.MRP_RUN, "订单 " + orderNo + " 请算料",
                "跑 MRP 并处理缺口", TaskTypes.REF_SALES_ORDER, 1L, orderNo, "PLANNER");
    }

    @Test
    @DisplayName("转生产开出的算料任务默认落在计划角色池，并通知到该角色所有人")
    void openCreatesRolePoolTask() {
        taskService.open(draft());

        Map<String, Object> row = jdbc.queryForMap(
                "select status, assignee_role, assignee_username, task_type from biz_task where ref_no = ?", orderNo);
        assertEquals("OPEN", row.get("status"));
        assertEquals("PLANNER", row.get("assignee_role"));
        assertNull(row.get("assignee_username"));
        assertEquals(TaskTypes.MRP_RUN, row.get("task_type"));

        Integer pushed = jdbc.queryForObject("""
                select count(*) from sys_notice where receiver = 'planner' and title = ?
                """, Integer.class, "订单 " + orderNo + " 请算料");
        assertNotNull(pushed);
        assertTrue(pushed > 0, "角色池任务要扇出到该角色的每个人");
    }

    @Test
    @DisplayName("同一张单据同一类任务只留一条未完成，重复触发不刷屏")
    void openIsIdempotent() {
        taskService.open(draft());
        taskService.open(draft());
        taskService.open(draft());

        Integer count = jdbc.queryForObject(
                "select count(*) from biz_task where ref_no = ?", Integer.class, orderNo);
        assertEquals(1, count);
    }

    @Test
    @DisplayName("改派到具体人后，任务归属换成这个人，并单独给他一条站内信")
    void assignMovesTaskToPerson() {
        taskService.open(draft());
        long id = jdbc.queryForObject(
                "select id from biz_task where ref_no = ?", Long.class, orderNo);

        taskService.assign(id, "planner");

        Map<String, Object> row = jdbc.queryForMap("""
                select assignee_username, assignee_name, assignee_user_id from biz_task where id = ?
                """, id);
        assertEquals("planner", row.get("assignee_username"));
        assertNotNull(row.get("assignee_name"));
        assertNotNull(row.get("assignee_user_id"));

        Integer assigned = jdbc.queryForObject("""
                select count(*) from sys_notice
                where receiver = 'planner' and title like '任务已指派给你%'
                """, Integer.class);
        assertNotNull(assigned);
        assertTrue(assigned > 0);
    }

    @Test
    @DisplayName("派工或领料做完后按单据关任务，不动别的单")
    void closeOnlyAffectsTargetDocument() {
        taskService.open(draft());
        String other = orderNo + "-B";
        taskService.open(TaskDraft.of(TaskTypes.MRP_RUN, "另一张单", null,
                TaskTypes.REF_SALES_ORDER, 2L, other, "PLANNER"));

        taskService.close(TaskTypes.MRP_RUN, orderNo, "planner", "已生成采购申请");

        Map<String, Object> closed = jdbc.queryForMap(
                "select status, done_note from biz_task where ref_no = ?", orderNo);
        assertEquals("DONE", closed.get("status"));
        assertEquals("已生成采购申请", closed.get("done_note"));

        String otherStatus = jdbc.queryForObject(
                "select status from biz_task where ref_no = ?", String.class, other);
        assertEquals("OPEN", otherStatus);
    }

    @Test
    @DisplayName("跑完一轮 MRP 整批关掉算料任务，已关闭的不会被重复计数")
    void closeAllClosesOpenOnesOnly() {
        taskService.open(draft());
        taskService.open(TaskDraft.of(TaskTypes.MRP_RUN, "另一张单", null,
                TaskTypes.REF_SALES_ORDER, 2L, orderNo + "-C", "PLANNER"));

        int first = taskService.closeAll(TaskTypes.MRP_RUN, "planner", "MRP 已生成采购申请 PR1");
        assertEquals(2, first);
        int second = taskService.closeAll(TaskTypes.MRP_RUN, "planner", "再跑一次");
        assertEquals(0, second);
    }

    @Test
    @DisplayName("按单据批量关闭只动点名的那几张，别的订单任务不受影响")
    void closeByRefOnlyTouchesGivenDocuments() {
        taskService.open(draft());
        taskService.open(TaskDraft.of(TaskTypes.MRP_RUN, "第二张", null,
                TaskTypes.REF_SALES_ORDER, 2L, orderNo + "-B", "PLANNER"));
        taskService.open(TaskDraft.of(TaskTypes.MRP_RUN, "第三张", null,
                TaskTypes.REF_SALES_ORDER, 3L, orderNo + "-C", "PLANNER"));

        int closed = taskService.closeByRef(TaskTypes.MRP_RUN, TaskTypes.REF_SALES_ORDER,
                List.of(1L, 2L), "planner", "已完成物料需求计算");

        assertEquals(2, closed);
        assertEquals("OPEN", jdbc.queryForObject(
                "select status from biz_task where ref_no = ?", String.class, orderNo + "-C"));
    }

    @Test
    @DisplayName("我的任务：角色池里的活同角色可见，别的角色看不见")
    void mineCoversRolePool() {
        taskService.open(draft());

        List<Map<String, Object>> mine = taskService.mine("planner", "OPEN", null);
        assertTrue(mine.stream().anyMatch(row -> orderNo.equals(row.get("refNo"))));

        List<Map<String, Object>> others = taskService.mine("sales", "OPEN", null);
        assertTrue(others.stream().noneMatch(row -> orderNo.equals(row.get("refNo"))));
    }

    @Test
    @DisplayName("过了要求完成日算逾期，逾期数进概况")
    void summaryCountsOverdue() {
        taskService.open(draft().dueDate(LocalDate.now().minusDays(2)));

        Map<String, Object> summary = taskService.summary("planner");
        assertTrue(((Number) summary.get("open")).longValue() > 0);
        assertTrue(((Number) summary.get("overdue")).longValue() > 0);
    }
}
