package com.mes.approval;

import com.mes.common.BizException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单轨化：BPMN 是唯一事实来源，节点清单从 BPMN 解析，
 * 超时 / 转交 / 加签 / 抄送这些策略单独落策略表。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalNodePolicyTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private String u1;
    private String u2;
    private String code;

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
                """, username, "策略测试" + prefix);
        return username;
    }

    private ApprovalService.NodeRequest approver(String key, String name, List<String> users,
                                                 Integer timeoutHours, Boolean allowTransfer) {
        return new ApprovalService.NodeRequest(null, name, "USER", users, "ANY",
                null, null, null, List.of(), null, "APPROVER", null, null, 1, "AUTO_PASS",
                true, allowTransfer, true, timeoutHours, null, key);
    }

    private ApprovalService.NodeRequest cc(String key, String name, List<String> receivers) {
        return new ApprovalService.NodeRequest(null, name, "USER", List.of(), "ANY",
                null, null, null, receivers, null, "CC", null, null, 1, "AUTO_PASS",
                true, true, true, null, null, key);
    }

    private long saveFlow(String bizType, ApprovalService.NodeRequest... nodes) {
        login("admin", "ADMIN");
        code = "NP" + tag + bizType;
        return approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                code, "策略测试" + bizType, bizType, true, "单测用", List.of(nodes)));
    }

    private long submit(String bizType) {
        login(u1, "PLANNER");
        Long id = approvalService.submit(bizType, 1L, code, "策略单据 " + tag, Map.of());
        assertNotNull(id, "应生成审批实例");
        return id;
    }

    private String taskIdOf(String username, long instanceId) {
        login(username, "PLANNER");
        return approvalService.myTasks().stream()
                .filter(row -> row.get("instanceId") != null
                        && ((Number) row.get("instanceId")).longValue() == instanceId)
                .map(row -> String.valueOf(row.get("id")))
                .findFirst().orElse(null);
    }

    @BeforeEach
    void setUp() {
        tag = Long.toString(System.nanoTime());
        u1 = user("pa");
        u2 = user("pb");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("节点清单从 BPMN 解析：审批节点与抄送节点都能列出来")
    void nodesComeFromBpmn() {
        saveFlow("N1", approver("n1", "计划审核", List.of(u2), null, true),
                cc("n2", "抄送计划", List.of(u2)),
                approver("n3", "厂长复核", List.of(u2), null, true));

        Map<String, Object> flow = approvalService.flows().stream()
                .filter(row -> code.equals(row.get("code")))
                .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) flow.get("nodes");
        assertEquals(3, nodes.size(), "应从 BPMN 读出 3 个节点：" + nodes);
        assertEquals(3, ((Number) flow.get("nodeCount")).intValue());
        assertTrue(nodes.stream().anyMatch(n -> "n1".equals(n.get("itemKey")) && "APPROVER".equals(n.get("nodeKind"))),
                nodes.toString());
        assertTrue(nodes.stream().anyMatch(n -> "n2".equals(n.get("itemKey")) && "CC".equals(n.get("nodeKind"))),
                nodes.toString());
    }

    @Test
    @DisplayName("节点树保存时策略落表：超时小时数与抄送人都能读回")
    void policySavedFromNodeTree() {
        long flowId = saveFlow("N2",
                approver("n1", "计划审核", List.of(u2), 6, true),
                cc("n2", "抄送计划", List.of(u2)));

        List<Map<String, Object>> policies = approvalService.nodePolicies(flowId);
        Map<String, Object> plan = policies.stream()
                .filter(row -> "n1".equals(row.get("nodeKey")))
                .findFirst().orElseThrow();
        assertEquals(6, ((Number) plan.get("timeoutHours")).intValue());
        assertTrue(Boolean.TRUE.equals(plan.get("allowTransfer")), "没配就按允许");

        Map<String, Object> ccNode = policies.stream()
                .filter(row -> "n2".equals(row.get("nodeKey")))
                .findFirst().orElseThrow();
        assertEquals(u2, ccNode.get("ccUsers"), "抄送人应落在策略表里");
    }

    @Test
    @DisplayName("策略按 nodeKey 覆盖保存，画布没保存过的节点会被拒")
    void savePoliciesRejectsUnknownNode() {
        long flowId = saveFlow("N3", approver("n1", "计划审核", List.of(u2), null, true));

        int saved = approvalService.saveNodePolicies(flowId, List.of(
                new ApprovalService.NodePolicyRequest("n1", 12, "REMIND", false, false, u1 + "," + u2)));
        assertEquals(1, saved);

        Map<String, Object> row = approvalService.nodePolicies(flowId).stream()
                .filter(item -> "n1".equals(item.get("nodeKey")))
                .findFirst().orElseThrow();
        assertEquals(12, ((Number) row.get("timeoutHours")).intValue());
        assertEquals("REMIND", row.get("timeoutAction"));
        assertEquals(Boolean.FALSE, row.get("allowTransfer"));
        assertEquals(Boolean.FALSE, row.get("allowAddSign"));
        assertEquals(u1 + "," + u2, row.get("ccUsers"));

        assertThrows(BizException.class, () -> approvalService.saveNodePolicies(flowId,
                List.of(new ApprovalService.NodePolicyRequest("ghost", 1, null, null, null, null))));
    }

    @Test
    @DisplayName("策略生效：节点不允许转交时，转交被拒")
    void transferBlockedByPolicy() {
        saveFlow("N4", approver("n1", "计划审核", List.of(u2), null, false));
        long instanceId = submit("N4");
        String taskId = taskIdOf(u2, instanceId);
        assertNotNull(taskId, "应生成待办");

        login(u2, "PLANNER");
        assertThrows(BizException.class,
                () -> approvalService.transferTask(taskId, u1, "帮我处理"),
                "节点配了不允许转交，应被拒绝");
    }

    @Test
    @DisplayName("清空策略后恢复默认：可以转交")
    void clearedPolicyRestoresDefault() {
        long flowId = saveFlow("N5", approver("n1", "计划审核", List.of(u2), null, false));
        long instanceId = submit("N5");
        String taskId = taskIdOf(u2, instanceId);

        login("admin", "ADMIN");
        approvalService.saveNodePolicies(flowId, List.of());

        login(u2, "PLANNER");
        Map<String, Object> result = approvalService.transferTask(taskId, u1, "清掉策略后允许转交");
        assertNotNull(result, "清空策略后应恢复默认允许转交");
    }
}
