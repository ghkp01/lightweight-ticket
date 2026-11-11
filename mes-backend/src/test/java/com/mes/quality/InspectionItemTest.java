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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检验项与抽样：模板按工序带出、必检项不能漏、任一项不合格整单不合格、抽检按接收数判定。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InspectionItemTest {

    @Autowired
    private com.mes.production.ProductionService productionService;

    @Autowired
    private QualityService qualityService;

    @Autowired
    private InspectionItemService itemService;

    @Autowired
    private SamplingService samplingService;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long workOrderId;
    private String workNo;
    private long cutProcessId;
    private long assyOperationId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 100) returning id
                """, Long.class, "QI" + suffix, "检验项测试产品" + suffix);

        cutProcessId = jdbc.queryForObject("select id from process where code = 'CUT'", Long.class);
        long assyProcessId = jdbc.queryForObject("select id from process where code = 'ASSY'", Long.class);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 1.0, 1.00)
                """, productId, cutProcessId);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 2, ?, 1.0, 1.00)
                """, productId, assyProcessId);

        workNo = "WO" + suffix;
        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 600, 'IN_PROGRESS') returning id
                """, Long.class, workNo, productId);
        productionService.createOperations(workOrderId, productId, new BigDecimal("600"));

        assyOperationId = jdbc.queryForObject("""
                select id from work_order_operation where work_order_id = ? and seq = 2
                """, Long.class, workOrderId);

        itemService.save(null, cutProcessId, List.of(
                new InspectionItemService.ItemRequest(null, "T-LEN", "下料长度", "180±0.5 mm", "卡尺",
                        "MEASURE", new BigDecimal("179.5"), new BigDecimal("180.5"), "mm", true, 1, true),
                new InspectionItemService.ItemRequest(null, "T-BURR", "切口毛刺", "无可见毛刺", "目视",
                        "VISUAL", null, null, null, true, 2, true)
        ));
    }

    private QualityService.InspectionRequest request(BigDecimal inspectQty, BigDecimal defectQty,
                                                     Integer operationSeq, String samplePlan, BigDecimal aql,
                                                     List<InspectionItemService.ItemResultRequest> items) {
        return new QualityService.InspectionRequest("PROCESS", "WORK_ORDER", workNo, workOrderId, productId,
                inspectQty, defectQty, "qc", "", null, null, null, operationSeq, items, samplePlan, aql);
    }

    private List<InspectionItemService.ItemResultRequest> passItems() {
        List<Map<String, Object>> templates = itemService.list(null, cutProcessId);
        return templates.stream().map(t -> new InspectionItemService.ItemResultRequest(
                ((Number) t.get("id")).longValue(), String.valueOf(t.get("itemName")),
                (String) t.get("standard"), new BigDecimal("5"), BigDecimal.ZERO, "180.2", "PASS", null
        )).toList();
    }

    @Test
    @DisplayName("检验项按工序维护，全量覆盖后按顺序返回")
    void savesItemsForProcess() {
        List<Map<String, Object>> items = itemService.list(null, cutProcessId);
        assertEquals(2, items.size());
        assertEquals("下料长度", items.get(0).get("itemName"));
        assertEquals("MEASURE", items.get(0).get("valueType"));

        itemService.save(null, cutProcessId, List.of(
                new InspectionItemService.ItemRequest(null, "T-ONLY", "只留一项", "标准", "目视",
                        "VISUAL", null, null, null, true, 1, true)
        ));
        List<Map<String, Object>> after = itemService.list(null, cutProcessId);
        assertEquals(1, after.size());
        assertEquals("只留一项", after.get(0).get("itemName"));
    }

    @Test
    @DisplayName("工序配了检验项，漏填必检项直接拒绝")
    void rejectsMissingItems() {
        BizException error = assertThrows(BizException.class, () ->
                qualityService.create(request(new BigDecimal("10"), BigDecimal.ZERO, 1, null, null, null)));
        assertEquals(1002, error.getCode());
        assertTrue(error.getMessage().contains("必检项"));
    }

    @Test
    @DisplayName("逐项合格：整单合格并落检验项明细")
    void storesItemResults() {
        Map<String, Object> response = qualityService.create(
                request(new BigDecimal("10"), null, 1, null, null, passItems()));

        assertEquals("PASS", response.get("result"));
        assertEquals(2, response.get("itemCount"));
        List<Map<String, Object>> results = itemService.resultsOf(((Number) response.get("id")).longValue());
        assertEquals(2, results.size());
        assertEquals("PASS", results.get(0).get("result"));
        assertEquals(0, new BigDecimal("180.2").compareTo(new BigDecimal(String.valueOf(results.get(0).get("measured")))));
    }

    @Test
    @DisplayName("任一项判不合格：整单不合格，不良数取明细合计")
    void itemFailMakesInspectionFail() {
        List<InspectionItemService.ItemResultRequest> items = new java.util.ArrayList<>(passItems());
        items.set(1, new InspectionItemService.ItemResultRequest(
                items.get(1).itemId(), items.get(1).itemName(), items.get(1).standard(),
                new BigDecimal("5"), new BigDecimal("2"), null, "FAIL", "有毛刺"));

        Map<String, Object> response = qualityService.create(
                request(new BigDecimal("10"), null, 1, null, null, items));

        assertEquals("FAIL", response.get("result"));
        Map<String, Object> row = jdbc.queryForMap(
                "select defect_qty from inspection where id = ?", response.get("id"));
        assertEquals(0, new BigDecimal("2").compareTo(new BigDecimal(String.valueOf(row.get("defect_qty")))));

        List<Map<String, Object>> top = qualityService.itemDefectTop(30);
        assertTrue(top.stream().anyMatch(t -> "切口毛刺".equals(t.get("itemName"))), top.toString());
    }

    @Test
    @DisplayName("抽检按接收数判定：批量 500、AQL 2.5 时 Ac=1")
    void samplingUsesAcceptanceNumber() {
        SamplingService.SamplePlan plan = samplingService.plan(new BigDecimal("500"), "SAMPLE", new BigDecimal("2.5"));
        assertEquals("SAMPLE", plan.plan());
        assertEquals(0, new BigDecimal("50").compareTo(plan.sampleQty()));
        assertEquals(1, plan.acceptQty());
        assertEquals(2, plan.rejectQty());

        // 不良 1 件：在接收数内，判合格
        Map<String, Object> pass = qualityService.create(
                request(new BigDecimal("500"), new BigDecimal("1"), 2, "SAMPLE", new BigDecimal("2.5"), null));
        assertEquals("PASS", pass.get("result"));
        assertEquals(1, ((Number) pass.get("acceptQty")).intValue());

        // 不良 2 件：达到拒收数，判不合格
        Map<String, Object> fail = qualityService.create(
                request(new BigDecimal("500"), new BigDecimal("2"), 2, "SAMPLE", new BigDecimal("2.5"), null));
        assertEquals("FAIL", fail.get("result"));
    }

    @Test
    @DisplayName("全检默认不良数大于 0 即不合格")
    void fullInspectionKeepsStrictRule() {
        Map<String, Object> response = qualityService.create(
                request(new BigDecimal("10"), new BigDecimal("1"), 2, "FULL", null, null));
        assertEquals("FAIL", response.get("result"));
        assertEquals("FULL", response.get("samplePlan"));
    }
}
