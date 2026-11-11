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
 * 定向灰度：按人 / 按工厂定向走新版本，名单外的人按比例或走稳定版。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalCanaryTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private String u1;
    private String u2;
    private long plantId;

    private void login(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void user(String username) {
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, 'x', ?, 'PLANNER', true)
                """, username, "灰度测试" + username);
    }

    private ApprovalService.NodeRequest node(String key, String name) {
        return new ApprovalService.NodeRequest(null, name, "USER", List.of(u2), "ANY",
                null, null, null, List.of(), null, "APPROVER", null, null, 1, "AUTO_PASS",
                true, true, true, null, null, key);
    }

    /** 建第一条流程（v1） */
    private long createFlow(String bizType) {
        login("admin", "ADMIN");
        return approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                "CV" + tag + bizType, "灰度测试" + bizType, bizType, true, "灰度用例",
                List.of(node("n1", "一级审批"))));
    }

    /** 再存一次，产生 v2（此时 v2 是最新版本，也是「稳定版本」的候选） */
    private void saveSecondVersion(long flowId, String bizType) {
        login("admin", "ADMIN");
        approvalService.saveFlow(flowId, new ApprovalService.FlowRequest(
                "CV" + tag + bizType, "灰度测试" + bizType, bizType, true, "灰度用例第二版",
                List.of(node("n1", "一级审批"))));
    }

    private long submit(String bizType, String applicant) {
        login(applicant, "PLANNER");
        Long id = approvalService.submit(bizType, 1L, "CV" + tag, "灰度单据 " + tag, Map.of());
        assertNotNull(id, "应生成审批实例");
        return id;
    }

    /** 实例实际跑的流程定义版本 */
    private int definitionVersionOf(long instanceId) {
        String pid = jdbc.queryForObject("select process_id from sys_approval_instance where id = ?",
                String.class, instanceId);
        String definitionId = jdbc.queryForObject(
                "select proc_def_id_ from act_hi_procinst where proc_inst_id_ = ?", String.class, pid);
        Integer version = jdbc.queryForObject(
                "select version_ from act_re_procdef where id_ = ?", Integer.class, definitionId);
        return version == null ? 0 : version;
    }

    @BeforeEach
    void setUp() {
        tag = Long.toString(System.nanoTime());
        // 一个工厂 + 两个发起人：u1 挂在工厂下，u2 不挂组织
        plantId = jdbc.queryForObject("""
                insert into sys_org (code, name, type, path, level, sort, enabled)
                values (?, ?, 'PLANT', '/', 1, 0, true) returning id
                """, Long.class, "CP" + tag, "灰度工厂" + tag);
        u1 = "ca" + tag;
        u2 = "cb" + tag;
        user(u1);
        user(u2);
        jdbc.update("update sys_user set org_id = ? where username = ?", plantId, u1);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("定向用户：名单内走灰度版，名单外走稳定版")
    void targetUserGoesToCanary() {
        long flowId = createFlow("T1");
        saveSecondVersion(flowId, "T1");

        login("admin", "ADMIN");
        Map<String, Object> result = approvalService.configureCanary(flowId,
                new ApprovalService.CanaryRequest(1, 0, null, List.of(u1), "先放给 u1 试"));
        assertTrue(result.get("message").toString().contains("定向"), result.toString());

        assertEquals(1, definitionVersionOf(submit("T1", u1)), "定向名单里的人应走灰度版 v1");
        assertEquals(2, definitionVersionOf(submit("T1", u2)), "名单外的人应走稳定版 v2");
    }

    @Test
    @DisplayName("定向工厂：工厂内的人走灰度版")
    void targetFactoryGoesToCanary() {
        long flowId = createFlow("T2");
        saveSecondVersion(flowId, "T2");

        login("admin", "ADMIN");
        approvalService.configureCanary(flowId,
                new ApprovalService.CanaryRequest(1, 0, List.of(plantId), null, "先放给这个厂"));

        assertEquals(1, definitionVersionOf(submit("T2", u1)), "工厂命中的发起人应走灰度版 v1");
        assertEquals(2, definitionVersionOf(submit("T2", u2)), "不在该工厂的人应走稳定版 v2");
    }

    @Test
    @DisplayName("比例 100 全量灰度，比例 0 且无定向直接关闭")
    void percentOnlyCanary() {
        long flowId = createFlow("T3");
        saveSecondVersion(flowId, "T3");

        login("admin", "ADMIN");
        approvalService.configureCanary(flowId, new ApprovalService.CanaryRequest(1, 100));
        assertEquals(1, definitionVersionOf(submit("T3", u2)), "100% 应全部走灰度版");

        approvalService.configureCanary(flowId, new ApprovalService.CanaryRequest(1, 0));
        Map<String, Object> status = approvalService.canaryStatus(flowId);
        assertFalse(Boolean.TRUE.equals(status.get("active")), "比例为 0 且无定向应视为关闭");
        assertEquals(2, definitionVersionOf(submit("T3", u2)), "关闭后应回到稳定版");
    }

    @Test
    @DisplayName("定向工厂 + 比例：名单内必中，名单外按比例（这里用 0% 验证不中）")
    void targetedPlusPercent() {
        long flowId = createFlow("T4");
        saveSecondVersion(flowId, "T4");

        login("admin", "ADMIN");
        approvalService.configureCanary(flowId,
                new ApprovalService.CanaryRequest(1, 0, List.of(plantId), List.of(), "只放工厂"));
        assertEquals(1, definitionVersionOf(submit("T4", u1)), "工厂名单命中必走灰度");
        assertEquals(2, definitionVersionOf(submit("T4", u2)), "之外的人 0% 不命中");
    }

    @Test
    @DisplayName("灰度现状：返回规则与各版本单据量")
    void statusReportsRuleAndVersions() {
        long flowId = createFlow("T5");
        saveSecondVersion(flowId, "T5");

        // 只把工厂放给灰度版，名单里再挂一个不会发起单的用户，用来验证名单回读
        String idleUser = "cc" + tag;
        user(idleUser);

        login("admin", "ADMIN");
        approvalService.configureCanary(flowId,
                new ApprovalService.CanaryRequest(1, 0, List.of(plantId), List.of(idleUser), "工厂定向"));
        submit("T5", u1);
        submit("T5", u2);

        Map<String, Object> status = approvalService.canaryStatus(flowId);
        assertEquals(Boolean.TRUE, status.get("active"));
        assertEquals(1, ((Number) status.get("canaryVersion")).intValue());
        assertEquals(0, ((Number) status.get("canaryPercent")).intValue());
        assertEquals(List.of(plantId), status.get("canaryFactoryIds"));
        assertEquals(List.of(idleUser), status.get("canaryUsers"));
        assertEquals("2", String.valueOf(status.get("stableVersion")), "稳定版应是开灰度时的最新版 v2");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) status.get("versions");
        assertTrue(versions.size() >= 2, "应能看到两个版本的单据量：" + versions);
        int total = versions.stream()
                .mapToInt(row -> ((Number) row.get("total")).intValue())
                .sum();
        assertEquals(2, total, "两次提交都应计入版本统计");
        assertTrue(versions.stream().anyMatch(row -> ((Number) row.get("version")).intValue() == 1),
                "统计里应有灰度版 v1：" + versions);
        assertTrue(versions.stream().anyMatch(row -> ((Number) row.get("version")).intValue() == 2),
                "统计里应有稳定版 v2：" + versions);
    }

    @Test
    @DisplayName("关闭灰度：清掉定向名单，回到稳定版")
    void disableCanaryClearsTargets() {
        long flowId = createFlow("T6");
        saveSecondVersion(flowId, "T6");

        login("admin", "ADMIN");
        approvalService.configureCanary(flowId,
                new ApprovalService.CanaryRequest(1, 0, List.of(plantId), List.of(u1), "开灰度"));
        approvalService.configureCanary(flowId, new ApprovalService.CanaryRequest(null, 0));

        Map<String, Object> status = approvalService.canaryStatus(flowId);
        assertFalse(Boolean.TRUE.equals(status.get("active")));
        assertTrue(((List<?>) status.get("canaryFactoryIds")).isEmpty(), "关闭后定向工厂应清空");
        assertTrue(((List<?>) status.get("canaryUsers")).isEmpty(), "关闭后定向用户应清空");
        assertEquals(2, definitionVersionOf(submit("T6", u1)), "关闭后主力账号也回稳定版");
    }

    @Test
    @DisplayName("灰度版本不存在时拒绝开启")
    void rejectUnknownVersion() {
        long flowId = createFlow("T7");
        saveSecondVersion(flowId, "T7");

        login("admin", "ADMIN");
        assertThrows(BizException.class, () -> approvalService.configureCanary(flowId,
                new ApprovalService.CanaryRequest(99, 10)));
    }
}
