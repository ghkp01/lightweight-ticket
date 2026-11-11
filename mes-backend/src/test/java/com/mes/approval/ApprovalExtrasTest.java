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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回退到指定节点、灰度发布、表单字段级权限、手写签名。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalExtrasTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private String u1;
    private String u2;

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
                """, username, "扩展测试" + prefix);
        return username;
    }

    private ApprovalService.NodeRequest node(String key, String name, String type, List<String> values) {
        return new ApprovalService.NodeRequest(null, name, type, values, "ANY",
                null, null, null, List.of(), null, "APPROVER", null, null, 1, "AUTO_PASS",
                true, true, true, null, null, key);
    }

    private long saveFlow(String bizType, ApprovalService.NodeRequest... nodes) {
        login("admin", "ADMIN");
        return approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                "EX" + tag + bizType, "扩展测试" + bizType, bizType, true, "单测用", List.of(nodes)));
    }

    private long submit(String bizType, String applicant) {
        login(applicant, "PLANNER");
        Long id = approvalService.submit(bizType, 1L, "EX" + tag, "扩展单据 " + tag, Map.of());
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
        u1 = user("a");
        u2 = user("b");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("回退：从第二级退回首级，首级重新生成待办")
    void rollbackToPreviousNode() {
        saveFlow("X1", node("n1", "一级审批", "USER", List.of(u2)), node("n2", "二级审批", "USER", List.of(u2)));
        long instanceId = submit("X1", u1);
        String first = taskIdOf(u2, instanceId);

        login(u2, "PLANNER");
        approvalService.approve(first, "一级同意");
        String second = taskIdOf(u2, instanceId);
        assertNotNull(second, "应到二级");

        List<Map<String, Object>> targets = approvalService.rollbackTargets(second);
        assertTrue(targets.stream().anyMatch(row -> "n1".equals(row.get("nodeKey"))), targets.toString());

        Map<String, Object> result = approvalService.rollback(second, "n1", "金额写错了");
        assertEquals("PENDING", result.get("status"));
        assertNotNull(taskIdOf(u2, instanceId), "退回首级后应重新有待办");
        assertEquals("PENDING", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("回退：只能退到走过的节点")
    void rollbackRejectsUnknownTarget() {
        saveFlow("X2", node("n1", "一级审批", "USER", List.of(u2)));
        long instanceId = submit("X2", u1);
        String taskId = taskIdOf(u2, instanceId);
        login(u2, "PLANNER");
        assertThrows(BizException.class, () -> approvalService.rollback(taskId, "not-exist", null));
    }

    @Test
    @DisplayName("灰度：按比例走指定版本，关掉后回到稳定版本")
    void canaryRouting() {
        long flowId = saveFlow("X3", node("n1", "一级审批", "USER", List.of(u2)));
        login("admin", "ADMIN");
        approvalService.saveFlow(flowId, new ApprovalService.FlowRequest(
                "EX" + tag + "X3", "扩展测试X3", "X3", true, "第二版",
                List.of(node("n1", "一级审批", "USER", List.of(u2)))));

        approvalService.configureCanary(flowId, 100, 1);
        long canaryInstance = submit("X3", u1);
        assertEquals(1, definitionVersionOf(canaryInstance), "100% 灰度应走 v1");

        login("admin", "ADMIN");
        approvalService.configureCanary(flowId, 0, null);
        long stableInstance = submit("X3", u1);
        assertEquals(2, definitionVersionOf(stableInstance), "关掉灰度应走稳定版本 v2");
    }

    private int definitionVersionOf(long instanceId) {
        String pid = jdbc.queryForObject("select process_id from sys_approval_instance where id = ?",
                String.class, instanceId);
        String definitionId = jdbc.queryForObject(
                "select proc_def_id_ from act_hi_procinst where proc_inst_id_ = ?", String.class, pid);
        Integer version = jdbc.queryForObject(
                "select version_ from act_re_procdef where id_ = ?", Integer.class, definitionId);
        return version == null ? 0 : version;
    }

    @Test
    @DisplayName("字段权限：隐藏字段不返回，没 EDIT 权限不能改")
    void fieldRules() {
        long flowId = seededTransferFlowId();
        approvalService.saveFieldRules(flowId, List.of(
                Map.of("nodeKey", "Task_Plan", "fieldKey", "remark", "access", "EDIT"),
                Map.of("nodeKey", "Task_Plan", "fieldKey", "plannedDate", "access", "HIDDEN"),
                Map.of("nodeKey", "Task_Plan", "fieldKey", "totalQty", "access", "VIEW")));

        long transferId = newTransfer();
        login(u1, "PLANNER");
        Long instanceId = approvalService.submit("TRANSFER", transferId, "EX" + tag,
                "字段权限单据", Map.of("qty", 1, "amount", 1));
        assertNotNull(instanceId);

        String taskId = taskIdOf("planner", instanceId);
        assertNotNull(taskId, "计划角色应有待办");
        Map<String, Object> form = approvalService.taskForm(taskId);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) form.get("fields");
        assertTrue(fields.stream().noneMatch(row -> "plannedDate".equals(row.get("key"))), "隐藏字段不该返回");
        Map<String, Object> remark = fields.stream()
                .filter(row -> "remark".equals(row.get("key"))).findFirst().orElseThrow();
        assertEquals("EDIT", remark.get("access"));
        assertFalse(Boolean.TRUE.equals(remark.get("readOnly")));

        Map<String, Object> saved = approvalService.saveTaskForm(taskId, Map.of("remark", "审批里改的备注"));
        assertTrue(((List<?>) saved.get("changed")).contains("备注"));
        assertThrows(BizException.class,
                () -> approvalService.saveTaskForm(taskId, Map.of("plannedDate", "2026-11-01")),
                "隐藏字段不能改");
    }

    @Test
    @DisplayName("手写签名：流程要求签名时没签名不能通过，签名后记录留档")
    void signatureRequired() {
        long flowId = saveFlow("X4", node("n1", "一级审批", "USER", List.of(u2)));
        login("admin", "ADMIN");
        approvalService.setSignatureRequired(flowId, true);

        long instanceId = submit("X4", u1);
        String taskId = taskIdOf(u2, instanceId);
        login(u2, "PLANNER");
        assertThrows(BizException.class, () -> approvalService.approve(taskId, "同意"));

        Map<String, Object> result = approvalService.approve(taskId, "同意",
                "data:image/png;base64,iVBORw0KGgo=");
        assertEquals("APPROVED", result.get("status"));
        List<Map<String, Object>> signatures =
                (List<Map<String, Object>>) approvalService.detail(instanceId).get("signatures");
        assertEquals(1, signatures.size());
        assertEquals(u2, signatures.get(0).get("approver"));
    }

    private long seededTransferFlowId() {
        return jdbc.queryForObject("""
                select id from sys_approval_flow where biz_type = 'TRANSFER' and enabled = true limit 1
                """, Long.class);
    }

    private long newTransfer() {
        Long from = jdbc.queryForObject("select id from warehouse order by id limit 1", Long.class);
        Long to = jdbc.queryForObject("select id from warehouse order by id desc limit 1", Long.class);
        Long productId = jdbc.queryForObject("select id from product order by id limit 1", Long.class);
        long id = jdbc.queryForObject("""
                insert into transfer_order (transfer_no, from_warehouse_id, to_warehouse_id, status, created_by)
                values (?, ?, ?, 'PENDING_APPROVAL', ?) returning id
                """, Long.class, "EX" + tag, from, to, u1);
        jdbc.update("insert into transfer_order_item (transfer_id, product_id, qty) values (?, ?, 1)",
                id, productId);
        return id;
    }
}
