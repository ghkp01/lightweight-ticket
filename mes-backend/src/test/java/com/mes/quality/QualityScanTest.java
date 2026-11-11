package com.mes.quality;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 扫码检验：工序码带出的工序要落到检验单上，判不合格要能进不良台账并挂到该工序。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QualityScanTest {

    @Autowired
    private com.mes.production.ProductionService productionService;

    @Autowired
    private QualityService qualityService;

    @Autowired
    private InspectionItemService itemService;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long workOrderId;
    private String workNo;
    private long seq2OperationId;
    private long cutProcessId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 100) returning id
                """, Long.class, "QS" + suffix, "扫码检验产品" + suffix);

        long cutProcessId = jdbc.queryForObject("select id from process where code = 'CUT'", Long.class);
        this.cutProcessId = cutProcessId;
        long assyProcessId = jdbc.queryForObject("select id from process where code = 'ASSY'", Long.class);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 1.5, 1.20)
                """, productId, cutProcessId);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 2, ?, 2.0, 2.00)
                """, productId, assyProcessId);

        workNo = "QSWO" + suffix;
        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'IN_PROGRESS') returning id
                """, Long.class, workNo, productId);
        productionService.createOperations(workOrderId, productId, BigDecimal.TEN);
        productionService.snapshotBom(workOrderId, productId, BigDecimal.TEN);

        seq2OperationId = jdbc.queryForObject("""
                select id from work_order_operation where work_order_id = ? and seq = 2
                """, Long.class, workOrderId);
    }

    private QualityService.InspectionRequest request(String type, BigDecimal inspectQty, BigDecimal defectQty,
                                                     Long operationId, Integer operationSeq) {
        // 下料工序配了检验项，走它时必须逐项给结果
        List<InspectionItemService.ItemResultRequest> items = null;
        if (operationSeq != null && operationSeq == 1) {
            items = itemService.list(null, cutProcessId).stream()
                    .map(t -> new InspectionItemService.ItemResultRequest(
                            ((Number) t.get("id")).longValue(), String.valueOf(t.get("itemName")),
                            (String) t.get("standard"), inspectQty, BigDecimal.ZERO, null, "PASS", null))
                    .toList();
        }
        return new QualityService.InspectionRequest(type, "WORK_ORDER", workNo, workOrderId, productId,
                inspectQty, defectQty, "qc", "", null, null, operationId, operationSeq,
                items, null, null);
    }

    @Test
    @DisplayName("工序码扫码检验：按工序序号落库并回带工序")
    void processInspectionStoresOperation() {
        Map<String, Object> result = qualityService.create(
                request("PROCESS", new BigDecimal("10"), BigDecimal.ZERO, null, 2));

        assertEquals(2, ((Number) result.get("operationSeq")).intValue());
        Map<String, Object> row = jdbc.queryForMap("""
                select operation_id, operation_seq, result from inspection where id = ?
                """, result.get("id"));
        assertEquals(seq2OperationId, ((Number) row.get("operation_id")).longValue());
        assertEquals(2, ((Number) row.get("operation_seq")).intValue());
        assertEquals("PASS", row.get("result"));

        List<?> list = qualityService.list("PROCESS", null, workNo, 1, 10).records();
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) list.get(0);
        assertEquals(2, ((Number) first.get("operationSeq")).intValue());
        assertNotNull(first.get("operationName"));
    }

    @Test
    @DisplayName("工序不属于该工单：拒绝落库")
    void rejectsOperationOutsideWorkOrder() {
        BizException error = assertThrows(BizException.class, () ->
                qualityService.create(request("PROCESS", BigDecimal.TEN, BigDecimal.ZERO, null, 9)));
        assertEquals(1002, error.getCode());
    }

    @Test
    @DisplayName("工序检验不合格：不良台账挂到该工序")
    void processFailWritesDefectWithOperation() {
        qualityService.create(request("PROCESS", new BigDecimal("10"), new BigDecimal("2"), null, 2));

        List<Map<String, Object>> defects = jdbc.queryForList("""
                select defect_no, work_order_id, operation_id, product_id, qty, source, status,
                       reason
                from defect_record where work_order_id = ?
                """, workOrderId);
        assertEquals(1, defects.size());
        Map<String, Object> defect = defects.get(0);
        assertEquals("PROCESS_QC", defect.get("source"));
        assertEquals("PENDING", defect.get("status"));
        assertEquals(seq2OperationId, ((Number) defect.get("operation_id")).longValue());
        assertEquals(0, new BigDecimal("2").compareTo(new BigDecimal(String.valueOf(defect.get("qty")))));
        assertEquals("第 2 道工序检验不合格", defect.get("reason"));
    }

    @Test
    @DisplayName("工序检验合格：不产生不良记录")
    void processPassWritesNoDefect() {
        qualityService.create(request("PROCESS", BigDecimal.TEN, BigDecimal.ZERO, null, 1));

        Integer count = jdbc.queryForObject(
                "select count(*) from defect_record where work_order_id = ?", Integer.class, workOrderId);
        assertEquals(0, count);

        Map<String, Object> row = jdbc.queryForMap("""
                select operation_id, operation_seq from inspection
                where work_order_id = ? order by id desc limit 1
                """, workOrderId);
        assertEquals(1, ((Number) row.get("operation_seq")).intValue());
    }

    @Test
    @DisplayName("没带工序的普通过程检验：工序字段为空，不报错")
    void manualProcessInspectionKeepsOperationEmpty() {
        Map<String, Object> result = qualityService.create(
                request("PROCESS", BigDecimal.TEN, BigDecimal.ZERO, null, null));

        assertNull(result.get("operationSeq"));
        Map<String, Object> row = jdbc.queryForMap("""
                select operation_id, operation_seq from inspection where id = ?
                """, result.get("id"));
        assertNull(row.get("operation_id"));
        assertNull(row.get("operation_seq"));
    }
}
