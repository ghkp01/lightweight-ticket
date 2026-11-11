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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批人来源与节点策略：发起人自己、各级主管、审批人为空、超时提醒与超时自动通过。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalNodeKindTest {

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
                """, username, "来源测试" + prefix);
        return username;
    }

    private ApprovalService.NodeRequest node(String key, String name, String type, List<String> values,
                                             int levelUp, Integer timeoutHours, String timeoutAction,
                                             String emptyStrategy) {
        return new ApprovalService.NodeRequest(null, name, type, values, "ANY",
                null, null, null, List.of(), null, "APPROVER", null, null, levelUp, emptyStrategy,
                true, true, true, timeoutHours, timeoutAction, key);
    }

    private void saveFlow(String bizType, ApprovalService.NodeRequest... nodes) {
        login("admin", "ADMIN");
        approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                "AN" + tag + bizType, "来源测试" + bizType + tag, bizType, true, "单测用", List.of(nodes)));
    }

    private long submit(String bizType, String applicant) {
        login(applicant, "PLANNER");
        Long instanceId = approvalService.submit(bizType, 1L, "AN" + tag, "来源单据 " + tag, Map.of());
        assertNotNull(instanceId);
        return instanceId;
    }

    private List<Map<String, Object>> tasksOf(String username, long instanceId) {
        String pid = jdbc.queryForObject("select process_id from sys_approval_instance where id = ?",
                String.class, instanceId);
        login(username, "PLANNER");
        return approvalService.myTasks().stream()
                .filter(task -> task.get("instanceId") != null && ((Number) task.get("instanceId")).longValue() == instanceId)
                .toList();
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
    @DisplayName("发起人自己审批：待办直接派给申请人")
    void initiatorApprover() {
        saveFlow("C1", node("n1", "经办人确认", "INITIATOR", List.of(), 1, null, null, "AUTO_PASS"));
        long instanceId = submit("C1", u1);
        assertEquals(1, tasksOf(u1, instanceId).size());
    }

    @Test
    @DisplayName("发起人主管审批：按组织层级向上找负责人")
    void orgLeaderApprover() {
        long plantId = jdbc.queryForObject("""
                insert into sys_org (code, name, type, path, level, leader)
                values (?, ?, 'PLANT', '/', 1, ?) returning id
                """, Long.class, "P" + tag, "测试工厂" + tag, u2);
        long teamId = jdbc.queryForObject("""
                insert into sys_org (parent_id, code, name, type, path, level, leader)
                values (?, ?, ?, 'TEAM', ?, 2, ?) returning id
                """, Long.class, plantId, "T" + tag, "测试班组" + tag, "/" + plantId + "/", u3);
        jdbc.update("update sys_user set org_id = ? where username = ?", teamId, u1);

        saveFlow("C2", node("n1", "直接主管", "ORG_LEADER", List.of(), 1, null, null, "AUTO_PASS"));
        long level1 = submit("C2", u1);
        assertEquals(1, tasksOf(u3, level1).size(), "1 级应派给班组负责人");

        login("admin", "ADMIN");
        approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                "AN" + tag + "C3", "上级主管" + tag, "C3", true, "单测用",
                List.of(node("n1", "工厂负责人", "ORG_LEADER", List.of(), 2, null, null, "AUTO_PASS"))));
        long level2 = submit("C3", u1);
        assertEquals(1, tasksOf(u2, level2).size(), "2 级应派给工厂负责人");
    }

    @Test
    @DisplayName("审批人为空时按配置转管理员，而不是自动通过")
    void emptyApproverGoesToAdmin() {
        jdbc.update("update sys_user set enabled = false where role = 'QC'");
        saveFlow("C4", node("n1", "质检复核", "ROLE", List.of("QC"), 1, null, null, "ADMIN"));
        long instanceId = submit("C4", u1);

        assertEquals("PENDING", approvalService.statusOf(instanceId));
        assertEquals(1, tasksOf("admin", instanceId).size(), "找不到审批人时应转给管理员");
    }

    @Test
    @DisplayName("超时自动通过：超过配置小时数后系统代为通过")
    void timeoutAutoPass() {
        saveFlow("C5", node("n1", "限时审批", "USER", List.of(u2), 1, 1, "AUTO_PASS", "AUTO_PASS"));
        long instanceId = submit("C5", u1);
        String pid = jdbc.queryForObject("select process_id from sys_approval_instance where id = ?",
                String.class, instanceId);
        // 把 Flowable 里任务的创建时间往前挪，模拟已经超时
        jdbc.update("update act_ru_task set create_time_ = now() - interval '3 hours' where proc_inst_id_ = ?", pid);

        com.mes.approval.api.ApprovalPort.TimeoutScan result = approvalService.scanTimeouts();
        assertTrue(result.autoPassed() >= 1, result.toString());
        assertEquals("APPROVED", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("超时提醒：只发一次，流程仍等审批人处理")
    void timeoutRemind() {
        saveFlow("C6", node("n1", "限时审批", "USER", List.of(u2), 1, 1, "REMIND", "AUTO_PASS"));
        long instanceId = submit("C6", u1);
        String pid = jdbc.queryForObject("select process_id from sys_approval_instance where id = ?",
                String.class, instanceId);
        jdbc.update("update act_ru_task set create_time_ = now() - interval '3 hours' where proc_inst_id_ = ?", pid);

        com.mes.approval.api.ApprovalPort.TimeoutScan first = approvalService.scanTimeouts();
        assertTrue(first.reminded() >= 1, first.toString());
        assertEquals("PENDING", approvalService.statusOf(instanceId));

        com.mes.approval.api.ApprovalPort.TimeoutScan second = approvalService.scanTimeouts();
        assertEquals(0, second.reminded(), "同一节点不重复提醒");
    }
}
