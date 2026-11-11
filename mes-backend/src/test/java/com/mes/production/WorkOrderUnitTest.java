package com.mes.production;

import com.mes.common.BizException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单件序列号（SN）测试：件码生成、扫件码流转、工序完成数联动、报工互斥、报废留痕。
 * 使用独立的 mes_test 库，测试事务回滚，不污染演示数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkOrderUnitTest {

    @Autowired
    private WorkOrderUnitService unitService;

    @Autowired
    private ProductionService productionService;

    @Autowired
    private JdbcTemplate jdbc;

    private long workOrderId;
    private long plainWorkOrderId;
    private String workNo;
    private long op1Id;
    private long op2Id;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        long serialProduct = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price, serial_managed, trace_mode)
                values (?, ?, 'FINISHED', '件', 0, true, 'SERIAL') returning id
                """, Long.class, "SP" + suffix, "单件成品" + suffix);
        long plainProduct = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price, serial_managed, trace_mode)
                values (?, ?, 'FINISHED', '件', 0, false, 'NONE') returning id
                """, Long.class, "PP" + suffix, "批量成品" + suffix);

        workNo = "WOS" + suffix;
        // 追溯方式以工单上的快照为准（产品只是默认值），所以造数时直接写工单
        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status, trace_mode)
                values (?, ?, 3, 'RELEASED', 'SERIAL') returning id
                """, Long.class, workNo, serialProduct);
        plainWorkOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status, trace_mode)
                values (?, ?, 3, 'RELEASED', 'NONE') returning id
                """, Long.class, "WOP" + suffix, plainProduct);

        long cut = processId("CUT" + suffix, "下料" + suffix);
        long machine = processId("MACH" + suffix, "机加工" + suffix);
        op1Id = jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, process_id)
                values (?, 1, ?, 3, ?) returning id
                """, Long.class, workOrderId, "下料" + suffix, cut);
        op2Id = jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, process_id)
                values (?, 2, ?, 3, ?) returning id
                """, Long.class, workOrderId, "机加工" + suffix, machine);
        jdbc.update("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, process_id)
                values (?, 1, ?, 3, ?)
                """, plainWorkOrderId, "下料" + suffix, cut);
    }

    private long processId(String code, String name) {
        return jdbc.queryForObject("""
                insert into process (code, name, enabled) values (?, ?, true) returning id
                """, Long.class, code, name);
    }

    private String firstUnitNo() {
        return jdbc.queryForObject("""
                select unit_no from work_order_unit where work_order_id = ? order by seq_no limit 1
                """, String.class, workOrderId);
    }

    private BigDecimal opDone(long operationId) {
        return jdbc.queryForObject("select done_qty from work_order_operation where id = ?",
                BigDecimal.class, operationId);
    }

    @Test
    @DisplayName("按计划数量生成件码，工单号-三位流水，重复调用不重复生成")
    void generatesUnitCodes() {
        int created = unitService.generateForWorkOrder(workOrderId);
        assertEquals(3, created);
        List<Map<String, Object>> units = jdbc.queryForList("""
                select unit_no, seq_no, status from work_order_unit
                where work_order_id = ? order by seq_no
                """, workOrderId);
        assertEquals(3, units.size());
        assertEquals(workNo + "-001", units.get(0).get("unit_no"));
        assertEquals(workNo + "-003", units.get(2).get("unit_no"));
        assertTrue(units.stream().allMatch(u -> "PENDING".equals(u.get("status"))));

        assertEquals(3, unitService.generateForWorkOrder(workOrderId));
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from work_order_unit where work_order_id = ?", Integer.class, workOrderId));
    }

    @Test
    @DisplayName("非单件产品不生成件码")
    void skipsPlainProduct() {
        assertEquals(0, unitService.generateForWorkOrder(plainWorkOrderId));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from work_order_unit where work_order_id = ?", Integer.class, plainWorkOrderId));
    }

    @Test
    @DisplayName("扫件码：首识别即指向第一道待做工序，可开工不可完工")
    void resolvesFirstOperation() {
        unitService.generateForWorkOrder(workOrderId);
        Map<String, Object> unit = unitService.resolve(firstUnitNo());

        assertEquals("PENDING", unit.get("status"));
        assertEquals(workNo, unit.get("workNo"));
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) unit.get("currentOperation");
        assertNotNull(current);
        assertEquals(1, ((Number) current.get("seq")).intValue());
        assertEquals(Boolean.TRUE, unit.get("canStart"));
        assertEquals(Boolean.FALSE, unit.get("canFinish"));
        assertTrue(unit.get("logs") instanceof List<?>);
    }

    @Test
    @DisplayName("开工后转在制、件完工累加工序完成数并流转到下一道")
    void startAndFinishFlowsThroughOperations() {
        unitService.generateForWorkOrder(workOrderId);
        String unitNo = firstUnitNo();

        Map<String, Object> started = unitService.start(unitNo, "张师傅");
        assertEquals("IN_PROGRESS", started.get("status"));
        assertEquals(Boolean.FALSE, started.get("canStart"));
        assertEquals(Boolean.TRUE, started.get("canFinish"));

        Map<String, Object> finished = unitService.finish(
                unitNo, new WorkOrderUnitService.FinishRequest("张师傅", true, null));
        assertEquals("PENDING", finished.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) finished.get("currentOperation");
        assertEquals(2, ((Number) current.get("seq")).intValue());
        assertEquals(0, opDone(op1Id).compareTo(BigDecimal.ONE));

        Integer reportRows = jdbc.queryForObject("""
                select count(*) from work_report where work_order_id = ? and remark = ?
                """, Integer.class, workOrderId, "SN:" + unitNo);
        assertEquals(1, reportRows);

        unitService.start(unitNo, "李师傅");
        Map<String, Object> done = unitService.finish(
                unitNo, new WorkOrderUnitService.FinishRequest("李师傅", true, null));
        assertEquals("FINISHED", done.get("status"));
        assertEquals(0, opDone(op2Id).compareTo(BigDecimal.ONE));
    }

    @Test
    @DisplayName("全部件完工后工单自动置为完工")
    void finishesWorkOrderWhenAllUnitsDone() {
        unitService.generateForWorkOrder(workOrderId);
        List<String> unitNos = jdbc.queryForList("""
                select unit_no from work_order_unit where work_order_id = ? order by seq_no
                """, String.class, workOrderId);
        for (String unitNo : unitNos) {
            unitService.start(unitNo, "王师傅");
            unitService.finish(unitNo, new WorkOrderUnitService.FinishRequest("王师傅", true, null));
            unitService.start(unitNo, "王师傅");
            unitService.finish(unitNo, new WorkOrderUnitService.FinishRequest("王师傅", true, null));
        }
        assertEquals("FINISHED", jdbc.queryForObject(
                "select status from work_order where id = ?", String.class, workOrderId));
        assertEquals(0, jdbc.queryForObject(
                "select done_qty from work_order where id = ?", BigDecimal.class, workOrderId)
                .compareTo(new BigDecimal("3")));
        Map<String, Object> summary = unitService.listByWorkOrder(workOrderId);
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) summary.get("summary");
        assertEquals(3L, ((Number) stats.get("finished")).longValue());
    }

    @Test
    @DisplayName("不合格完工：件报废且不计合格数")
    void scrapOnUnqualifiedFinish() {
        unitService.generateForWorkOrder(workOrderId);
        String unitNo = firstUnitNo();
        unitService.start(unitNo, "赵师傅");
        Map<String, Object> result = unitService.finish(
                unitNo, new WorkOrderUnitService.FinishRequest("赵师傅", false, "孔位偏移"));

        assertEquals("SCRAPPED", result.get("status"));
        assertEquals(0, opDone(op1Id).compareTo(BigDecimal.ONE));
        assertEquals(Boolean.FALSE, result.get("canScrap"));
        assertThrows(BizException.class, () -> unitService.start(unitNo, "赵师傅"));
    }

    @Test
    @DisplayName("报废必须填原因，报废不动工序完成数")
    void scrapNeedsReason() {
        unitService.generateForWorkOrder(workOrderId);
        String unitNo = firstUnitNo();

        BizException error = assertThrows(BizException.class, () -> unitService.scrap(unitNo, "  ", "王师傅"));
        assertEquals(1002, error.getCode());

        Map<String, Object> scrapped = unitService.scrap(unitNo, "料废无法返修", "王师傅");
        assertEquals("SCRAPPED", scrapped.get("status"));
        assertEquals(0, opDone(op1Id).signum());
        Integer logs = jdbc.queryForObject("""
                select count(*) from work_order_unit_log where action = 'SCRAP' and remark = '料废无法返修'
                """, Integer.class);
        assertEquals(1, logs);
    }

    @Test
    @DisplayName("单件工单禁止按数量报工，避免与件流转重复计数")
    void rejectsQuantityReport() {
        unitService.generateForWorkOrder(workOrderId);
        ProductionService.ReportRequest request = new ProductionService.ReportRequest(
                op1Id, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "张师傅", null, null, null);
        BizException error = assertThrows(BizException.class, () -> productionService.report(workOrderId, request));
        assertEquals(2023, error.getCode());
        assertEquals(0, opDone(op1Id).signum());
    }

    @Test
    @DisplayName("未知件码返回 2022")
    void unknownUnitRejected() {
        BizException error = assertThrows(BizException.class, () -> unitService.resolve(workNo + "-999"));
        assertEquals(2022, error.getCode());
    }
}
