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
 * 审批人的动作：转交、加签（会签节点）、催办、节点开关。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalActionTest {

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
                """, username, "动作测试" + prefix);
        return username;
    }

    private ApprovalService.NodeRequest node(String key, String name, String type, List<String> values,
                                             String mode) {
        return new ApprovalService.NodeRequest(null, name, type, values, mode,
                null, null, null, List.of(), null, "APPROVER", null, null, 1, "AUTO_PASS",
                true, true, true, null, null, key);
    }

    private void saveFlow(String bizType, boolean allowTransfer, ApprovalService.NodeRequest... nodes) {
        login("admin", "ADMIN");
        List<ApprovalService.NodeRequest> list = new java.util.ArrayList<>();
        for (ApprovalService.NodeRequest item : nodes) {
            list.add(new ApprovalService.NodeRequest(item.seq(), item.name(), item.approverType(),
                    item.approverValues(), item.multiMode(), item.conditionField(), item.conditionOp(),
                    item.conditionValue(), item.ccUsers(), item.remark(), item.nodeKind(),
                    item.parentKey(), item.branchKey(), item.levelUp(), item.emptyStrategy(),
                    item.dedupeSameUser(), allowTransfer, item.allowAddSign(),
                    item.timeoutHours(), item.timeoutAction(), item.itemKey()));
        }
        approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                "AB" + tag + bizType, "动作测试" + bizType + tag, bizType, true, "单测用", list));
    }

    private long submit(String bizType) {
        login(u1, "PLANNER");
        Long instanceId = approvalService.submit(bizType, 1L, "AB" + tag, "动作单据 " + tag, Map.of());
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
    @DisplayName("转交：待办改派，原审批人看不到也不能处理")
    void transferTask() {
        saveFlow("B1", true, node("n1", "计划审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("B1");
        String taskId = String.valueOf(tasksOf(u2, instanceId).get(0).get("id"));

        login(u2, "PLANNER");
        approvalService.transferTask(taskId, u3, "我不在厂里");
        assertTrue(tasksOf(u2, instanceId).isEmpty(), "转交后原审批人不应再有待办");
        assertEquals(1, tasksOf(u3, instanceId).size());
        login(u2, "PLANNER");
        assertThrows(BizException.class, () -> approvalService.approve(taskId, "同意"));
    }

    @Test
    @DisplayName("转交给不存在的账号被拒")
    void transferToUnknownUser() {
        saveFlow("B2", true, node("n1", "计划审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("B2");
        String taskId = String.valueOf(tasksOf(u2, instanceId).get(0).get("id"));
        login(u2, "PLANNER");
        assertThrows(BizException.class,
                () -> approvalService.transferTask(taskId, "no-such-user" + tag, null));
    }

    @Test
    @DisplayName("节点关闭转交后不能再转交")
    void transferDisabled() {
        saveFlow("B3", false, node("n1", "计划审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("B3");
        String taskId = String.valueOf(tasksOf(u2, instanceId).get(0).get("id"));
        login(u2, "PLANNER");
        assertThrows(BizException.class, () -> approvalService.transferTask(taskId, u3, null));
    }

    @Test
    @DisplayName("加签：生成加签待办，原审批人通过前必须先等加签人")
    void addSignBlocksUntilExtraHandled() {
        saveFlow("B4", true, node("n1", "会签审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("B4");
        String taskId = String.valueOf(tasksOf(u2, instanceId).get(0).get("id"));

        login(u2, "PLANNER");
        Map<String, Object> added = approvalService.addSign(taskId, u3, "请老张一起看");
        assertEquals("PENDING", added.get("status"));

        Map<String, Object> extra = tasksOf(u3, instanceId).stream()
                .filter(row -> Boolean.TRUE.equals(row.get("extra")))
                .findFirst().orElse(null);
        assertNotNull(extra, "加签人应看到一条加签待办");

        login(u2, "PLANNER");
        BizException blocked = assertThrows(BizException.class,
                () -> approvalService.approve(taskId, "同意"));
        assertTrue(blocked.getMessage().contains("加签"), blocked.getMessage());

        login(u3, "PLANNER");
        approvalService.approveExtra(((Number) extra.get("id")).longValue(), "我看过了");

        login(u2, "PLANNER");
        assertEquals("APPROVED", approvalService.approve(taskId, "同意").get("status"));
        assertEquals("APPROVED", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("加签人不同意：整单驳回，并必须填原因")
    void addSignRejectEndsInstance() {
        saveFlow("B5", true, node("n1", "会签审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("B5");
        String taskId = String.valueOf(tasksOf(u2, instanceId).get(0).get("id"));

        login(u2, "PLANNER");
        approvalService.addSign(taskId, u3, null);
        Map<String, Object> extra = tasksOf(u3, instanceId).stream()
                .filter(row -> Boolean.TRUE.equals(row.get("extra")))
                .findFirst().orElseThrow();
        long extraId = ((Number) extra.get("id")).longValue();

        login(u3, "PLANNER");
        assertThrows(BizException.class, () -> approvalService.rejectExtra(extraId, " "));
        assertEquals("REJECTED", approvalService.rejectExtra(extraId, "库存对不上").get("status"));
        assertEquals("REJECTED", approvalService.statusOf(instanceId));
    }

    @Test
    @DisplayName("不能给自己加签，也不能重复给同一个人加签")
    void addSignValidation() {
        saveFlow("B7", true, node("n1", "会签审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("B7");
        String taskId = String.valueOf(tasksOf(u2, instanceId).get(0).get("id"));
        login(u2, "PLANNER");
        assertThrows(BizException.class, () -> approvalService.addSign(taskId, u2, null));

        approvalService.addSign(taskId, u3, null);
        assertThrows(BizException.class, () -> approvalService.addSign(taskId, u3, null));
    }

    @Test
    @DisplayName("催办：只有申请人和管理员可以催，且能推到当前待办人")
    void urgeOnlyApplicantOrAdmin() {
        saveFlow("B6", true, node("n1", "计划审核", "USER", List.of(u2), "ANY"));
        long instanceId = submit("B6");

        login(u3, "PLANNER");
        assertThrows(BizException.class, () -> approvalService.urge(instanceId), "非申请人不能催办");

        login(u1, "PLANNER");
        Map<String, Object> result = approvalService.urge(instanceId);
        assertEquals(1, ((Number) result.get("notified")).intValue());
        Integer notices = jdbc.queryForObject("""
                select count(*) from sys_notice where receiver = ? and title like '审批催办%'
                """, Integer.class, u2);
        assertTrue(notices != null && notices > 0);
    }
}
