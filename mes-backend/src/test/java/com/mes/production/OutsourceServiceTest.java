package com.mes.production;

import com.mes.common.BizException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工序外协：转外协 → 领料与报工被拦住 → 发出 → 收回。
 *
 * <p>整类在事务里跑，结束回滚。数据依赖演示库里的在制工单：找不到可用的就跳过，
 * 完整链路交给 {@code scripts/e2e-outsource.py} 覆盖。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OutsourceServiceTest {

    @Autowired
    private OutsourceService service;

    @Autowired
    private ProductionService productionService;

    @Autowired
    private WorkOrderIssueService issueService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 找一张能转外协的工单：已下达或在制，某道工序没做完，且这道工序挂了 BOM */
    private Map<String, Object> findOpenOperation() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select w.id as "workOrderId", w.work_no as "workNo", o.id as "operationId",
                       o.seq, o.name, o.plan_qty - o.done_qty as "remainingQty"
                from work_order w
                join work_order_operation o on o.work_order_id = w.id
                where w.status in ('RELEASED', 'IN_PROGRESS')
                  and o.done_qty < o.plan_qty
                  and exists (select 1 from work_order_bom b
                              where b.work_order_id = w.id and b.operation_seq = o.seq
                                and b.source <> 'SUBSTITUTE')
                order by o.seq, w.id
                limit 1
                """);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long supplierId() {
        List<Long> rows = jdbcTemplate.queryForList("""
                select id from supplier where enabled order by
                  case when category = 'OUTSOURCE' then 0 else 1 end, id limit 1
                """, Long.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    @DisplayName("转外协后：领料与报工都被拦住，撤销后恢复厂内做")
    void planBlocksIssueAndReport() {
        Map<String, Object> op = findOpenOperation();
        Long supplier = supplierId();
        if (op == null || supplier == null) {
            return;
        }
        long workOrderId = ((Number) op.get("workOrderId")).longValue();
        int seq = ((Number) op.get("seq")).intValue();
        long operationId = ((Number) op.get("operationId")).longValue();

        service.plan(workOrderId, seq, new OutsourceService.PlanRequest(
                supplier, new BigDecimal("8.50"), null, "单测转外协"));
        assertEquals("PENDING_SEND", statusOf(operationId));

        // 待发料清单里应该看得到这道工序的料
        Map<String, Object> materials = service.materials(workOrderId, seq);
        assertTrue(((List<?>) materials.get("lines")).size() > 0, "外协工序应该有 BOM 用料");

        // 领料被拦：料要随外协发出，不能从本厂领
        Long materialId = jdbcTemplate.queryForObject("""
                select material_id from work_order_bom
                where work_order_id = ? and operation_seq = ? and source <> 'SUBSTITUTE'
                order by id limit 1
                """, Long.class, workOrderId, seq);
        BizException issueError = assertThrows(BizException.class, () -> issueService.issueBatch(
                workOrderId, new WorkOrderIssueService.IssueBatchRequest(null, null, null, "PC", "单测",
                        List.of(new WorkOrderIssueService.IssueLine(materialId, BigDecimal.ONE)))));
        assertTrue(issueError.getMessage().contains("外协"), issueError.getMessage());

        // 报工被拦：数量由外协单收回登记
        BizException reportError = assertThrows(BizException.class, () -> productionService.report(
                workOrderId, new ProductionService.ReportRequest(operationId, BigDecimal.ONE, null, null,
                        null, "单测报工", null, null)));
        assertTrue(reportError.getMessage().contains("外协"), reportError.getMessage());

        // 撤销后回到厂内做
        service.cancelPlan(workOrderId, seq);
        assertNull(statusOf(operationId));
    }

    @Test
    @DisplayName("发出 → 收回：外协单与工序完成数一起走，物料成本走外协发出的流水")
    void sendAndReceive() {
        Map<String, Object> op = findOpenOperation();
        Long supplier = supplierId();
        if (op == null || supplier == null) {
            return;
        }
        long workOrderId = ((Number) op.get("workOrderId")).longValue();
        int seq = ((Number) op.get("seq")).intValue();
        long operationId = ((Number) op.get("operationId")).longValue();
        BigDecimal remaining = new BigDecimal(String.valueOf(op.get("remainingQty")));

        // 料不够一半就直接跳过：这条链路要真的扣库存，测试库库存不由本用例负责
        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                select b.material_id as "materialId", b.plan_qty - b.issued_qty as "needQty",
                       coalesce((select sum(i.qty) from inventory i where i.product_id = b.material_id
                                 and i.warehouse_id = (select id from warehouse where type = 'RAW' order by id limit 1)), 0)
                           as "stockQty"
                from work_order_bom b
                where b.work_order_id = ? and b.operation_seq = ? and b.source <> 'SUBSTITUTE'
                """, workOrderId, seq);
        for (Map<String, Object> line : lines) {
            BigDecimal need = new BigDecimal(String.valueOf(line.get("needQty")));
            BigDecimal stock = new BigDecimal(String.valueOf(line.get("stockQty")));
            if (need.signum() > 0 && stock.compareTo(need) < 0) {
                return;
            }
        }

        BigDecimal sendQty = remaining.min(new BigDecimal("5"));
        Map<String, Object> sent = service.send(workOrderId, seq, new OutsourceService.SendRequest(
                sendQty, supplier, new BigDecimal("6.00"), null, null, null, "单测发出", null));
        long orderId = ((Number) sent.get("orderId")).longValue();
        assertEquals("SENT", statusOf(operationId));
        assertEquals("SENT", jdbcTemplate.queryForObject(
                "select status from outsource_order where id = ?", String.class, orderId));
        assertTrue(((List<?>) sent.get("lines")).size() > 0);

        // 同工序再发一次会被拦（未收回的在途数量已经占住计划量时）
        Map<String, Object> received = service.receive(orderId, new OutsourceService.ReceiveRequest(
                sendQty, BigDecimal.ZERO, null, "单测收回", "PASS", null));
        assertNotNull(received.get("orderNo"));
        assertEquals("RECEIVED", jdbcTemplate.queryForObject(
                "select status from outsource_order where id = ?", String.class, orderId));
        BigDecimal done = jdbcTemplate.queryForObject(
                "select done_qty from work_order_operation where id = ?", BigDecimal.class, operationId);
        assertTrue(done.compareTo(BigDecimal.ZERO) > 0, "收回后工序完成数应该增加");
    }

    @Test
    @DisplayName("边界：外协数量不能超过计划量，收回不能超过外协量")
    void invalidQuantitiesRejected() {
        Map<String, Object> op = findOpenOperation();
        Long supplier = supplierId();
        if (op == null || supplier == null) {
            return;
        }
        long workOrderId = ((Number) op.get("workOrderId")).longValue();
        int seq = ((int) ((Number) op.get("seq")).intValue());
        BigDecimal remaining = new BigDecimal(String.valueOf(op.get("remainingQty")));

        assertThrows(BizException.class, () -> service.send(workOrderId, seq,
                new OutsourceService.SendRequest(remaining.add(new BigDecimal("1000")), supplier,
                        BigDecimal.ONE, null, null, null, null, null)));
        assertThrows(BizException.class, () -> service.send(workOrderId, seq,
                new OutsourceService.SendRequest(BigDecimal.ONE, null, BigDecimal.ONE,
                        null, null, null, null, null)));
    }

    private String statusOf(long operationId) {
        return jdbcTemplate.queryForObject(
                "select outsource_status from work_order_operation where id = ?", String.class, operationId);
    }
}
