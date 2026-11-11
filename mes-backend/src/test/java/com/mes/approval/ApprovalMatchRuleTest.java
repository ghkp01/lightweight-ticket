package com.mes.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按适用条件分流：多条审批链怎么命中、优先级怎么排、坏规则为什么不能当兜底。
 *
 * <p>用 simulate 验算而不是真起流程：分流的正确性全在匹配这一段，
 * 真起流程由 e2e-approval-route 覆盖。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalMatchRuleTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private JdbcTemplate jdbc;

    private String tag;

    @BeforeEach
    void setUp() {
        tag = Long.toString(System.nanoTime());
        // 库里可能还有别的测试留下的 REQUISITION 流程，先清掉，保证只按本用例的规则匹配
        jdbc.update("delete from sys_approval_flow where biz_type = 'REQUISITION'");
    }

    private long insertFlow(String code, String rulesJson, int priority) {
        return jdbc.queryForObject("""
                insert into sys_approval_flow (code, name, biz_type, enabled, created_by, match_rules, priority)
                values (?, ?, 'REQUISITION', true, 'test', ?::jsonb, ?) returning id
                """, Long.class, code + tag, "流程" + code, rulesJson, priority);
    }

    private Map<String, Object> simulate(Map<String, Object> variables) {
        return approvalService.simulate("REQUISITION", variables);
    }

    @Test
    @DisplayName("规则为空就是兜底：任何单据都命中")
    void catchAllAlwaysMatches() {
        insertFlow("REQ_FALLBACK", null, 10);
        Map<String, Object> result = simulate(Map.of("amount", 99999, "category", "EQUIPMENT"));
        assertTrue(Boolean.TRUE.equals(result.get("matched")), String.valueOf(result));
        assertEquals("REQ_FALLBACK" + tag, result.get("flowCode"));
    }

    @Test
    @DisplayName("按采购类别分流：外协件命中外协链，原辅料落到兜底")
    void routesByCategory() {
        insertFlow("REQ_OUTSOURCE",
                "[{\"field\":\"CATEGORY\",\"op\":\"IN\",\"value\":\"OUTSOURCE,SPARE\"}]", 200);
        insertFlow("REQ_FALLBACK", null, 10);

        Map<String, Object> outsourced = simulate(Map.of("category", "OUTSOURCE", "amount", 1200));
        assertEquals("REQ_OUTSOURCE" + tag, outsourced.get("flowCode"));

        Map<String, Object> material = simulate(Map.of("category", "MATERIAL", "amount", 1200));
        assertEquals("REQ_FALLBACK" + tag, material.get("flowCode"));
    }

    @Test
    @DisplayName("多条规则是 AND：金额与类别都得满足")
    void multipleRulesAreAnd() {
        insertFlow("REQ_BIG_OUTSOURCE",
                "[{\"field\":\"CATEGORY\",\"op\":\"=\",\"value\":\"OUTSOURCE\"},"
                        + "{\"field\":\"AMOUNT\",\"op\":\">=\",\"value\":\"5000\"}]", 300);

        assertTrue(Boolean.TRUE.equals(simulate(Map.of("category", "OUTSOURCE", "amount", 8000))
                .get("matched")), "外协且金额达标应命中");
        assertFalse(Boolean.TRUE.equals(simulate(Map.of("category", "OUTSOURCE", "amount", 1000))
                .get("matched")), "金额不够不应命中");
        assertFalse(Boolean.TRUE.equals(simulate(Map.of("category", "MATERIAL", "amount", 8000))
                .get("matched")), "类别不符不应命中");
    }

    @Test
    @DisplayName("优先级大的先匹配，两条都能命中时取优先级高的")
    void higherPriorityWins() {
        insertFlow("REQ_LOW", null, 10);
        insertFlow("REQ_HIGH",
                "[{\"field\":\"AMOUNT\",\"op\":\">=\",\"value\":\"1\"}]", 500);

        Map<String, Object> result = simulate(Map.of("amount", 100));
        assertEquals("REQ_HIGH" + tag, result.get("flowCode"));
        List<?> traces = (List<?>) result.get("traces");
        assertEquals(2, traces.size());
        Map<?, ?> first = (Map<?, ?>) traces.get(0);
        assertEquals(500, first.get("priority"));
    }

    @Test
    @DisplayName("布尔字段按 true / false 比较")
    void matchesBooleanField() {
        insertFlow("REQ_URGENT",
                "[{\"field\":\"URGENT\",\"op\":\"=\",\"value\":\"true\"}]", 200);
        assertTrue(Boolean.TRUE.equals(simulate(Map.of("urgent", true)).get("matched")));
        assertFalse(Boolean.TRUE.equals(simulate(Map.of("urgent", false)).get("matched")));
    }

    @Test
    @DisplayName("规则结构不对（不是数组）时跳过这条，也不当成兜底")
    void brokenRulesAreSkipped() {
        insertFlow("REQ_BROKEN", "{\"field\":\"CATEGORY\"}", 900);
        insertFlow("REQ_FALLBACK", null, 10);

        Map<String, Object> result = simulate(Map.of("amount", 100));
        // 坏规则那条跳过，落到兜底
        assertEquals("REQ_FALLBACK" + tag, result.get("flowCode"));
        List<?> traces = (List<?>) result.get("traces");
        Map<?, ?> broken = (Map<?, ?>) traces.get(0);
        assertFalse(Boolean.TRUE.equals(broken.get("pass")));
        assertNotNull(broken.get("hint"));
    }

    @Test
    @DisplayName("保存流程时同业务类型的优先级不能撞")
    void duplicatePriorityRejected() {
        insertFlow("REQ_FIRST", null, 100);
        try {
            approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                    "REQ_SECOND" + tag, "第二条", "REQUISITION", true, null,
                    List.of(new ApprovalService.NodeRequest(1, "审核", "ROLE",
                            List.of("PLANNER"), "ANY", null, null, null, null, null)),
                    null, null, null, 100, null));
        } catch (RuntimeException error) {
            assertTrue(error.getMessage().contains("优先级"), error.getMessage());
            return;
        }
        throw new AssertionError("优先级冲突应该被拒绝");
    }

    @Test
    @DisplayName("同一业务类型只能有一条兜底流程")
    void duplicateCatchAllRejected() {
        insertFlow("REQ_CATCH", null, 10);
        try {
            approvalService.saveFlow(null, new ApprovalService.FlowRequest(
                    "REQ_CATCH2" + tag, "第二条兜底", "REQUISITION", true, null,
                    List.of(new ApprovalService.NodeRequest(1, "审核", "ROLE",
                            List.of("PLANNER"), "ANY", null, null, null, null, null)),
                    null, null, null, 200, null));
        } catch (RuntimeException error) {
            assertTrue(error.getMessage().contains("兜底"), error.getMessage());
            return;
        }
        throw new AssertionError("第二条兜底应该被拒绝");
    }
}
