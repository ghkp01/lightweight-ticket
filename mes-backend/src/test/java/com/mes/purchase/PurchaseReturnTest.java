package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.warehouse.InboundOrderService;
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
 * 入库来源收紧与采购退货：手工采购入库被拒、无单入库需原因并留痕、
 * 采购收货入库单不可改、退货扣库回退实收冲暂估、已开票禁止退货。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PurchaseReturnTest {

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private InboundOrderService inboundOrderService;

    @Autowired
    private PurchaseReturnService purchaseReturnService;

    @Autowired
    private ApService apService;

    @Autowired
    private JdbcTemplate jdbc;

    private long supplierId;
    private long warehouseId;
    private long productId;
    private long orderId;
    private long orderItemId;
    private long inboundId;
    private long inboundItemId;

    @BeforeEach
    void setUp() {
        supplierId = jdbc.queryForObject("select id from supplier where code = 'S001'", Long.class);
        warehouseId = jdbc.queryForObject("select id from warehouse where code = 'WH-RAW'", Long.class);
        productId = jdbc.queryForObject("select id from product where code = 'P003'", Long.class);
        orderId = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, warehouseId, LocalDate.now(), LocalDate.now().plusDays(3), false, "退货测试",
                List.of(new PurchaseOrderService.ItemRequest(productId, new BigDecimal("10"),
                        new BigDecimal("10"), null))));
        purchaseOrderService.confirm(orderId);
        Map<String, Object> receipt = purchaseOrderService.receive(orderId, null);
        inboundId = ((Number) receipt.get("inboundId")).longValue();
        inboundOrderService.submit(inboundId);
        orderItemId = jdbc.queryForObject(
                "select id from purchase_order_item where order_id = ?", Long.class, orderId);
        inboundItemId = jdbc.queryForObject(
                "select id from inbound_order_item where inbound_id = ?", Long.class, inboundId);
    }

    @Test
    @DisplayName("手工建采购入库被拒，必须从采购订单收货")
    void manualPurchaseInboundRejected() {
        BizException ex = assertThrows(BizException.class, () -> inboundOrderService.create(
                new InboundOrderService.InboundRequest("PURCHASE", warehouseId, "宁波金属材料有限公司", false,
                        "想让采购入库走手工", "期初建账",
                        List.of(new InboundOrderService.InboundItem(productId, BigDecimal.ONE, null, null)))));
        assertEquals(4006, ex.getCode());
        assertTrue(ex.getMessage().contains("采购订单收货"));
    }

    @Test
    @DisplayName("无单入库必须填写原因，建单后标记为无单来源且不生成暂估")
    void manualInboundNeedsReason() {
        BizException noReason = assertThrows(BizException.class, () -> inboundOrderService.create(
                new InboundOrderService.InboundRequest("OTHER", warehouseId, null, false, "期初建账", " ",
                        List.of(new InboundOrderService.InboundItem(productId, BigDecimal.TEN, "INIT-B", null)))));
        assertEquals(4007, noReason.getCode());

        long id = inboundOrderService.create(new InboundOrderService.InboundRequest(
                "OTHER", warehouseId, null, false, "期初建账", "期初盘点建账，无采购单",
                List.of(new InboundOrderService.InboundItem(productId, BigDecimal.TEN, "INIT-B", null))));
        Map<String, Object> detail = inboundOrderService.detail(id);
        assertEquals("MANUAL", detail.get("source"));
        assertEquals("期初盘点建账，无采购单", detail.get("noOrderReason"));

        inboundOrderService.submit(id);
        Integer accruals = jdbc.queryForObject(
                "select count(*) from ap_accrual where inbound_order_id = ?", Integer.class, id);
        assertEquals(0, accruals);
    }

    @Test
    @DisplayName("采购收货生成的入库单不允许改数量，避免绕过采购单")
    void purchaseInboundNotEditable() {
        assertEquals("PO", inboundOrderService.detail(inboundId).get("source"));

        long secondOrder = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, warehouseId, LocalDate.now(), LocalDate.now().plusDays(3), false, "改单测试",
                List.of(new PurchaseOrderService.ItemRequest(productId, new BigDecimal("5"),
                        new BigDecimal("10"), null))));
        purchaseOrderService.confirm(secondOrder);
        Map<String, Object> receipt = purchaseOrderService.receive(secondOrder, null);
        long draftInboundId = ((Number) receipt.get("inboundId")).longValue();

        BizException edit = assertThrows(BizException.class, () -> inboundOrderService.update(draftInboundId,
                new InboundOrderService.InboundRequest("OTHER", warehouseId, null, false, "改单", "绕过采购单",
                        List.of(new InboundOrderService.InboundItem(productId, BigDecimal.ONE, null, null)))));
        assertEquals(4006, edit.getCode());
        assertTrue(edit.getMessage().contains("不支持修改"));
    }

    @Test
    @DisplayName("采购退货提交后扣库存、回退实收、采购单退回部分到货、暂估同步冲减")
    void returnReducesStockAndAccrual() {
        BigDecimal stockBefore = jdbc.queryForObject(
                "select qty from inventory where product_id = ? and warehouse_id = ?",
                BigDecimal.class, productId, warehouseId);

        long returnId = purchaseReturnService.create(new PurchaseReturnService.ReturnRequest(
                inboundId, LocalDate.now(), "来料尺寸超差退回供应商",
                List.of(new PurchaseReturnService.ReturnItemRequest(inboundItemId, new BigDecimal("4"), "LOT-A"))));
        assertEquals("DRAFT", purchaseReturnService.detail(returnId).get("status"));
        purchaseReturnService.complete(returnId);

        Map<String, Object> detail = purchaseReturnService.detail(returnId);
        assertEquals("COMPLETED", detail.get("status"));
        assertEquals(0, new BigDecimal(String.valueOf(detail.get("totalAmount")))
                .compareTo(new BigDecimal("40")));

        BigDecimal stockAfter = jdbc.queryForObject(
                "select qty from inventory where product_id = ? and warehouse_id = ?",
                BigDecimal.class, productId, warehouseId);
        assertEquals(0, stockBefore.subtract(stockAfter).compareTo(new BigDecimal("4")));

        BigDecimal received = jdbc.queryForObject(
                "select received_qty from purchase_order_item where id = ?", BigDecimal.class, orderItemId);
        assertEquals(0, received.compareTo(new BigDecimal("6")));
        assertEquals("PARTIAL", purchaseOrderService.detail(orderId).get("status"));

        Map<String, Object> accrual = jdbc.queryForMap(
                "select amount, status from ap_accrual where inbound_order_id = ?", inboundId);
        assertEquals(0, new BigDecimal(String.valueOf(accrual.get("amount")))
                .compareTo(new BigDecimal("60")));

        String txnType = jdbc.queryForObject("""
                select txn_type from stock_txn where ref_no = (
                    select return_no from purchase_return where id = ?) limit 1
                """, String.class, returnId);
        assertEquals("PURCHASE_RETURN", txnType);
    }

    @Test
    @DisplayName("退货数量超过可退数量被拒，重复退货累计不能超过入库数量")
    void returnQuantityLimited() {
        BizException tooMany = assertThrows(BizException.class,
                () -> purchaseReturnService.create(new PurchaseReturnService.ReturnRequest(
                        inboundId, LocalDate.now(), "超量退货",
                        List.of(new PurchaseReturnService.ReturnItemRequest(inboundItemId,
                                new BigDecimal("11"), null)))));
        assertEquals(4010, tooMany.getCode());

        long first = purchaseReturnService.create(new PurchaseReturnService.ReturnRequest(
                inboundId, LocalDate.now(), "先退一半",
                List.of(new PurchaseReturnService.ReturnItemRequest(inboundItemId,
                        new BigDecimal("6"), null))));
        purchaseReturnService.complete(first);

        BizException remain = assertThrows(BizException.class,
                () -> purchaseReturnService.create(new PurchaseReturnService.ReturnRequest(
                        inboundId, LocalDate.now(), "再退六件就超了",
                        List.of(new PurchaseReturnService.ReturnItemRequest(inboundItemId,
                                new BigDecimal("6"), null)))));
        assertEquals(4010, remain.getCode());
        assertTrue(remain.getMessage().contains("超过可退数量"));
    }

    @Test
    @DisplayName("已开票匹配的入库单退货时自动开红字发票红冲，已付款部分转待供应商退款")
    void returnCreatesCreditInvoice() {
        long invoiceId = apService.createInvoice(new ApService.InvoiceRequest(
                "INV-PRT-" + System.nanoTime(), "VAT_SPECIAL", supplierId, orderId, LocalDate.now(),
                new BigDecimal("13"), "退货测试开票",
                List.of(new ApService.InvoiceItemRequest(orderItemId, new BigDecimal("10"),
                        new BigDecimal("10"), null))));
        apService.match(invoiceId, new ApService.MatchRequest(false, null));
        apService.pay(invoiceId, new ApService.PaymentRequest(LocalDate.now(), new BigDecimal("100"),
                "BANK", "BANK-PRT", null));

        long returnId = purchaseReturnService.create(new PurchaseReturnService.ReturnRequest(
                inboundId, LocalDate.now(), "已开票后整批退回",
                List.of(new PurchaseReturnService.ReturnItemRequest(inboundItemId,
                        new BigDecimal("4"), null))));
        Map<String, Object> result = purchaseReturnService.complete(returnId);
        assertEquals(1, ((List<?>) result.get("redInvoiceIds")).size());

        Map<String, Object> returnDetail = purchaseReturnService.detail(returnId);
        assertNotNull(returnDetail.get("redInvoiceNo"));
        assertEquals("COMPLETED", returnDetail.get("status"));
        assertEquals(1, ((List<?>) returnDetail.get("redInvoices")).size());

        Map<String, Object> blue = apService.invoiceDetail(invoiceId);
        assertEquals(0, new BigDecimal(String.valueOf(blue.get("grossAmount")))
                .compareTo(new BigDecimal("60")));
        assertEquals(0, new BigDecimal(String.valueOf(blue.get("refundableAmount")))
                .compareTo(new BigDecimal("40")));
        assertEquals("PAID", blue.get("status"));

        Map<String, Object> accrual = jdbc.queryForMap(
                "select amount, matched_amount, status from ap_accrual where inbound_order_id = ?", inboundId);
        assertEquals(0, new BigDecimal(String.valueOf(accrual.get("amount")))
                .compareTo(new BigDecimal("60")));
        assertEquals(0, new BigDecimal(String.valueOf(accrual.get("matched_amount")))
                .compareTo(new BigDecimal("60")));
        assertEquals("MATCHED", accrual.get("status"));

        apService.refund(invoiceId, new ApService.RefundRequest(LocalDate.now(), new BigDecimal("40"),
                "BANK", "REFUND-1", "红冲后退款"));
        assertEquals(0, new BigDecimal(String.valueOf(apService.invoiceDetail(invoiceId)
                .get("refundableAmount"))).signum());

        BizException tooMuch = assertThrows(BizException.class, () -> apService.refund(invoiceId,
                new ApService.RefundRequest(LocalDate.now(), new BigDecimal("1"), "BANK", null, null)));
        assertEquals(2014, tooMuch.getCode());
    }

    @Test
    @DisplayName("红字发票登记与红冲：蓝票金额与暂估同步回退，红票可作废恢复")
    void creditInvoiceReversal() {
        long invoiceId = apService.createInvoice(new ApService.InvoiceRequest(
                "INV-CR-" + System.nanoTime(), "VAT_SPECIAL", supplierId, orderId, LocalDate.now(),
                new BigDecimal("13"), "红冲测试开票",
                List.of(new ApService.InvoiceItemRequest(orderItemId, new BigDecimal("10"),
                        new BigDecimal("10"), null))));
        apService.match(invoiceId, new ApService.MatchRequest(false, null));
        long blueItemId = ((Number) ((List<Map<String, Object>>) apService.invoiceDetail(invoiceId)
                .get("items")).get(0).get("id")).longValue();

        long creditId = apService.createCredit(new ApService.CreditRequest(
                "CR-E2E-" + System.nanoTime(), invoiceId, LocalDate.now(), null, "来料折让",
                List.of(new ApService.CreditLine(blueItemId, new BigDecimal("3")))));
        Map<String, Object> creditDetail = apService.invoiceDetail(creditId);
        assertEquals("CREDIT", creditDetail.get("invoiceType"));
        assertEquals(0, new BigDecimal(String.valueOf(creditDetail.get("grossAmount")))
                .compareTo(new BigDecimal("-30")));
        assertEquals(invoiceId, ((Number) creditDetail.get("relatedInvoiceId")).longValue());

        Map<String, Object> reversed = apService.matchCredit(creditId);
        assertEquals(0, new BigDecimal(String.valueOf(reversed.get("blueGrossAmount")))
                .compareTo(new BigDecimal("70")));
        assertEquals("MATCHED", apService.invoiceDetail(creditId).get("status"));

        Map<String, Object> accrual = jdbc.queryForMap(
                "select amount, matched_amount from ap_accrual where inbound_order_id = ?", inboundId);
        assertEquals(0, new BigDecimal(String.valueOf(accrual.get("matched_amount")))
                .compareTo(new BigDecimal("70")));

        BizException tooMany = assertThrows(BizException.class, () -> apService.createCredit(
                new ApService.CreditRequest("CR-OVER-" + System.nanoTime(), invoiceId, LocalDate.now(), null,
                        "超量红冲",
                        List.of(new ApService.CreditLine(blueItemId, new BigDecimal("8"))))));
        assertEquals(2021, tooMany.getCode());

        BizException noPay = assertThrows(BizException.class, () -> apService.pay(creditId,
                new ApService.PaymentRequest(LocalDate.now(), new BigDecimal("10"), "BANK", null, null)));
        assertEquals(2014, noPay.getCode());

        BizException blueLocked = assertThrows(BizException.class, () -> apService.cancel(invoiceId));
        assertEquals(2014, blueLocked.getCode());
        assertTrue(blueLocked.getMessage().contains("红字发票"));

        apService.cancel(creditId);
        assertEquals("CANCELLED", apService.invoiceDetail(creditId).get("status"));
        assertEquals(0, new BigDecimal(String.valueOf(apService.invoiceDetail(invoiceId)
                .get("grossAmount"))).compareTo(new BigDecimal("100")));
        Map<String, Object> restored = jdbc.queryForMap(
                "select amount, matched_amount, status from ap_accrual where inbound_order_id = ?", inboundId);
        assertEquals(0, new BigDecimal(String.valueOf(restored.get("matched_amount")))
                .compareTo(new BigDecimal("100")));
        assertEquals("MATCHED", restored.get("status"));
    }
}
