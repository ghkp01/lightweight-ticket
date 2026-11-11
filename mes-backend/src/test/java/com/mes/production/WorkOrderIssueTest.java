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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产领料单测试：单据生成、指定仓库、超领拦截、非 BOM 物料拦截、整批回滚、替代料单独记行。
 * 使用独立的 mes_test 库，测试事务回滚，不污染演示数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkOrderIssueTest {

    @Autowired
    private ProductionService productionService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbc;

    private long materialId;
    private long substituteId;
    private long otherMaterialId;
    private long workOrderId;
    private long rawWarehouse;
    private long secondWarehouse;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        long productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 0) returning id
                """, Long.class, "IP" + suffix, "领料测试成品" + suffix);
        materialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "IM" + suffix, "测试主料" + suffix);
        substituteId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "IS" + suffix, "测试替代料" + suffix);
        otherMaterialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "IO" + suffix, "非 BOM 物料" + suffix);

        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'RELEASED') returning id
                """, Long.class, "WOT" + suffix, productId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, operation_seq, source)
                values (?, ?, 10, 1, 'STANDARD')
                """, workOrderId, materialId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty,
                                            operation_seq, substitute_for, source)
                values (?, ?, 0, 0, 1, ?, 'SUBSTITUTE')
                """, workOrderId, substituteId, materialId);

        rawWarehouse = inventoryService.warehouseOfType("RAW");
        setStock(materialId, rawWarehouse, new BigDecimal("10"));
        setStock(substituteId, rawWarehouse, new BigDecimal("5"));
        setStock(otherMaterialId, rawWarehouse, new BigDecimal("10"));

        secondWarehouse = jdbc.queryForObject("""
                insert into warehouse (code, name, type) values (?, ?, 'RAW') returning id
                """, Long.class, "WH" + suffix, "线边仓" + suffix);
        setStock(materialId, secondWarehouse, new BigDecimal("3"));
    }

    private void setStock(long productId, long warehouseId, BigDecimal qty) {
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, ?)
                on conflict (product_id, warehouse_id) do update set qty = excluded.qty
                """, productId, warehouseId, qty);
    }

    private BigDecimal stock(long productId, long warehouseId) {
        return jdbc.queryForObject(
                "select qty from inventory where product_id = ? and warehouse_id = ?",
                BigDecimal.class, productId, warehouseId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private WorkOrderIssueService.IssueBatchRequest request(long warehouseId, String issuer,
                                                           List<WorkOrderIssueService.IssueLine> items) {
        return new WorkOrderIssueService.IssueBatchRequest(warehouseId, issuer, "装配一组", "PC", "测试领料", items);
    }

    private WorkOrderIssueService.IssueLine line(long productId, String qty) {
        return new WorkOrderIssueService.IssueLine(productId, new BigDecimal(qty));
    }

    @Test
    @DisplayName("批量领料生成领料单、明细与回指流水")
    void issueBatchCreatesIssueRecord() {
        Map<String, Object> result = productionService.issueBatch(
                workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "4"))));

        String issueNo = String.valueOf(result.get("issueNo"));
        assertNotNull(issueNo);
        assertTrue(issueNo.startsWith("PI"), issueNo);
        assertEquals(1, count("select count(*) from production_issue where issue_no = ?", issueNo));

        Map<String, Object> issue = jdbc.queryForMap("""
                select issuer, team, source, total_qty, warehouse_id, operator
                from production_issue where issue_no = ?
                """, issueNo);
        assertEquals("张三", issue.get("issuer"));
        assertEquals("装配一组", issue.get("team"));
        assertEquals("PC", issue.get("source"));
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) issue.get("total_qty")));
        assertEquals(rawWarehouse, ((Number) issue.get("warehouse_id")).longValue());

        assertEquals(1, count("""
                select count(*) from production_issue_item it
                join production_issue i on i.id = it.issue_id
                where i.issue_no = ?
                """, issueNo));
        assertEquals(1, count("""
                select count(*) from stock_txn
                where issue_id = (select id from production_issue where issue_no = ?)
                """, issueNo));
        assertEquals(0, new BigDecimal("6").compareTo(stock(materialId, rawWarehouse)));
        assertEquals("IN_PROGRESS",
                jdbc.queryForObject("select status from work_order where id = ?", String.class, workOrderId));
    }

    @Test
    @DisplayName("领料按指定仓库扣减，不动默认原料仓")
    void respectsSelectedWarehouse() {
        productionService.issueBatch(workOrderId, request(secondWarehouse, "李四", List.of(line(materialId, "3"))));

        assertEquals(0, new BigDecimal("0").compareTo(stock(materialId, secondWarehouse)));
        assertEquals(0, new BigDecimal("10").compareTo(stock(materialId, rawWarehouse)));
    }

    @Test
    @DisplayName("领料数量超过剩余需求被拒绝")
    void rejectsOverIssue() {
        BizException ex = assertThrows(BizException.class, () -> productionService.issueBatch(
                workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "11")))));
        assertTrue(ex.getMessage().contains("超过剩余需求"), ex.getMessage());
    }

    @Test
    @DisplayName("非 BOM 物料被拒绝并提示走出库单")
    void rejectsMaterialOutsideBom() {
        BizException ex = assertThrows(BizException.class, () -> productionService.issueBatch(
                workOrderId, request(rawWarehouse, "张三", List.of(line(otherMaterialId, "1")))));
        assertTrue(ex.getMessage().contains("出库管理"), ex.getMessage());
        assertEquals(0, count("select count(*) from production_issue where work_order_id = ?", workOrderId));
    }

    @Test
    @DisplayName("同一批里任一行失败，整批不落账")
    void rollsBackWholeBatch() {
        // 两行同主料合并后 12 超过需求 10，必须整批回滚
        BizException ex = assertThrows(BizException.class, () -> productionService.issueBatch(
                workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "4"), line(materialId, "8")))));
        assertTrue(ex.getMessage().contains("超过剩余需求"), ex.getMessage());
        assertEquals(0, count("select count(*) from production_issue where work_order_id = ?", workOrderId));
        assertEquals(0, count("select count(*) from stock_txn where ref_no = (select work_no from work_order where id = ?)",
                workOrderId));
        assertEquals(0, new BigDecimal("10").compareTo(stock(materialId, rawWarehouse)));
    }

    @Test
    @DisplayName("主料不足时替代料单独记行并单独累加")
    void recordsSubstituteAsSeparateLine() {
        setStock(materialId, rawWarehouse, BigDecimal.ZERO);
        Map<String, Object> result = productionService.issueBatch(
                workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "4"))));

        String issueNo = String.valueOf(result.get("issueNo"));
        List<Map<String, Object>> items = jdbc.queryForList("""
                select it.material_id as "materialId", it.qty, it.alternative_for as "alternativeFor"
                from production_issue_item it
                join production_issue i on i.id = it.issue_id
                where i.issue_no = ? order by it.id
                """, issueNo);
        assertEquals(1, items.size());
        assertEquals(substituteId, ((Number) items.get(0).get("materialId")).longValue());
        assertEquals(materialId, ((Number) items.get(0).get("alternativeFor")).longValue());

        Map<String, Object> mainLine = jdbc.queryForMap("""
                select issued_qty, sub_issued_qty from work_order_bom
                where work_order_id = ? and material_id = ?
                """, workOrderId, materialId);
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) mainLine.get("issued_qty")));
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) mainLine.get("sub_issued_qty")));

        // 替代料自身行也累计消耗，但主料实领 = 已领 - 替代补量
        assertEquals(0, new BigDecimal("1").compareTo(stock(substituteId, rawWarehouse)));
    }

    @Test
    @DisplayName("领料单可在工单详情按时间倒序列出")
    void listsIssuesOfWorkOrder() {
        productionService.issueBatch(workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "2"))));
        productionService.issueBatch(workOrderId, request(rawWarehouse, "李四", List.of(line(materialId, "3"))));

        List<Map<String, Object>> issues = productionService.issues(workOrderId);
        assertEquals(2, issues.size());
        assertEquals("李四", issues.get(0).get("issuer"));
        assertEquals("张三", issues.get(1).get("issuer"));
        assertTrue(((Number) issues.get(0).get("itemCount")).intValue() >= 1);
    }

    private WorkOrderIssueService.ReturnRequest returnRequest(Long warehouseId, String returner,
                                                              List<WorkOrderIssueService.ReturnLine> items) {
        return new WorkOrderIssueService.ReturnRequest(warehouseId, returner, "装配一组", "PC", "领多了", items);
    }

    private WorkOrderIssueService.ReturnLine returnLine(long productId, String qty) {
        return new WorkOrderIssueService.ReturnLine(productId, new BigDecimal(qty), null);
    }

    @Test
    @DisplayName("退料生成退料单、回库、回写已领量")
    void returnCreatesDocumentAndRestoresStock() {
        productionService.issueBatch(workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "5"))));
        assertEquals(0, new BigDecimal("5").compareTo(stock(materialId, rawWarehouse)));

        Map<String, Object> result = productionService.returnMaterial(workOrderId,
                returnRequest(null, "王五", List.of(returnLine(materialId, "2"))));

        String returnNo = String.valueOf(result.get("returnNo"));
        assertTrue(returnNo.startsWith("MR"), returnNo);
        Map<String, Object> doc = jdbc.queryForMap("""
                select warehouse_id, returner, total_qty, reason from production_return where return_no = ?
                """, returnNo);
        assertEquals("王五", doc.get("returner"));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) doc.get("total_qty")));
        assertEquals(rawWarehouse, ((Number) doc.get("warehouse_id")).longValue());

        assertEquals(1, count("""
                select count(*) from stock_txn
                where return_id = (select id from production_return where return_no = ?)
                """, returnNo));
        assertEquals(0, new BigDecimal("7").compareTo(stock(materialId, rawWarehouse)));
        assertEquals(0, new BigDecimal("3").compareTo(jdbc.queryForObject("""
                select issued_qty from work_order_bom where work_order_id = ? and material_id = ?
                """, BigDecimal.class, workOrderId, materialId)));
        assertEquals(1, productionService.returns(workOrderId).size());
    }

    @Test
    @DisplayName("退料回到原领料仓库，不动默认原料仓")
    void returnGoesBackToOriginWarehouse() {
        productionService.issueBatch(workOrderId, request(secondWarehouse, "李四", List.of(line(materialId, "2"))));
        assertEquals(0, new BigDecimal("1").compareTo(stock(materialId, secondWarehouse)));

        productionService.returnMaterial(workOrderId,
                returnRequest(null, "李四", List.of(returnLine(materialId, "2"))));

        assertEquals(0, new BigDecimal("3").compareTo(stock(materialId, secondWarehouse)));
        assertEquals(0, new BigDecimal("10").compareTo(stock(materialId, rawWarehouse)));
        assertEquals(secondWarehouse, ((Number) productionService.returns(workOrderId).get(0)
                .get("warehouseId")).longValue());
    }

    @Test
    @DisplayName("退料沿用原领料批次，批次库存重新累加")
    void returnKeepsOriginalBatch() {
        productionService.issueBatch(workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "4"))));
        String issueBatch = jdbc.queryForObject("""
                select batch_no from stock_txn
                where txn_type = 'ISSUE' and ref_no = (select work_no from work_order where id = ?)
                order by id desc limit 1
                """, String.class, workOrderId);
        assertNotNull(issueBatch);
        BigDecimal before = jdbc.queryForObject("""
                select qty from inventory_batch where product_id = ? and warehouse_id = ? and batch_no = ?
                """, BigDecimal.class, materialId, rawWarehouse, issueBatch);

        productionService.returnMaterial(workOrderId,
                returnRequest(null, "张三", List.of(returnLine(materialId, "4"))));

        String returnBatch = jdbc.queryForObject("""
                select batch_no from stock_txn
                where txn_type = 'RETURN_MAT' and ref_no = (select work_no from work_order where id = ?)
                order by id desc limit 1
                """, String.class, workOrderId);
        assertEquals(issueBatch, returnBatch);
        BigDecimal after = jdbc.queryForObject("""
                select qty from inventory_batch where product_id = ? and warehouse_id = ? and batch_no = ?
                """, BigDecimal.class, materialId, rawWarehouse, issueBatch);
        assertEquals(0, before.add(new BigDecimal("4")).compareTo(after));
    }

    @Test
    @DisplayName("退料超过可退数量被拒绝")
    void rejectsOverReturn() {
        productionService.issueBatch(workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "4"))));
        BizException ex = assertThrows(BizException.class, () -> productionService.returnMaterial(workOrderId,
                returnRequest(null, "张三", List.of(returnLine(materialId, "5")))));
        assertTrue(ex.getMessage().contains("可退数量"), ex.getMessage());
        assertEquals(0, new BigDecimal("6").compareTo(stock(materialId, rawWarehouse)));
        assertEquals(0, count("select count(*) from production_return where work_order_id = ?", workOrderId));
    }

    @Test
    @DisplayName("未领料不能退料")
    void rejectsReturnWithoutIssue() {
        BizException ex = assertThrows(BizException.class, () -> productionService.returnMaterial(workOrderId,
                returnRequest(null, "张三", List.of(returnLine(materialId, "1")))));
        assertEquals(2012, ex.getCode());
    }

    @Test
    @DisplayName("可退明细给出超领量，退完后不再可退")
    void returnableLinesShowOverIssue() {
        productionService.issueBatch(workOrderId, request(rawWarehouse, "张三", List.of(line(materialId, "4"))));
        // 把需求调小到 2，已领 4 就变成超领 2
        jdbc.update("update work_order_bom set plan_qty = 2 where work_order_id = ? and material_id = ?",
                workOrderId, materialId);

        List<Map<String, Object>> lines = productionService.returnableLines(workOrderId);
        Map<String, Object> line = lines.stream()
                .filter(l -> materialId == ((Number) l.get("materialId")).longValue())
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) line.get("returnableQty")));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) line.get("suggestReturnQty")));

        productionService.returnMaterial(workOrderId,
                returnRequest(null, "张三", List.of(returnLine(materialId, "4"))));
        Map<String, Object> after = productionService.returnableLines(workOrderId).stream()
                .filter(l -> materialId == ((Number) l.get("materialId")).longValue())
                .findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) after.get("returnableQty")));
    }
}
