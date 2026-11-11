package com.mes.finance;

import com.mes.common.BizException;
import com.mes.sales.SalesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 财务核心：应收开票/收款核销/账龄、凭证平衡、期间关账、外币汇率、应付凭证同步。
 */
@SpringBootTest
@ActiveProfiles("test")
class FinanceTest {

    @Autowired
    private ReceivableService receivables;
    @Autowired
    private FinanceLedgerService ledger;
    @Autowired
    private ApVoucherSyncService apSync;
    @Autowired
    private SalesService sales;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    @DisplayName("发货单开票 → 收款核销 → 账龄归零，凭证始终借贷平衡")
    void receivableLifecycle() {
        long customerId = customerId();
        long productId = productId();
        long orderId = sales.create(new SalesService.OrderRequest(
                customerId, LocalDate.now(), LocalDate.now().plusDays(7), "财务测试",
                List.of(new SalesService.OrderItem(productId, new BigDecimal("2"), new BigDecimal("56.50"))),
                "NORMAL"));
        long shipmentId = shipment(orderId, customerId, productId, new BigDecimal("2"));

        long invoiceId = receivables.invoiceFromShipment(shipmentId,
                new ReceivableService.ShipmentInvoiceRequest(new BigDecimal("0.13"), 30, null, "财务测试开票"));
        Map<String, Object> invoice = receivables.invoiceDetail(invoiceId);
        assertEquals("CNY", invoice.get("currency"));
        assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(String.valueOf(invoice.get("netAmount")))));
        assertEquals(0, new BigDecimal("13.00").compareTo(new BigDecimal(String.valueOf(invoice.get("taxAmount")))));
        assertEquals(0, new BigDecimal("113.00").compareTo(new BigDecimal(String.valueOf(invoice.get("totalAmount")))));
        assertEquals(0, new BigDecimal("113.00").compareTo(new BigDecimal(String.valueOf(invoice.get("baseTotal")))));
        assertVoucherBalanced("AR_INVOICE", invoiceId);

        // 重复开票被拦截
        assertThrows(BizException.class, () -> receivables.invoiceFromShipment(shipmentId, null));

        long firstReceipt = receivables.createReceipt(new ReceivableService.ReceiptRequest(
                customerId, LocalDate.now(), "CNY", new BigDecimal("50.00"), "BANK", "BANK-001",
                List.of(new ReceivableService.ApplyLine(invoiceId, new BigDecimal("50.00"))), "首款"));
        assertEquals("PARTIAL", receivables.invoiceDetail(invoiceId).get("status"));
        assertVoucherBalanced("AR_RECEIPT", firstReceipt);

        Map<String, Object> aging = receivables.aging();
        assertTrue(rows(aging).stream().anyMatch(row ->
                "CNY".equals(row.get("currency"))
                        && new BigDecimal(String.valueOf(row.get("total"))).signum() > 0));

        long secondReceipt = receivables.createReceipt(new ReceivableService.ReceiptRequest(
                customerId, LocalDate.now(), "CNY", new BigDecimal("63.00"), "BANK", "BANK-002",
                List.of(new ReceivableService.ApplyLine(invoiceId, new BigDecimal("63.00"))), "尾款"));
        assertEquals("PAID", receivables.invoiceDetail(invoiceId).get("status"));

        // 取消尾款：发票回到部分收款，凭证红冲
        receivables.cancelReceipt(secondReceipt);
        assertEquals("PARTIAL", receivables.invoiceDetail(invoiceId).get("status"));
        assertEquals("REVERSED", jdbc.queryForObject("""
                select status from fin_voucher where biz_type = 'AR_RECEIPT' and biz_id = ?
                """, String.class, secondReceipt));

        // 已收款的发票不能直接作废
        assertThrows(BizException.class, () -> receivables.cancelInvoice(invoiceId));
    }

    @Test
    @DisplayName("外币发票没有汇率拒绝开票，维护汇率后按本位币入账")
    void foreignCurrencyNeedsRate() {
        long customerId = customerId();
        long productId = productId();
        LocalDate date = LocalDate.of(2024, 2, 1);
        // 测试库会保留上一轮维护的汇率，先清掉，保证“缺汇率必须拒绝”的前提成立
        jdbc.update("delete from fin_exchange_rate where currency = 'USD' and rate_date = ?", date);
        ReceivableService.InvoiceRequest request = new ReceivableService.InvoiceRequest(
                customerId, null, "NORMAL", date, date.plusDays(30), "USD",
                new BigDecimal("0.13"), true,
                List.of(new ReceivableService.InvoiceItem(productId, BigDecimal.ONE, new BigDecimal("113.00"))),
                "美元发票");

        assertTrue(assertThrows(BizException.class, () -> receivables.createInvoice(request))
                .getMessage().contains("汇率"));

        ledger.saveRate("USD", date, new BigDecimal("7.2000"));
        long invoiceId = receivables.createInvoice(request);
        Map<String, Object> invoice = receivables.invoiceDetail(invoiceId);
        assertEquals(0, new BigDecimal("7.20000000").compareTo(
                new BigDecimal(String.valueOf(invoice.get("exchangeRate")))));
        assertEquals(0, new BigDecimal("813.60").compareTo(
                new BigDecimal(String.valueOf(invoice.get("baseTotal")))));
        assertVoucherBalanced("AR_INVOICE", invoiceId);
    }

    @Test
    @DisplayName("期间关账后不能再开票或收款")
    void closedPeriodBlocksPosting() {
        long customerId = customerId();
        long productId = productId();
        LocalDate date = LocalDate.of(2099, 12, 15);
        // 测试库可能保留上一轮的关账记录，先恢复为未关账，保证用例可重复执行
        jdbc.update("delete from fin_period where period = '2099-12'");
        ReceivableService.InvoiceRequest request = new ReceivableService.InvoiceRequest(
                customerId, null, "NORMAL", date, date.plusDays(30), "CNY",
                new BigDecimal("0.13"), true,
                List.of(new ReceivableService.InvoiceItem(productId, BigDecimal.ONE, new BigDecimal("113.00"))),
                "关账测试");
        long invoiceId = receivables.createInvoice(request);

        ledger.closePeriod("2099-12");
        assertThrows(BizException.class, () -> ledger.closePeriod("2099-12"));
        assertThrows(BizException.class, () -> receivables.createInvoice(new ReceivableService.InvoiceRequest(
                customerId, null, "NORMAL", date.plusDays(1), date.plusDays(30), "CNY",
                new BigDecimal("0.13"), true,
                List.of(new ReceivableService.InvoiceItem(productId, BigDecimal.ONE, new BigDecimal("56.50"))),
                "关账后开票")));
        assertThrows(BizException.class, () -> receivables.createReceipt(new ReceivableService.ReceiptRequest(
                customerId, date, "CNY", new BigDecimal("10.00"), "BANK", null,
                List.of(new ReceivableService.ApplyLine(invoiceId, new BigDecimal("10.00"))), "关账后收款")));
    }

    @Test
    @DisplayName("应付发票与付款补记凭证可重复执行且不重复记账")
    void apVoucherSyncIsIdempotent() {
        cleanupApFixtures();
        Long supplierId = jdbc.queryForObject("""
                insert into supplier (code, name) values ('FIN-SUP-01', '财务测试供应商')
                on conflict (code) do update set name = excluded.name
                returning id
                """, Long.class);
        String invoiceNo = "FIN-AP-" + System.nanoTime();
        long invoiceId = jdbc.queryForObject("""
                insert into ap_invoice (invoice_no, supplier_id, invoice_date, net_amount, tax_amount,
                                        gross_amount, status)
                values (?, ?, date '2099-11-10', 100.00, 13.00, 113.00, 'MATCHED')
                returning id
                """, Long.class, invoiceNo, supplierId);
        try {
            Map<String, Object> first = apSync.sync();
            assertTrue(((Number) first.get("invoicePosted")).intValue() >= 1);
            Map<String, Object> second = apSync.sync();
            assertEquals(0, ((Number) second.get("invoicePosted")).intValue());
            assertVoucherBalanced("AP_INVOICE", invoiceId);
        } finally {
            // 应付测试数据会干扰采购模块的“可红冲候选”断言，用例结束必须清干净
            cleanupApFixtures();
        }
    }

    @Test
    @DisplayName("未核销收款进入预收账款，后续可核销到新发票")
    void advanceReceiptCanBeAppliedLater() {
        long customerId = customerId();
        long productId = productId();
        long orderId = sales.create(new SalesService.OrderRequest(
                customerId, LocalDate.now(), LocalDate.now().plusDays(7), "预收测试",
                List.of(new SalesService.OrderItem(productId, BigDecimal.ONE, new BigDecimal("113.00"))),
                "NORMAL"));
        long shipmentId = shipment(orderId, customerId, productId, BigDecimal.ONE);
        long invoiceId = receivables.invoiceFromShipment(shipmentId, null);

        long receiptId = receivables.createReceipt(new ReceivableService.ReceiptRequest(
                customerId, LocalDate.now(), "CNY", new BigDecimal("30.00"), "BANK", "ADV-001",
                List.of(), "预收 30"));
        Map<String, Object> voucherTotals = jdbc.queryForMap("""
                select coalesce(sum(l.credit), 0) as credit
                from fin_voucher_line l
                join fin_account a on a.code = l.account_code
                join fin_voucher v on v.id = l.voucher_id
                where v.biz_type = 'AR_RECEIPT' and v.biz_id = ? and a.role = 'AR_ADVANCE'
                """, receiptId);
        assertEquals(0, new BigDecimal("30.00").compareTo(
                new BigDecimal(String.valueOf(voucherTotals.get("credit")))));

        Map<String, Object> applied = receivables.applyAdvance(receiptId,
                List.of(new ReceivableService.ApplyLine(invoiceId, new BigDecimal("30.00"))));
        assertEquals(0, new BigDecimal("30.00").compareTo(
                new BigDecimal(String.valueOf(applied.get("applied")))));
        assertEquals("PARTIAL", receivables.invoiceDetail(invoiceId).get("status"));
        // 已做过预收核销的收款单不能直接取消
        assertThrows(BizException.class, () -> receivables.cancelReceipt(receiptId));
    }

    // ---------------------------------------------------------------- 工具

    private void cleanupApFixtures() {
        List<Long> ids = jdbc.queryForList(
                "select id from ap_invoice where invoice_no like 'FIN-AP-%'", Long.class);
        for (Long id : ids) {
            jdbc.update("""
                    delete from fin_voucher_line where voucher_id in
                        (select id from fin_voucher where biz_type = 'AP_INVOICE' and biz_id = ?)
                    """, id);
            jdbc.update("delete from fin_voucher where biz_type = 'AP_INVOICE' and biz_id = ?", id);
            jdbc.update("delete from ap_payment where invoice_id = ?", id);
            jdbc.update("delete from ap_invoice_match where invoice_id = ?", id);
            jdbc.update("delete from ap_invoice_item where invoice_id = ?", id);
            jdbc.update("delete from ap_invoice where id = ?", id);
        }
        jdbc.update("""
                delete from supplier s where s.code = 'FIN-SUP-01'
                  and not exists (select 1 from purchase_order po where po.supplier_id = s.id)
                """);
    }

    private long shipment(long orderId, long customerId, long productId, BigDecimal qty) {
        Long salesItemId = jdbc.queryForObject(
                "select id from sales_order_item where order_id = ? order by id limit 1",
                Long.class, orderId);
        Long shipmentId = jdbc.queryForObject("""
                insert into shipment (shipment_no, sales_order_id, customer_id, status, shipped_at)
                values (?, ?, ?, 'SHIPPED', now())
                returning id
                """, Long.class, "FIN-SH-" + System.nanoTime(), orderId, customerId);
        jdbc.update("""
                insert into shipment_item (shipment_id, sales_order_item_id, product_id, qty)
                values (?, ?, ?, ?)
                """, shipmentId, salesItemId, productId, qty);
        return shipmentId;
    }

    private void assertVoucherBalanced(String bizType, long bizId) {
        Long voucherId = jdbc.queryForObject("""
                select id from fin_voucher where biz_type = ? and biz_id = ?
                """, Long.class, bizType, bizId);
        assertNotNull(voucherId);
        Map<String, Object> totals = jdbc.queryForMap("""
                select coalesce(sum(debit), 0) as debit, coalesce(sum(credit), 0) as credit
                from fin_voucher_line where voucher_id = ?
                """, voucherId);
        BigDecimal debit = new BigDecimal(String.valueOf(totals.get("debit")));
        BigDecimal credit = new BigDecimal(String.valueOf(totals.get("credit")));
        assertEquals(0, debit.compareTo(credit), "凭证借贷必须平衡");
        assertTrue(debit.signum() > 0, "凭证金额必须大于 0");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("rows");
    }

    private long customerId() {
        return jdbc.queryForObject("select id from customer order by id limit 1", Long.class);
    }

    private long productId() {
        return jdbc.queryForObject("select id from product order by id limit 1", Long.class);
    }
}
