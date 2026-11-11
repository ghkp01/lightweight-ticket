package com.mes.quality;

import com.mes.common.BizException;
import com.mes.masterdata.DocumentService;
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
 * 质检形态与不良处置测试：工序内检验点拦截、不良台账、四种处置、退货返修开单。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QualityDefectTest {

    @Autowired
    private com.mes.production.ProductionService productionService;

    @Autowired
    private DefectService defectService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long workOrderId;
    private long cutOperationId;
    private long cutProcessId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 100) returning id
                """, Long.class, "QT" + suffix, "质检测试产品" + suffix);

        // 下料工序设为「工序内检验点」，装配保持不检验
        cutProcessId = jdbc.queryForObject("select id from process where code = 'CUT'", Long.class);
        Long assyProcessId = jdbc.queryForObject("select id from process where code = 'ASSY'", Long.class);
        jdbc.update("update process set inspection_mode = 'ATTACHED' where id = ?", cutProcessId);

        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 1.5, 1.20)
                """, productId, cutProcessId);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 2, ?, 2.0, 2.00)
                """, productId, assyProcessId);

        long rawMaterialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "QM" + suffix, "质检测试主料" + suffix);
        Long bomId = jdbc.queryForObject("""
                insert into bom (bom_no, product_id, version, status, base_qty, effective_from)
                values (?, ?, 'V1.0', 'ACTIVE', 1, current_date) returning id
                """, Long.class, "BOMQ" + suffix, productId);
        jdbc.update("""
                insert into bom_item (bom_id, material_id, qty, operation_seq)
                values (?, ?, 1, 1)
                """, bomId, rawMaterialId);

        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'RELEASED') returning id
                """, Long.class, "QWO" + suffix, productId);
        productionService.createOperations(workOrderId, productId, BigDecimal.TEN);
        productionService.snapshotBom(workOrderId, productId, BigDecimal.TEN);

        cutOperationId = jdbc.queryForObject("""
                select id from work_order_operation where work_order_id = ? and seq = 1
                """, Long.class, workOrderId);
    }

    private com.mes.production.ProductionService.ReportRequest report(
            BigDecimal qty, BigDecimal qualified, BigDecimal defect, String inspectionResult) {
        return new com.mes.production.ProductionService.ReportRequest(
                cutOperationId, qty, qualified, defect, "测试工", null, inspectionResult, null);
    }

    @Test
    @DisplayName("工序内检验点：报工必须给检验结论")
    void attachedOperationRequiresInspectionResult() {
        BizException ex = assertThrows(BizException.class,
                () -> productionService.report(workOrderId, report(
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, null)));
        assertEquals(2020, ex.getCode());
    }

    @Test
    @DisplayName("工序内检验点：判不合格必须填不良数量")
    void failRequiresDefectQty() {
        BizException ex = assertThrows(BizException.class,
                () -> productionService.report(workOrderId, report(
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, "FAIL")));
        assertEquals(2010, ex.getCode());
    }

    @Test
    @DisplayName("判不合格报工：生成过程检验记录与待处置不良")
    void failReportWritesInspectionAndDefect() {
        productionService.report(workOrderId, report(
                BigDecimal.TEN, new BigDecimal("8"), new BigDecimal("2"), "FAIL"));

        Integer processInspections = jdbc.queryForObject("""
                select count(*) from inspection where work_order_id = ? and type = 'PROCESS'
                """, Integer.class, workOrderId);
        assertEquals(1, processInspections);

        Map<String, Object> defect = jdbc.queryForMap("""
                select qty, source, status from defect_record where work_order_id = ?
                """, workOrderId);
        assertEquals(0, new BigDecimal(String.valueOf(defect.get("qty")))
                .compareTo(new BigDecimal("2")));
        assertEquals("REPORT", defect.get("source"));
        assertEquals("PENDING", defect.get("status"));
    }

    @Test
    @DisplayName("不良处置：让步接收把数量放行入库并结案")
    void concessionInboundAndClose() {
        productionService.report(workOrderId, report(
                BigDecimal.TEN, new BigDecimal("8"), new BigDecimal("2"), "FAIL"));
        long defectId = jdbc.queryForObject(
                "select id from defect_record where work_order_id = ?", Long.class, workOrderId);
        long finishedWarehouse = jdbc.queryForObject(
                "select id from warehouse where type = 'FINISHED' order by id limit 1", Long.class);
        BigDecimal before = jdbc.queryForObject("""
                select coalesce((select qty from inventory where product_id = ? and warehouse_id = ?), 0)
                """, BigDecimal.class, productId, finishedWarehouse);

        defectService.dispose(defectId, new DefectService.DisposeRequest("CONCESSION", "客户同意"));

        BigDecimal after = jdbc.queryForObject("""
                select coalesce((select qty from inventory where product_id = ? and warehouse_id = ?), 0)
                """, BigDecimal.class, productId, finishedWarehouse);
        assertEquals(0, after.subtract(before).compareTo(new BigDecimal("2")));

        Map<String, Object> row = jdbc.queryForMap("""
                select disposition, status from defect_record where id = ?
                """, defectId);
        assertEquals("CONCESSION", row.get("disposition"));
        assertEquals("DISPOSED", row.get("status"));

        BizException ex = assertThrows(BizException.class,
                () -> defectService.dispose(defectId,
                        new DefectService.DisposeRequest("SCRAP", null)));
        assertEquals(2019, ex.getCode());
    }

    @Test
    @DisplayName("不良处置：返修开出的工单要带工序与 BOM")
    void reworkCreatesWorkOrderWithOperationsAndBom() {
        productionService.report(workOrderId, report(
                BigDecimal.TEN, new BigDecimal("7"), new BigDecimal("3"), "FAIL"));
        long defectId = jdbc.queryForObject(
                "select id from defect_record where work_order_id = ?", Long.class, workOrderId);

        Map<String, Object> result = defectService.dispose(defectId,
                new DefectService.DisposeRequest("REWORK", "返修"));
        Long reworkId = (Long) result.get("reworkWorkOrderId");
        assertNotNull(reworkId);

        Map<String, Object> rework = jdbc.queryForMap("""
                select work_type, parent_work_order_id, plan_qty, status from work_order where id = ?
                """, reworkId);
        assertEquals("REWORK", rework.get("work_type"));
        assertEquals(0, new BigDecimal(String.valueOf(rework.get("plan_qty")))
                .compareTo(new BigDecimal("3")));

        Integer operations = jdbc.queryForObject("""
                select count(*) from work_order_operation where work_order_id = ?
                """, Integer.class, reworkId);
        assertTrue(operations != null && operations >= 2, "返修工单要有工序");

        Integer bom = jdbc.queryForObject("""
                select count(*) from work_order_bom where work_order_id = ?
                """, Integer.class, reworkId);
        assertTrue(bom != null && bom > 0, "返修工单要有 BOM 快照");
    }

    @Test
    @DisplayName("工单文档下发：按工序返回 SOP")
    void workOrderDocumentsGroupedByOperation() {
        long docId = jdbc.queryForObject("""
                insert into product_document (product_id, process_id, doc_type, title, version, content)
                values (?, ?, 'SOP', '质检测试作业指导书', 'V9.9', '1. 测试步骤')
                returning id
                """, Long.class, productId, cutProcessId);

        Map<String, Object> docs = documentService.forWorkOrder(workOrderId);
        List<Map<String, Object>> operations = (List<Map<String, Object>>) docs.get("operations");
        assertNotNull(operations);
        Map<String, Object> first = operations.get(0);
        List<Map<String, Object>> documents = (List<Map<String, Object>>) first.get("documents");
        assertEquals(1, documents.size());
        assertEquals(docId, ((Number) documents.get(0).get("id")).longValue());
        assertEquals("SOP", documents.get(0).get("docType"));
    }
}
