package com.mes.production;

import com.mes.common.BizException;
import com.mes.inventory.InventoryService;
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
 * 生产核心规则测试：工序防差错、不良扣减、领料上限、非标 BOM、替代料。
 * 使用独立的 mes_test 库，测试事务回滚，不污染演示数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductionRulesTest {

    @Autowired
    private ProductionService productionService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbc;

    private long materialId;
    private long substituteId;
    private long secondMaterialId;
    private long extraMaterialId;
    private long workOrderId;
    private long firstOperationId;
    private long secondOperationId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        long productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 0) returning id
                """, Long.class, "T" + suffix, "测试成品" + suffix);
        materialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "M" + suffix, "测试主料" + suffix);
        substituteId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "S" + suffix, "测试替代料" + suffix);
        secondMaterialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "N" + suffix, "装配辅料" + suffix);
        extraMaterialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "E" + suffix, "非标新增料" + suffix);

        Long bomId = jdbc.queryForObject("""
                insert into bom (bom_no, product_id, version, status, base_qty, effective_from)
                values (?, ?, 'V1.0', 'ACTIVE', 1, current_date) returning id
                """, Long.class, "BOMT" + suffix, productId);
        jdbc.update("""
                insert into bom_item (bom_id, material_id, qty, operation_seq, substitute_material_id)
                values (?, ?, 1, 1, ?)
                """, bomId, materialId, substituteId);

        Long cut = jdbc.queryForObject("select id from process where code = 'CUT'", Long.class);
        Long assy = jdbc.queryForObject("select id from process where code = 'ASSY'", Long.class);
        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'RELEASED') returning id
                """, Long.class, "WOT" + suffix, productId);
        firstOperationId = jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty,
                                                  standard_minutes, unit_price, process_id)
                values (?, 1, '下料', 10, 10, 1.20, ?) returning id
                """, Long.class, workOrderId, cut);
        secondOperationId = jdbc.queryForObject("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty,
                                                  standard_minutes, unit_price, process_id)
                values (?, 2, '装配', 10, 10, 2.00, ?) returning id
                """, Long.class, workOrderId, assy);

        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, operation_seq, source)
                values (?, ?, 10, 1, 'STANDARD')
                """, workOrderId, materialId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty,
                                            operation_seq, substitute_for, source)
                values (?, ?, 0, 0, 1, ?, 'SUBSTITUTE')
                """, workOrderId, substituteId, materialId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, operation_seq, source)
                values (?, ?, 5, 2, 'STANDARD')
                """, workOrderId, secondMaterialId);

        long rawWarehouse = inventoryService.warehouseOfType("RAW");
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, 0)
                on conflict (product_id, warehouse_id) do update set qty = 0
                """, materialId, rawWarehouse);
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, 10)
                on conflict (product_id, warehouse_id) do update set qty = 10
                """, substituteId, rawWarehouse);
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, 10)
                on conflict (product_id, warehouse_id) do update set qty = 10
                """, secondMaterialId, rawWarehouse);
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, 10)
                on conflict (product_id, warehouse_id) do update set qty = 10
                """, extraMaterialId, rawWarehouse);
    }

    private ProductionService.ReportRequest report(long operationId, int qty, int qualified, int defect) {
        return report(operationId, qty, qualified, defect, null);
    }

    private ProductionService.ReportRequest report(long operationId, int qty, int qualified, int defect,
                                                   String inspectionResult) {
        return new ProductionService.ReportRequest(operationId, BigDecimal.valueOf(qty),
                BigDecimal.valueOf(qualified), BigDecimal.valueOf(defect), "测试工", null,
                inspectionResult, null);
    }

    @Test
    @DisplayName("前道未完工时后道不能报工")
    void rejectReportBeforePreviousOperationDone() {
        BizException ex = assertThrows(BizException.class,
                () -> productionService.report(workOrderId, report(secondOperationId, 1, 1, 0)));
        assertTrue(ex.getMessage().contains("前道"));
    }

    @Test
    @DisplayName("本道不良自动扣减后道计划")
    void deductDefectFromNextOperation() {
        productionService.report(workOrderId, report(firstOperationId, 10, 8, 2));
        BigDecimal nextPlan = jdbc.queryForObject(
                "select plan_qty from work_order_operation where id = ?", BigDecimal.class, secondOperationId);
        assertEquals(0, nextPlan.compareTo(new BigDecimal("8")));
    }

    @Test
    @DisplayName("报工数量不能超过剩余数量")
    void rejectOverReport() {
        productionService.report(workOrderId, report(firstOperationId, 4, 4, 0));
        BizException ex = assertThrows(BizException.class,
                () -> productionService.report(workOrderId, report(firstOperationId, 10, 10, 0)));
        assertEquals(2010, ex.getCode());
    }

    @Test
    @DisplayName("非标调整必须填写原因")
    void requireReasonForCustomBom() {
        BizException ex = assertThrows(BizException.class, () -> productionService.saveBomLine(workOrderId,
                new ProductionService.BomLineRequest(materialId, new BigDecimal("12"), 1, null, " ")));
        assertEquals(1002, ex.getCode());
    }

    @Test
    @DisplayName("主料不足时自动用替代料并以工单 BOM 记录")
    void issueWithSubstituteMaterial() {
        productionService.issue(workOrderId);

        BigDecimal mainIssued = jdbc.queryForObject("""
                select issued_qty from work_order_bom where work_order_id = ? and material_id = ?
                """, BigDecimal.class, workOrderId, materialId);
        BigDecimal subIssued = jdbc.queryForObject("""
                select issued_qty from work_order_bom where work_order_id = ? and material_id = ?
                """, BigDecimal.class, workOrderId, substituteId);
        assertEquals(0, mainIssued.compareTo(new BigDecimal("10")));
        assertEquals(0, subIssued.compareTo(new BigDecimal("10")));

        BigDecimal subTxn = jdbc.queryForObject("""
                select coalesce(-sum(t.qty), 0) from stock_txn t
                where t.txn_type = 'ISSUE' and t.product_id = ?
                  and t.ref_no = (select work_no from work_order where id = ?)
                """, BigDecimal.class, substituteId, workOrderId);
        assertEquals(0, subTxn.compareTo(new BigDecimal("10")));

        List<Map<String, Object>> remarks = jdbc.queryForList("""
                select remark from stock_txn where txn_type = 'ISSUE' and product_id = ?
                """, substituteId);
        assertTrue(remarks.stream().anyMatch(row -> String.valueOf(row.get("remark")).contains("替代发料")));
    }

    @Test
    @DisplayName("已领料的工单 BOM 行不能删除")
    void rejectDeleteIssuedBomLine() {
        productionService.issue(workOrderId);
        Long bomId = jdbc.queryForObject("""
                select id from work_order_bom where work_order_id = ? and material_id = ?
                """, Long.class, workOrderId, materialId);
        BizException ex = assertThrows(BizException.class,
                () -> productionService.deleteBomLine(workOrderId, bomId, "测试删除"));
        assertEquals(2016, ex.getCode());
    }

    @Test
    @DisplayName("替代料行不能单独调整")
    void rejectAdjustSubstituteLine() {
        BizException ex = assertThrows(BizException.class, () -> productionService.saveBomLine(workOrderId,
                new ProductionService.BomLineRequest(substituteId, new BigDecimal("5"), 1, null, "调整替代料")));
        assertEquals(1002, ex.getCode());
    }

    @Test
    @DisplayName("按工序领料只领该工序的物料")
    void issueByOperationOnlyIssuesThatOperation() {
        productionService.issueByOperation(workOrderId, 1);
        assertEquals(0, issuedOf(materialId).compareTo(new BigDecimal("10")));
        assertEquals(0, issuedOf(secondMaterialId).compareTo(BigDecimal.ZERO));

        productionService.issueByOperation(workOrderId, 2);
        assertEquals(0, issuedOf(secondMaterialId).compareTo(new BigDecimal("5")));
    }

    private BigDecimal issuedOf(long materialId) {
        return jdbc.queryForObject("""
                select issued_qty from work_order_bom where work_order_id = ? and material_id = ?
                """, BigDecimal.class, workOrderId, materialId);
    }

    @Test
    @DisplayName("BOM 差异识别改量与新增")
    void bomDiffDetectsChangedAndAdded() {
        productionService.saveBomLine(workOrderId,
                new ProductionService.BomLineRequest(materialId, new BigDecimal("12"), 1, null, "客户要求加量"));
        productionService.saveBomLine(workOrderId,
                new ProductionService.BomLineRequest(extraMaterialId, new BigDecimal("3"), 2, null, "非标加料"));

        Map<String, Map<String, Object>> byMaterial = new java.util.HashMap<>();
        for (Map<String, Object> row : productionService.bomDiff(workOrderId)) {
            byMaterial.put(String.valueOf(row.get("materialId")), row);
        }
        Map<String, Object> changed = byMaterial.get(String.valueOf(materialId));
        Map<String, Object> added = byMaterial.get(String.valueOf(extraMaterialId));
        assertEquals("CHANGED", changed.get("changeType"));
        assertEquals(0, new BigDecimal(String.valueOf(changed.get("diffQty"))).compareTo(new BigDecimal("2")));
        assertEquals("ADDED", added.get("changeType"));
        assertEquals(0, new BigDecimal(String.valueOf(added.get("standardQty"))).signum());
        assertEquals(0, new BigDecimal(String.valueOf(added.get("currentQty"))).compareTo(new BigDecimal("3")));
    }
}
