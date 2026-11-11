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
 * Flowable 引擎侧的能力：多实例会签、抄送节点、自动通过节点。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalAdvancedTest {

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
                """, username, "会签测试" + prefix);
        return username;
    }

    private ApprovalService.NodeRequest node(String key, String kind, String name, String type,
                                             List<String> values, String mode, List<String> ccUsers) {
        return new ApprovalService.NodeRequest(null, name, type, values, mode,
                null, null, null, ccUsers, null, kind, null, null, 1, "AUTO_PASS",
                true, true, true, null, null, key);
    }

    private void saveFlow(String bizType, ApprovalService.NodeRequest... nodes) {
        login("admin", "ADMIN");
        approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                "AA" + tag + bizType, "会签测试" + bizType + tag, bizType, true, "单测用", List.of(nodes)));
    }

    private long submit(String bizType, String applicant) {
        login(applicant, "PLANNER");
        Long instanceId = approvalService.submit(bizType, 1L, "AA" + tag, "会签单据 " + tag, Map.of());
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
    @DisplayName("会签：多实例任务，两人都通过才流转")
    void allSignNeedsEveryone() {
        saveFlow("A1", node("n1", "APPROVER", "会签节点", "USER", List.of(u2, u3), "ALL", List.of()));
        long instanceId = submit("A1", u1);

        List<Map<String, Object>> first = tasksOf(u2, instanceId);
        List<Map<String, Object>> second = tasksOf(u3, instanceId);
        assertEquals(1, first.size(), "会签应为每人各生成一条待办");
        assertEquals(1, second.size());

        login(u2, "PLANNER");
        Map<String, Object> partial = approvalService.approve(String.valueOf(first.get(0).get("id")), "我同意");
        assertEquals("PENDING", partial.get("status"), "只有一人通过不应结束");
        assertEquals("PENDING", approvalService.statusOf(instanceId));

        login(u3, "PLANNER");
        Map<String, Object> done = approvalService.approve(String.valueOf(second.get(0).get("id")), "我也同意");
        assertEquals("APPROVED", done.get("status"));
        assertEquals("APPROVED", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("抄送节点：走到就通知，不停留也不产生待办")
    void ccNodeNotifiesWithoutStopping() {
        saveFlow("A2",
                node("n1", "CC", "抄送计划部", "ROLE", List.of("PLANNER"), "ANY", List.of(u3)),
                node("n2", "APPROVER", "一级审批", "USER", List.of(u2), "ANY", List.of()));
        long instanceId = submit("A2", u1);

        assertEquals(1, tasksOf(u2, instanceId).size(), "抄送之后应直接到审批节点");
        Integer notices = jdbc.queryForObject("""
                select count(*) from sys_notice where receiver = ? and ref_type = 'APPROVAL'
                """, Integer.class, u3);
        assertTrue(notices != null && notices > 0, "抄送人应收到通知");
        Integer logs = jdbc.queryForObject("""
                select count(*) from sys_approval_log where instance_id = ? and action = 'CC'
                """, Integer.class,
                jdbc.queryForObject("select process_id from sys_approval_instance where id = ?",
                        String.class, instanceId));
        assertTrue(logs != null && logs > 0, "抄送要留痕");
    }

    @Test
    @DisplayName("自动通过节点：留痕后继续往下走")
    void autoNodeContinues() {
        saveFlow("A3",
                node("n1", "AUTO", "系统留痕", "ROLE", List.of(), "ANY", List.of()),
                node("n2", "APPROVER", "一级审批", "USER", List.of(u2), "ANY", List.of()));
        long instanceId = submit("A3", u1);

        assertEquals(1, tasksOf(u2, instanceId).size());
        String pid = jdbc.queryForObject("select process_id from sys_approval_instance where id = ?",
                String.class, instanceId);
        Integer logs = jdbc.queryForObject("""
                select count(*) from sys_approval_log where instance_id = ? and action = 'AUTO'
                """, Integer.class, pid);
        assertTrue(logs != null && logs > 0, "自动通过要留痕");
    }

    @Test
    @DisplayName("改了流程之后，在途实例仍按旧版本走（Flowable 流程版本并存）")
    void runningInstanceKeepsOldVersion() {
        saveFlow("A4", node("n1", "APPROVER", "一级审批", "USER", List.of(u2), "ANY", List.of()));
        long oldInstance = submit("A4", u1);
        assertEquals(1, tasksOf(u2, oldInstance).size(), "旧实例应派给老审批人");

        // 同一业务类型再存一次（流程编码不变），产生新版本
        login("admin", "ADMIN");
        Long flowId = jdbc.queryForObject("""
                select id from sys_approval_flow where biz_type = 'A4' and code = ?
                """, Long.class, "AA" + tag + "A4");
        approvalService.saveFlow(flowId, new ApprovalService.FlowRequest(
                "AA" + tag + "A4", "会签测试A4" + tag, "A4", true, "改版",
                List.of(node("n1", "APPROVER", "一级审批", "USER", List.of(u3), "ANY", List.of()))));
        long newInstance = submit("A4", u1);

        assertEquals(1, tasksOf(u2, oldInstance).size(), "老实例不受版本升级影响");
        assertEquals(0, tasksOf(u2, newInstance).size());
        assertEquals(1, tasksOf(u3, newInstance).size(), "新实例应派给新审批人");
    }
}
