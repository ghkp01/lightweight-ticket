package com.mes.production;

import com.mes.common.BizException;
import com.mes.system.UserSkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派工持证校验：工序配了技能就只能派给持证人员，没配则放开。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AssignSkillTest {

    @Autowired
    private ProductionService productionService;

    @Autowired
    private UserSkillService userSkillService;

    @Autowired
    private JdbcTemplate jdbc;

    private long processId;
    private long operationId;
    private long workOrderId;
    private long productId;
    private long skilledUserId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        processId = jdbc.queryForObject("""
                insert into process (code, name, category, need_inspection, inspection_mode,
                                     enabled, default_minutes, default_price)
                values (?, ?, 'MACHINING', false, 'NONE', true, 2, 1)
                returning id
                """, Long.class, "SP" + suffix, "技能测试工序" + suffix);
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 10) returning id
                """, Long.class, "SPD" + suffix, "技能测试产品" + suffix);
        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 5, 'RELEASED') returning id
                """, Long.class, "SWO" + suffix, productId);
        operationId = jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty,
                                                  standard_minutes, unit_price, process_id)
                values (?, 1, ?, 5, 10, 1, ?) returning id
                """, Long.class, workOrderId, "技能测试工序", processId);

        skilledUserId = createUser("skilled" + suffix, "持证师傅");
        otherUserId = createUser("other" + suffix, "没证师傅");
    }

    private long createUser(String username, String realName) {
        return jdbc.queryForObject("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, 'x', ?, 'QC', true) returning id
                """, Long.class, username, realName);
    }

    @Test
    @DisplayName("工序配了技能后，非持证人员不能派工")
    void assignRejectsUserWithoutSkill() {
        userSkillService.save(skilledUserId, new UserSkillService.SkillRequest(List.of(
                new UserSkillService.SkillItem(processId, "SENIOR", null))));

        BizException ex = assertThrows(BizException.class, () -> productionService.assignOperation(
                workOrderId, operationId, otherUserId, null));
        assertEquals(2022, ex.getCode());
    }

    @Test
    @DisplayName("持证人员可以正常派工")
    void assignAcceptsSkilledUser() {
        userSkillService.save(skilledUserId, new UserSkillService.SkillRequest(List.of(
                new UserSkillService.SkillItem(processId, "SENIOR", null))));

        Map<String, Object> result = productionService.assignOperation(
                workOrderId, operationId, skilledUserId, null);
        assertEquals(skilledUserId, ((Number) result.get("assignedUserId")).longValue());
        assertEquals("持证师傅", result.get("assignedWorker"));
    }

    @Test
    @DisplayName("工序没人配技能时不限制派工")
    void assignWithoutSkillConfigAllowsAnyone() {
        Map<String, Object> result = productionService.assignOperation(
                workOrderId, operationId, otherUserId, null);
        assertEquals(otherUserId, ((Number) result.get("assignedUserId")).longValue());
    }

    @Test
    @DisplayName("候选人只返回持证人员")
    void candidatesReturnSkilledUsersOnly() {
        userSkillService.save(skilledUserId, new UserSkillService.SkillRequest(List.of(
                new UserSkillService.SkillItem(processId, "NORMAL", null))));
        List<com.mes.system.api.SkillUser> candidates = userSkillService.candidates(processId);
        assertEquals(1, candidates.size());
        assertEquals(skilledUserId, candidates.get(0).id());
        assertTrue(userSkillService.processRequiresSkill(processId));
    }

    @Test
    @DisplayName("清空技能后工序回到不限制状态")
    void clearingSkillsUnlocksProcess() {
        userSkillService.save(skilledUserId, new UserSkillService.SkillRequest(List.of(
                new UserSkillService.SkillItem(processId, "SENIOR", null))));
        userSkillService.save(skilledUserId, new UserSkillService.SkillRequest(List.of()));
        assertTrue(!userSkillService.processRequiresSkill(processId));
    }

    @Test
    @DisplayName("多人分配：按人卡配额，超了报不进去")
    void multiAssignmentQuota() {
        productionService.assignOperations(workOrderId, operationId, List.of(
                new ProductionService.AssignmentItem(skilledUserId, new BigDecimal("2")),
                new ProductionService.AssignmentItem(otherUserId, new BigDecimal("3"))
        ), null);

        Map<String, Object> detail = productionService.detail(workOrderId);
        Map<?, ?> op = ((List<Map<String, Object>>) detail.get("operations")).get(0);
        assertEquals(2, ((List<?>) op.get("assignments")).size(), "两道人都要进名单");

        // 分了 2 件却报 3 件 → 拦
        BizException over = assertThrows(BizException.class, () -> productionService.report(workOrderId,
                new ProductionService.ReportRequest(operationId, new BigDecimal("3"), null, BigDecimal.ZERO,
                        "持证师傅", null, "NONE", null)));
        assertEquals(2025, over.getCode());
        assertTrue(over.getMessage().contains("分配量只剩"), over.getMessage());

        // 名单外的人不能报
        BizException outsider = assertThrows(BizException.class, () -> productionService.report(workOrderId,
                new ProductionService.ReportRequest(operationId, new BigDecimal("1"), null, BigDecimal.ZERO,
                        "路人甲", null, "NONE", null)));
        assertEquals(2025, outsider.getCode());
        assertTrue(outsider.getMessage().contains("只能是"), outsider.getMessage());

        // 报满自己的 2 件后，再报就被配额挡住
        productionService.report(workOrderId, new ProductionService.ReportRequest(
                operationId, new BigDecimal("2"), null, BigDecimal.ZERO, "持证师傅", null, "NONE", null));
        BizException full = assertThrows(BizException.class, () -> productionService.report(workOrderId,
                new ProductionService.ReportRequest(operationId, new BigDecimal("1"), null, BigDecimal.ZERO,
                        "持证师傅", null, "NONE", null)));
        assertEquals(2025, full.getCode());
    }

    @Test
    @DisplayName("多人分配：各报各的额度，已报量互不串人")
    void multiAssignmentAccumulatesPerWorker() {
        productionService.assignOperations(workOrderId, operationId, List.of(
                new ProductionService.AssignmentItem(skilledUserId, new BigDecimal("2")),
                new ProductionService.AssignmentItem(otherUserId, new BigDecimal("3"))
        ), null);

        productionService.report(workOrderId, new ProductionService.ReportRequest(
                operationId, new BigDecimal("2"), null, BigDecimal.ZERO, "持证师傅", null, "NONE", null));
        productionService.report(workOrderId, new ProductionService.ReportRequest(
                operationId, new BigDecimal("3"), null, BigDecimal.ZERO, "没证师傅", null, "NONE", null));

        Map<String, Object> detail = productionService.detail(workOrderId);
        List<Map<String, Object>> assignments = (List<Map<String, Object>>)
                ((Map<?, ?>) ((List<Map<String, Object>>) detail.get("operations")).get(0)).get("assignments");
        Map<String, Object> first = assignments.get(0);
        Map<String, Object> second = assignments.get(1);
        assertEquals(0, ((BigDecimal) first.get("reportedQty")).compareTo(new BigDecimal("2")));
        assertEquals(0, ((BigDecimal) first.get("remainingQty")).signum());
        assertEquals(0, ((BigDecimal) second.get("reportedQty")).compareTo(new BigDecimal("3")));
        assertEquals(0, ((BigDecimal) second.get("remainingQty")).signum());

        // 清空名单后回到「不限人」状态
        productionService.assignOperations(workOrderId, operationId, List.of(), null);
        Map<String, Object> cleared = productionService.detail(workOrderId);
        Map<?, ?> op = ((List<Map<String, Object>>) cleared.get("operations")).get(0);
        assertTrue(((List<?>) op.get("assignments")).isEmpty());
        assertEquals(null, op.get("assignedWorker"));
    }

    @Test
    @DisplayName("报工权限：设成「仅派工人员」后，其他人报工被拦")
    void reportBlockedWhenAssignedOnly() {
        productionService.assignOperation(workOrderId, operationId, skilledUserId, null, "ASSIGNED");
        Map<String, Object> detail = productionService.detail(workOrderId);
        Map<?, ?> op = ((List<Map<String, Object>>) detail.get("operations")).get(0);
        assertEquals("ASSIGNED", op.get("reportPolicy"));
        assertEquals(skilledUserId, ((Number) op.get("assignedUserId")).longValue());

        // 测试里没有登录态，CurrentUser 是 system，不等于派工人员 → 必须被拦
        BizException ex = assertThrows(BizException.class, () -> productionService.report(workOrderId,
                new ProductionService.ReportRequest(operationId, new BigDecimal("1"), null, BigDecimal.ZERO,
                        "别人", null, "NONE", null)));
        assertEquals(2024, ex.getCode());
        assertTrue(ex.getMessage().contains("只允许"), ex.getMessage());
    }

    @Test
    @DisplayName("报工权限：默认所有人可报，别人也能报")
    void reportAllowedByDefault() {
        productionService.assignOperation(workOrderId, operationId, skilledUserId, null);

        productionService.report(workOrderId, new ProductionService.ReportRequest(
                operationId, new BigDecimal("1"), null, BigDecimal.ZERO, "别人", null, "NONE", null));

        BigDecimal done = jdbc.queryForObject(
                "select done_qty from work_order_operation where id = ?", BigDecimal.class, operationId);
        assertEquals(0, done.compareTo(new BigDecimal("1")));
    }

    @Test
    @DisplayName("报工权限只接受 ANY / ASSIGNED")
    void reportPolicyValidation() {
        BizException ex = assertThrows(BizException.class, () -> productionService.assignOperation(
                workOrderId, operationId, skilledUserId, null, "BOSS_ONLY"));
        assertEquals(1002, ex.getCode());
        assertTrue(ex.getMessage().contains("报工权限"), ex.getMessage());
    }

    @Test
    @DisplayName("建单时就能派工：工序带上报工人与报工权限")
    void createWithAssignment() {
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 2, 1)
                """, productId, processId);

        long id = productionService.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("3"), null, null, null, null, null,
                List.of(new ProductionService.OperationAssignment(1, skilledUserId, "ASSIGNED"))));

        Map<String, Object> detail = productionService.detail(id);
        Map<?, ?> op = ((List<Map<String, Object>>) detail.get("operations")).get(0);
        assertEquals("ASSIGNED", op.get("reportPolicy"));
        assertEquals("持证师傅", op.get("assignedWorker"));
        assertEquals(skilledUserId, ((Number) op.get("assignedUserId")).longValue());
    }

    @Test
    @DisplayName("建单派工：设了「仅他能报」却没选人，直接拒")
    void createWithAssignedPolicyButNoUser() {
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 2, 1)
                """, productId, processId);

        BizException ex = assertThrows(BizException.class, () -> productionService.create(
                new ProductionService.WorkOrderRequest(
                        productId, new BigDecimal("3"), null, null, null, null, null,
                        List.of(new ProductionService.OperationAssignment(1, null, "ASSIGNED")))));
        assertTrue(ex.getMessage().contains("要同时选报工人"), ex.getMessage());
    }
}
