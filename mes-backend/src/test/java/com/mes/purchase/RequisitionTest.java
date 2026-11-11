package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.purchase.api.RequisitionDraft;
import com.mes.purchase.api.RequisitionLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采购申请：提交、审批阈值、部分转采购单、采购单取消回退、状态与删除护栏。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RequisitionTest {

    @Autowired
    private RequisitionService requisitionService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private JdbcTemplate jdbc;

    private long supplierId;
    private long warehouseId;

    @BeforeEach
    void setUp() {
        supplierId = jdbc.queryForObject("select id from supplier where code = 'S001'", Long.class);
        warehouseId = jdbc.queryForObject("select id from warehouse where code = 'WH-RAW'", Long.class);
    }

    @Test
    @DisplayName("提交 → 审批 → 全部转采购单：状态已转单，采购单带申请来源")
    void submitApproveAndConvertAll() {
        long id = create("STOCK", "标准件补库", List.of(
                item(3L, "10", "5"), item(5L, "20", "0.8")));
        assertEquals("DRAFT", requisitionService.detail(id).get("status"));

        requisitionService.submit(id);
        assertEquals("SUBMITTED", requisitionService.detail(id).get("status"));

        requisitionService.approve(id);
        Map<String, Object> approved = requisitionService.detail(id);
        assertEquals("APPROVED", approved.get("status"));

        List<Map<String, Object>> items = (List<Map<String, Object>>) approved.get("items");
        Map<String, Object> result = requisitionService.toOrder(id, new RequisitionService.ConvertRequest(
                supplierId, warehouseId, LocalDate.now().plusDays(3), false, null,
                List.of(new RequisitionService.ConvertLine(idOf(items.get(0)), new BigDecimal("10"), new BigDecimal("5")),
                        new RequisitionService.ConvertLine(idOf(items.get(1)), new BigDecimal("20"), new BigDecimal("0.8")))));

        assertNotNull(result.get("orderNo"));
        assertEquals("CONVERTED", requisitionService.detail(id).get("status"));
        Map<String, Object> order = purchaseOrderService.detail(((Number) result.get("orderId")).longValue());
        assertEquals(requisitionService.detail(id).get("reqNo"), order.get("requisitionNo"));
        assertEquals(0, new BigDecimal(String.valueOf(order.get("totalAmount"))).compareTo(new BigDecimal("66")));
    }

    @Test
    @DisplayName("超过审批阈值只有管理员能批，普通角色被拒")
    void highAmountNeedsAdmin() {
        long id = create("MANUAL", "设备备件", List.of(
                item(3L, "200", "45.5")));
        requisitionService.submit(id);
        BizException error = assertThrows(BizException.class, () -> requisitionService.approve(id));
        assertEquals(403, error.getCode());
        assertTrue(error.getMessage().contains("管理员"));
        assertEquals("SUBMITTED", requisitionService.detail(id).get("status"));
    }

    @Test
    @DisplayName("可以分次转单：先转一部分变部分转单，再转剩余变已转单")
    void partialConversion() {
        long id = create("STOCK", "分次转单", List.of(
                item(3L, "10", "5")));
        requisitionService.submit(id);
        requisitionService.approve(id);
        Map<String, Object> detail = requisitionService.detail(id);
        long itemId = idOf(((List<Map<String, Object>>) detail.get("items")).get(0));

        requisitionService.toOrder(id, new RequisitionService.ConvertRequest(
                supplierId, warehouseId, null, false, null,
                List.of(new RequisitionService.ConvertLine(itemId, new BigDecimal("4"), null))));
        Map<String, Object> partial = requisitionService.detail(id);
        assertEquals("PARTIAL", partial.get("status"));
        Map<String, Object> item = ((List<Map<String, Object>>) partial.get("items")).get(0);
        assertEquals(0, new BigDecimal(String.valueOf(item.get("convertedQty"))).compareTo(new BigDecimal("4")));
        assertEquals(0, new BigDecimal(String.valueOf(item.get("remainingQty"))).compareTo(new BigDecimal("6")));

        BizException tooMuch = assertThrows(BizException.class,
                () -> requisitionService.toOrder(id, new RequisitionService.ConvertRequest(
                        supplierId, warehouseId, null, false, null,
                        List.of(new RequisitionService.ConvertLine(itemId, new BigDecimal("7"), null)))));
        assertEquals(1002, tooMuch.getCode());

        requisitionService.toOrder(id, new RequisitionService.ConvertRequest(
                supplierId, warehouseId, null, false, null,
                List.of(new RequisitionService.ConvertLine(itemId, new BigDecimal("6"), null))));
        assertEquals("CONVERTED", requisitionService.detail(id).get("status"));
    }

    @Test
    @DisplayName("采购单取消后，已转数量退回申请，状态回到已批准")
    void cancelOrderReleasesRequisition() {
        long id = create("STOCK", "取消回退", List.of(
                item(3L, "8", "4")));
        requisitionService.submit(id);
        requisitionService.approve(id);
        long itemId = idOf(((List<Map<String, Object>>) requisitionService.detail(id).get("items")).get(0));
        Map<String, Object> result = requisitionService.toOrder(id, new RequisitionService.ConvertRequest(
                supplierId, warehouseId, null, false, null,
                List.of(new RequisitionService.ConvertLine(itemId, new BigDecimal("8"), null))));
        long orderId = ((Number) result.get("orderId")).longValue();
        assertEquals("CONVERTED", requisitionService.detail(id).get("status"));

        purchaseOrderService.cancel(orderId);

        Map<String, Object> after = requisitionService.detail(id);
        assertEquals("APPROVED", after.get("status"));
        Map<String, Object> item = ((List<Map<String, Object>>) after.get("items")).get(0);
        assertEquals(0, new BigDecimal(String.valueOf(item.get("convertedQty"))).signum());
    }

    @Test
    @DisplayName("已提交不能改、已转单不能取消、驳回必须填原因")
    void statusGuards() {
        long id = create("MANUAL", "状态护栏", List.of(
                item(3L, "2", "3")));
        requisitionService.submit(id);
        BizException editAfterSubmit = assertThrows(BizException.class,
                () -> requisitionService.update(id, request("MANUAL", "改用途", List.of(item(3L, "1", "3")))));
        assertEquals(2014, editAfterSubmit.getCode());

        requisitionService.approve(id);
        BizException rejectApproved = assertThrows(BizException.class,
                () -> requisitionService.reject(id, new RequisitionService.RejectRequest("不需要了")));
        assertEquals(2014, rejectApproved.getCode());

        long second = create("MANUAL", "驳回测试", List.of(
                item(3L, "1", "1")));
        requisitionService.submit(second);
        BizException emptyReason = assertThrows(BizException.class,
                () -> requisitionService.reject(second, new RequisitionService.RejectRequest("  ")));
        assertEquals(1002, emptyReason.getCode());
        requisitionService.reject(second, new RequisitionService.RejectRequest("预算不足"));
        assertEquals("REJECTED", requisitionService.detail(second).get("status"));
    }

    private RequisitionDraft request(String source, String purpose, List<RequisitionLine> items) {
        return new RequisitionDraft(source, "计划部", null,
                LocalDate.now().plusDays(3), purpose, "单元测试", items);
    }

    private RequisitionLine item(Long productId, String qty, String price) {
        return new RequisitionLine(productId, new BigDecimal(qty), new BigDecimal(price),
                supplierId, false, null);
    }

    /** 建单只需要 id，契约返回的单号在这里丢掉 */
    private long create(String source, String purpose, List<RequisitionLine> items) {
        return requisitionService.create(request(source, purpose, items)).id();
    }

    private static long idOf(Map<String, Object> item) {
        return ((Number) item.get("id")).longValue();
    }
}
