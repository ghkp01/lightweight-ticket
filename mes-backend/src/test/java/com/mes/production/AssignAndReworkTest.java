package com.mes.production;

import com.mes.common.BizException;
import com.mes.production.api.ReworkRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 派工选人与返修起点测试：
 * 派工只能指向系统用户；返修可以从中间某道工序起做，前面的工序不再重复加工。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AssignAndReworkTest {

    @Autowired
    private ProductionService productionService;

    @Autowired
    private ReworkService reworkService;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long workOrderId;
    private long firstOperationId;
    private long secondOperationId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 100) returning id
                """, Long.class, "AR" + suffix, "派工返修测试产品" + suffix);

        Long cut = jdbc.queryForObject("select id from process where code = 'CUT'", Long.class);
        Long assy = jdbc.queryForObject("select id from process where code = 'ASSY'", Long.class);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 1.5, 1.20)
                """, productId, cut);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 2, ?, 2.0, 2.00)
                """, productId, assy);

        long frontMaterial = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "AF" + suffix, "前道主料" + suffix);
        long backMaterial = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "AB" + suffix, "装配辅料" + suffix);
        Long bomId = jdbc.queryForObject("""
                insert into bom (bom_no, product_id, version, status, base_qty, effective_from)
                values (?, ?, 'V1.0', 'ACTIVE', 1, current_date) returning id
                """, Long.class, "BOMAR" + suffix, productId);
        jdbc.update("""
                insert into bom_item (bom_id, material_id, qty, operation_seq)
                values (?, ?, 1, 1)
                """, bomId, frontMaterial);
        jdbc.update("""
                insert into bom_item (bom_id, material_id, qty, operation_seq)
                values (?, ?, 2, 2)
                """, bomId, backMaterial);

        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'RELEASED') returning id
                """, Long.class, "ARW" + suffix, productId);
        productionService.createOperations(workOrderId, productId, BigDecimal.TEN);
        productionService.snapshotBom(workOrderId, productId, BigDecimal.TEN);
        firstOperationId = jdbc.queryForObject("""
                select id from work_order_operation where work_order_id = ? and seq = 1
                """, Long.class, workOrderId);
        secondOperationId = jdbc.queryForObject("""
                select id from work_order_operation where work_order_id = ? and seq = 2
                """, Long.class, workOrderId);
    }

    private long createUser(String username, String realName, boolean enabled) {
        return jdbc.queryForObject("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, 'x', ?, 'QC', ?) returning id
                """, Long.class, username, realName, enabled);
    }

    @Test
    @DisplayName("派工只能选系统用户，非法用户被拒")
    void assignRejectsUnknownUser() {
        BizException ex = assertThrows(BizException.class,
                () -> productionService.assignOperation(workOrderId, firstOperationId, 99999999L, null));
        assertEquals(1002, ex.getCode());
    }

    @Test
    @DisplayName("派工不能派给停用用户")
    void assignRejectsDisabledUser() {
        long userId = createUser("ar-dis-" + System.nanoTime(), "停用师傅", false);
        BizException ex = assertThrows(BizException.class,
                () -> productionService.assignOperation(workOrderId, firstOperationId, userId, null));
        assertEquals(1002, ex.getCode());
    }

    @Test
    @DisplayName("派工选人后写入责任人与显示名")
    void assignWritesUserAndDisplayName() {
        long userId = createUser("ar-ok-" + System.nanoTime(), "张师傅", true);
        // 演示库里下料工序已启用持证管控，这里给测试用户补一条技能
        jdbc.update("""
                insert into user_skill (user_id, process_id, level)
                values (?, (select id from process where code = 'CUT'), 'SENIOR')
                on conflict do nothing
                """, userId);
        Map<String, Object> result = productionService.assignOperation(
                workOrderId, firstOperationId, userId, null);

        assertEquals(userId, ((Number) result.get("assignedUserId")).longValue());
        assertEquals("张师傅", result.get("assignedWorker"));

        Map<String, Object> op = jdbc.queryForMap("""
                select assigned_user_id, assigned_worker from work_order_operation where id = ?
                """, firstOperationId);
        assertEquals(userId, ((Number) op.get("assigned_user_id")).longValue());
        assertEquals("张师傅", op.get("assigned_worker"));
    }

    @Test
    @DisplayName("只传名字且匹配不到用户时要求改成选人")
    void assignByUnknownNameRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> productionService.assignOperation(workOrderId, firstOperationId, null, "查无此人"));
        assertEquals(1002, ex.getCode());
    }

    @Test
    @DisplayName("返修从第 2 道工序起做：只生成后道工序")
    void reworkStartsFromMiddleOperation() {
        long reworkId = reworkService.createRework(new ReworkRequest(productId, new BigDecimal("2"),
                workOrderId, null, null, null, "装配不良返修", 2));
        assertNotNull(reworkId);

        List<Map<String, Object>> ops = jdbc.queryForList("""
                select seq, name from work_order_operation where work_order_id = ? order by seq
                """, reworkId);
        assertEquals(1, ops.size(), "返修工单只应有第 2 道工序");
        assertEquals(2, ((Number) ops.get(0).get("seq")).intValue());

        Integer fromSeq = jdbc.queryForObject(
                "select rework_from_seq from work_order where id = ?", Integer.class, reworkId);
        assertEquals(2, fromSeq.intValue());
    }

    @Test
    @DisplayName("返修从第 2 道起：BOM 只带后道物料")
    void reworkBomOnlyKeepsLaterMaterials() {
        long reworkId = reworkService.createRework(new ReworkRequest(productId, new BigDecimal("2"),
                workOrderId, null, null, null, "装配不良返修", 2));
        List<Map<String, Object>> bom = jdbc.queryForList("""
                select operation_seq from work_order_bom where work_order_id = ? and source = 'STANDARD'
                """, reworkId);
        assertEquals(1, bom.size(), "只应带第 2 道工序的物料");
        assertEquals(2, ((Number) bom.get(0).get("operation_seq")).intValue());
    }

    @Test
    @DisplayName("返修单从第 2 道起可直接报工，不受前道缺失影响")
    void reworkReportSkipsMissingPrevious() {
        long reworkId = reworkService.createRework(new ReworkRequest(productId, new BigDecimal("2"),
                workOrderId, null, null, null, "装配不良返修", 2));
        jdbc.update("update work_order set status = 'RELEASED' where id = ?", reworkId);
        long reworkOpId = jdbc.queryForObject("""
                select id from work_order_operation where work_order_id = ? and seq = 2
                """, Long.class, reworkId);

        productionService.report(reworkId, new ProductionService.ReportRequest(
                reworkOpId, new BigDecimal("2"), new BigDecimal("2"), BigDecimal.ZERO,
                "返修工", null, null, null));

        BigDecimal done = jdbc.queryForObject("""
                select done_qty from work_order_operation where id = ?
                """, BigDecimal.class, reworkOpId);
        assertEquals(0, done.compareTo(new BigDecimal("2")));
    }

    @Test
    @DisplayName("普通工单仍要求前道完工才能报工")
    void normalWorkOrderStillChecksPrevious() {
        BizException ex = assertThrows(BizException.class,
                () -> productionService.report(workOrderId, new ProductionService.ReportRequest(
                        secondOperationId, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                        "测试工", null, null, null)));
        assertEquals(true, ex.getMessage().contains("前道"));
    }
}
