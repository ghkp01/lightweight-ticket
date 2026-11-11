package com.mes.approval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批主流程（Flowable BPMN 2.0）：提交、或签、并行分支、条件分支、驳回、撤回。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalFlowTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private String u1;
    private String u2;
    private String u3;

    private void login(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private String user(String prefix) {
        String username = prefix + tag;
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, 'x', ?, 'PLANNER', true)
                """, username, "审批测试" + prefix);
        return username;
    }

    private ApprovalService.NodeRequest node(String key, String name, String type, List<String> values,
                                            String mode) {
        return new ApprovalService.NodeRequest(null, name, type, values, mode,
                null, null, null, List.of(), null, "APPROVER", null, null, 1, "AUTO_PASS",
                true, true, true, null, null, key);
    }

    private ApprovalService.NodeRequest container(String key, String name, String kind,
                                                  String field, String op, String value) {
        return new ApprovalService.NodeRequest(null, name, "ROLE", List.of(), "ANY",
                field, op, value, List.of(), null, kind, null, null, 1, "AUTO_PASS",
                true, true, true, null, null, key);
    }

    private ApprovalService.NodeRequest child(String key, String name, String type, List<String> values,
                                              String mode, String parentKey, String branchKey) {
        return new ApprovalService.NodeRequest(null, name, type, values, mode,
                null, null, null, List.of(), null, "APPROVER", parentKey, branchKey, 1, "AUTO_PASS",
                true, true, true, null, null, key);
    }

    private void saveFlow(String bizType, ApprovalService.NodeRequest... nodes) {
        login("admin", "ADMIN");
        approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                "AF" + tag + bizType, "主流程测试" + bizType + tag, bizType, true, "单测用", List.of(nodes)));
    }

    private Long submit(String bizType, Map<String, Object> context) {
        login(u1, "PLANNER");
        Long instanceId = approvalService.submit(bizType, 1L, "AF" + tag, "审批单据 " + tag, context);
        assertNotNull(instanceId, "应生成审批实例");
        return instanceId;
    }

    private Map<String, Object> taskOf(String username, String role, long instanceId) {
        login(username, role);
        return approvalService.myTasks().stream()
                .filter(task -> task.get("instanceId") != null
                        && ((Number) task.get("instanceId")).longValue() == instanceId)
                .findFirst()
                .orElse(null);
    }

    private String processIdOf(long instanceId) {
        return jdbc.queryForObject("select process_id from sys_approval_instance where id = ?",
                String.class, instanceId);
    }

    @BeforeEach
    void setUp() {
        tag = Long.toString(System.nanoTime());
        u1 = user("a");
        u2 = user("b");
        u3 = user("c");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("提交生成待办，通过后实例结束并回写通过")
    void submitThenApprove() {
        saveFlow("T1", node("n1", "计划审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("T1", Map.of());

        assertEquals("PENDING", approvalService.statusOf(instanceId));
        Map<String, Object> task = taskOf(u2, "PLANNER", instanceId);
        assertNotNull(task, "应给指定人生成待办");

        login(u2, "PLANNER");
        Map<String, Object> result = approvalService.approve(String.valueOf(task.get("id")), "同意");
        assertEquals("APPROVED", result.get("status"));
        assertEquals("APPROVED", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("并行分支：两条支路都通过才结束")
    void parallelNeedsBothBranches() {
        saveFlow("T2",
                container("p1", "并行会审", "PARALLEL", null, null, null),
                child("n1", "计划确认", "USER", List.of(u2), "ANY", "p1", "B0"),
                child("n2", "仓库确认", "USER", List.of(u3), "ANY", "p1", "B1"));
        long instanceId = submit("T2", Map.of());

        Map<String, Object> plan = taskOf(u2, "PLANNER", instanceId);
        Map<String, Object> warehouse = taskOf(u3, "PLANNER", instanceId);
        assertNotNull(plan, "并行分支应同时生成计划待办");
        assertNotNull(warehouse, "并行分支应同时生成仓库待办");

        login(u2, "PLANNER");
        Map<String, Object> first = approvalService.approve(String.valueOf(plan.get("id")), "计划 ok");
        assertEquals("PENDING", first.get("status"), "只通过一条支路不应结束");
        assertEquals(1, ((Number) first.get("waiting")).intValue());

        login(u3, "PLANNER");
        Map<String, Object> second = approvalService.approve(String.valueOf(warehouse.get("id")), "仓库 ok");
        assertEquals("APPROVED", second.get("status"), "second=" + second + " detail=" + approvalService.detail(instanceId));
        assertEquals("APPROVED", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("条件命中时走 TRUE 支路，不命中直接跳过整条支路")
    void conditionBranch() {
        saveFlow("T3",
                container("c1", "数量分支", "CONDITION", "QTY", ">=", "100"),
                child("n1", "大额复核", "USER", List.of(u3), "ANY", "c1", "TRUE"));
        long hit = submit("T3", Map.of("qty", new BigDecimal("500")));
        assertNotNull(taskOf(u3, "PLANNER", hit), "数量达标应走大额复核");

        login(u3, "PLANNER");
        Map<String, Object> task = taskOf(u3, "PLANNER", hit);
        assertEquals("APPROVED",
                approvalService.approve(String.valueOf(task.get("id")), "ok").get("status"));

        login(u1, "PLANNER");
        long miss = approvalService.submit("T3", 2L, "AF" + tag + "-2", "小额单据", Map.of("qty", new BigDecimal("5")));
        assertEquals("APPROVED", approvalService.statusOf(miss), "条件不命中应直接走到结束");
    }

    @Test
    @DisplayName("驳回必须填原因，驳回后实例结束且状态为已驳回")
    void rejectRequiresReason() {
        saveFlow("T4", node("n1", "计划审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("T4", Map.of());
        Map<String, Object> task = taskOf(u2, "PLANNER", instanceId);

        login(u2, "PLANNER");
        assertThrows(com.mes.common.BizException.class,
                () -> approvalService.reject(String.valueOf(task.get("id")), "  "));

        Map<String, Object> result = approvalService.reject(String.valueOf(task.get("id")), "库存不足");
        assertEquals("REJECTED", result.get("status"));
        assertEquals("REJECTED", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("没人处理时可以撤回，撤回后实例结束")
    void cancelBeforeHandling() {
        saveFlow("T5", node("n1", "计划审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("T5", Map.of());

        login(u1, "PLANNER");
        approvalService.cancel(instanceId);
        assertEquals("CANCELLED", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("没有启用流程时 submit 返回 null，业务按原逻辑降级")
    void noFlowReturnsNull() {
        login(u1, "PLANNER");
        assertEquals(null, approvalService.submit("NOTHING", 1L, "X", "无流程", Map.of()));
    }

    @Test
    @DisplayName("流程详情按实例返回时间线")
    void detailShowsTimeline() {
        saveFlow("T7", node("n1", "计划审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("T7", Map.of());
        Map<String, Object> task = taskOf(u2, "PLANNER", instanceId);
        login(u2, "PLANNER");
        approvalService.approve(String.valueOf(task.get("id")), "同意");

        Map<String, Object> detail = approvalService.detail(instanceId);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
        assertTrue(tasks.size() >= 1);
        assertTrue(tasks.stream().anyMatch(row -> "计划审核".equals(row.get("nodeName"))));
        assertEquals("APPROVED", detail.get("status"));
    }
}
