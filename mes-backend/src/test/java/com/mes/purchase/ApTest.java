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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发票与应付：入库自动暂估、发票税额拆分、三单匹配冲暂估、
 * 数量与单价差异、强制匹配、付款与作废回退。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApTest {

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private InboundOrderService inboundOrderService;

    @Autowired
    private ApService apService;

    @Autowired
    private JdbcTemplate jdbc;

    private long supplierId;
    private long warehouseId;
    private long productId;
    private long orderId;
    private long orderItemId;

    @BeforeEach
    void setUp() {
        supplierId = jdbc.queryForObject("select id from supplier where code = 'S001'", Long.class);
        warehouseId = jdbc.queryForObject("select id from warehouse where code = 'WH-RAW'", Long.class);
        productId = jdbc.queryForObject("select id from product where code = 'P003'", Long.class);
        orderId = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, warehouseId, LocalDate.now(), LocalDate.now().plusDays(3), false, "暂估测试",
                List.of(new PurchaseOrderService.ItemRequest(productId, new BigDecimal("10"),
                        new BigDecimal("10"), null))));
        purchaseOrderService.confirm(orderId);
        Map<String, Object> receipt = purchaseOrderService.receive(orderId, null);
        inboundOrderService.submit(((Number) receipt.get("inboundId")).longValue());
        orderItemId = ((Number) ((List<Map<String, Object>>) purchaseOrderService.detail(orderId)
                .get("items")).get(0).get("id")).longValue();
    }

    @Test
    @DisplayName("采购入库完成自动生成暂估，金额等于实收数量乘订单单价")
    void accrualCreatedOnReceipt() {
        List<Map<String, Object>> accruals = (List<Map<String, Object>>) apService
                .accruals(supplierId, null, null, 1, 20).records();
        assertFalse(accruals.isEmpty());
        Map<String, Object> accrual = accruals.get(0);
        assertEquals(0, new BigDecimal(String.valueOf(accrual.get("amount"))).compareTo(new BigDecimal("100")));
        assertEquals("OPEN", accrual.get("status"));
    }

    @Test
    @DisplayName("发票登记拆分税额，匹配后冲暂估，部分付款转已付清")
    void invoiceMatchAndPay() {
        long invoiceId = createInvoice("INV-" + System.nanoTime(), "10", "10");
        Map<String, Object> invoice = apService.invoiceDetail(invoiceId);
        assertEquals(0, new BigDecimal(String.valueOf(invoice.get("grossAmount"))).compareTo(new BigDecimal("100")));
        assertEquals(0, new BigDecimal(String.valueOf(invoice.get("netAmount"))).compareTo(new BigDecimal("88.50")));
        assertEquals(0, new BigDecimal(String.valueOf(invoice.get("taxAmount"))).compareTo(new BigDecimal("11.50")));
        assertEquals(LocalDate.now().plusDays(30).toString(), invoice.get("dueDate"));

        Map<String, Object> matched = apService.match(invoiceId, new ApService.MatchRequest(false, null));
        assertEquals(0, new BigDecimal(String.valueOf(matched.get("differenceAmount"))).signum());
        assertEquals("MATCHED", apService.invoiceDetail(invoiceId).get("status"));
        Map<String, Object> accrual = ((List<Map<String, Object>>) apService
                .accruals(supplierId, null, null, 1, 20).records()).get(0);
        assertEquals("MATCHED", accrual.get("status"));

        apService.pay(invoiceId, new ApService.PaymentRequest(LocalDate.now(), new BigDecimal("60"),
                "BANK", "BANK-1", null));
        Map<String, Object> partial = apService.invoiceDetail(invoiceId);
        assertEquals("PARTIAL", partial.get("status"));
        assertEquals(0, new BigDecimal(String.valueOf(partial.get("remainingAmount"))).compareTo(new BigDecimal("40")));

        BizException tooMuch = assertThrows(BizException.class, () -> apService.pay(invoiceId,
                new ApService.PaymentRequest(LocalDate.now(), new BigDecimal("50"), "BANK", null, null)));
        assertEquals(1002, tooMuch.getCode());

        apService.pay(invoiceId, new ApService.PaymentRequest(LocalDate.now(), new BigDecimal("40"),
                "BANK", "BANK-2", null));
        assertEquals("PAID", apService.invoiceDetail(invoiceId).get("status"));
    }

    @Test
    @DisplayName("发票数量超过已入库未开票被拒，重复发票号被拒")
    void invoiceValidation() {
        BizException tooMany = assertThrows(BizException.class,
                () -> createInvoice("INV-OVER-" + System.nanoTime(), "11", "10"));
        assertEquals(1002, tooMany.getCode());
        assertTrue(tooMany.getMessage().contains("超过已入库未开票"));

        String no = "INV-DUP-" + System.nanoTime();
        createInvoice(no, "5", "10");
        BizException duplicate = assertThrows(BizException.class,
                () -> createInvoice(no, "5", "10"));
        assertEquals(2015, duplicate.getCode());
    }

    @Test
    @DisplayName("单价差异需要强制匹配，差异金额记录在发票上")
    void priceDifferenceNeedsForce() {
        long invoiceId = createInvoice("INV-DIFF-" + System.nanoTime(), "10", "12");
        BizException difference = assertThrows(BizException.class,
                () -> apService.match(invoiceId, new ApService.MatchRequest(false, null)));
        assertEquals(2020, difference.getCode());
        assertTrue(difference.getMessage().contains("差异"));
        assertEquals("DRAFT", apService.invoiceDetail(invoiceId).get("status"));

        Map<String, Object> forced = apService.match(invoiceId, new ApService.MatchRequest(true, "价格已谈定"));
        assertEquals(0, new BigDecimal(String.valueOf(forced.get("differenceAmount")))
                .compareTo(new BigDecimal("20")));
        Map<String, Object> invoice = apService.invoiceDetail(invoiceId);
        assertEquals("MATCHED", invoice.get("status"));
        assertNotNull(invoice.get("differenceNote"));
    }

    @Test
    @DisplayName("已匹配未付款可以作废并回退暂估，已付款不能作废")
    void cancelInvoice() {
        long invoiceId = createInvoice("INV-CANCEL-" + System.nanoTime(), "10", "10");
        apService.match(invoiceId, new ApService.MatchRequest(false, null));
        apService.cancel(invoiceId);
        assertEquals("CANCELLED", apService.invoiceDetail(invoiceId).get("status"));
        Map<String, Object> accrual = ((List<Map<String, Object>>) apService
                .accruals(supplierId, null, null, 1, 20).records()).get(0);
        assertEquals("OPEN", accrual.get("status"));

        long paidId = createInvoice("INV-PAID-" + System.nanoTime(), "10", "10");
        apService.match(paidId, new ApService.MatchRequest(false, null));
        apService.pay(paidId, new ApService.PaymentRequest(LocalDate.now(), new BigDecimal("10"),
                "BANK", null, null));
        BizException paid = assertThrows(BizException.class, () -> apService.cancel(paidId));
        assertEquals(2014, paid.getCode());
    }

    private long createInvoice(String invoiceNo, String qty, String price) {
        return apService.createInvoice(new ApService.InvoiceRequest(invoiceNo, "VAT_SPECIAL", supplierId,
                orderId, LocalDate.now(), new BigDecimal("13"), "单元测试",
                List.of(new ApService.InvoiceItemRequest(orderItemId, new BigDecimal(qty),
                        new BigDecimal(price), null))));
    }
}
