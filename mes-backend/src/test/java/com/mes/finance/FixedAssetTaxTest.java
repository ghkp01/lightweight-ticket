package com.mes.finance;

import com.mes.common.BizException;
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
 * 固定资产折旧/处置、税务申报（增值税与附加税）的计提、结转与缴款。
 */
@SpringBootTest
@ActiveProfiles("test")
class FixedAssetTaxTest {

    @Autowired
    private FixedAssetService assets;
    @Autowired
    private TaxFilingService taxFilings;
    @Autowired
    private ReceivableService receivables;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    @DisplayName("直线法折旧：月折旧额正确、重复计提不重复、处置凭证平衡")
    void depreciationAndDisposal() {
        jdbc.update("delete from fa_depreciation where asset_id in (select id from fa_asset where remark = '测试设备')");
        jdbc.update("delete from fa_asset where remark = '测试设备'");

        long assetId = assets.create(new FixedAssetService.AssetRequest(
                "数控切割机", "生产设备", "一车间", "生产部",
                LocalDate.of(2024, 4, 10), new BigDecimal("12000.00"),
                new BigDecimal("0.05"), 12, "测试设备", false));

        Map<String, Object> first = assets.depreciate("2024-05");
        assertEquals(1, ((Number) first.get("assets")).intValue());
        assertEquals(0, new BigDecimal("950.00").compareTo(
                new BigDecimal(String.valueOf(first.get("amount")))));
        assertVoucherBalanced("FA_DEPRECIATION");

        Map<String, Object> second = assets.depreciate("2024-05");
        assertEquals(0, ((Number) second.get("assets")).intValue());

        Map<String, Object> disposed = assets.dispose(assetId,
                new FixedAssetService.DisposeRequest(LocalDate.of(2024, 6, 15),
                        new BigDecimal("10000.00"), "出售"));
        // 净值 12000-950=11050，处置款 10000，损失 1050
        assertEquals(0, new BigDecimal("-1050.00").compareTo(
                new BigDecimal(String.valueOf(disposed.get("gainLoss")))));
        assertEquals("DISPOSED", jdbc.queryForObject(
                "select status from fa_asset where id = ?", String.class, assetId));
        assertVoucherBalanced("FA_DISPOSAL");
    }

    @Test
    @DisplayName("增值税申报：销项取总账、附加税按 12% 计征，申报与缴款凭证平衡")
    void taxFilingLifecycle() {
        String period = "2024-06";
        cleanupTaxFixtures(period);
        try {
            long customerId = jdbc.queryForObject(
                    "select id from customer order by id limit 1", Long.class);
            long productId = jdbc.queryForObject(
                    "select id from product order by id limit 1", Long.class);
            receivables.createInvoice(new ReceivableService.InvoiceRequest(
                    customerId, null, "NORMAL", LocalDate.of(2024, 6, 10),
                    LocalDate.of(2024, 7, 10), "CNY", new BigDecimal("0.13"), true,
                    List.of(new ReceivableService.InvoiceItem(productId, BigDecimal.ONE,
                            new BigDecimal("113.00"))),
                    "税务测试发票"));

            Map<String, Object> filing = taxFilings.generate(
                    new TaxFilingService.GenerateRequest(period, null, null, null));
            assertEquals(0, new BigDecimal("13.00").compareTo(
                    new BigDecimal(String.valueOf(filing.get("outputTax")))));
            assertEquals(0, new BigDecimal("13.00").compareTo(
                    new BigDecimal(String.valueOf(filing.get("vatPayable")))));
            // 城建 7% + 教育 3% + 地方教育 2% = 12%
            assertEquals(0, new BigDecimal("1.56").compareTo(
                    new BigDecimal(String.valueOf(filing.get("totalSurtax")))));
            assertEquals(0, new BigDecimal("14.56").compareTo(
                    new BigDecimal(String.valueOf(filing.get("totalPayable")))));
            long filingId = ((Number) filing.get("id")).longValue();

            Map<String, Object> filed = taxFilings.file(filingId);
            assertEquals("FILED", filed.get("status"));
            assertVoucherBalanced("TAX_VAT_TRANSFER");
            assertVoucherBalanced("TAX_SURTAX_TRANSFER");

            Map<String, Object> paid = taxFilings.pay(filingId, "银行扣款");
            assertEquals("PAID", paid.get("status"));
            assertVoucherBalanced("TAX_PAYMENT");

            assertThrows(BizException.class, () -> taxFilings.generate(
                    new TaxFilingService.GenerateRequest(period, null, null, null)));
        } finally {
            cleanupTaxFixtures(period);
        }
    }

    // ---------------------------------------------------------------- 工具

    private void cleanupTaxFixtures(String period) {
        jdbc.update("""
                delete from fin_voucher_line where voucher_id in (
                    select id from fin_voucher where
                        (biz_type in ('TAX_VAT_TRANSFER', 'TAX_SURTAX_TRANSFER', 'TAX_PAYMENT')
                         and biz_id in (select id from tax_filing where period = ?))
                        or (biz_type = 'AR_INVOICE' and biz_id in (
                            select id from ar_invoice
                            where remark = '税务测试发票'
                              and to_char(invoice_date, 'YYYY-MM') = ?)))
                """, period, period);
        jdbc.update("""
                delete from fin_voucher where
                    (biz_type in ('TAX_VAT_TRANSFER', 'TAX_SURTAX_TRANSFER', 'TAX_PAYMENT')
                     and biz_id in (select id from tax_filing where period = ?))
                    or (biz_type = 'AR_INVOICE' and biz_id in (
                        select id from ar_invoice
                        where remark = '税务测试发票'
                          and to_char(invoice_date, 'YYYY-MM') = ?))
                """, period, period);
        jdbc.update("""
                delete from ar_receipt_apply where invoice_id in (
                    select id from ar_invoice where remark = '税务测试发票'
                      and to_char(invoice_date, 'YYYY-MM') = ?)
                """, period);
        jdbc.update("""
                delete from ar_invoice_item where invoice_id in (
                    select id from ar_invoice where remark = '税务测试发票'
                      and to_char(invoice_date, 'YYYY-MM') = ?)
                """, period);
        jdbc.update("""
                delete from ar_invoice where remark = '税务测试发票'
                  and to_char(invoice_date, 'YYYY-MM') = ?
                """, period);
        jdbc.update("delete from tax_filing where period = ?", period);
    }

    private void assertVoucherBalanced(String bizType) {
        List<Long> ids = jdbc.queryForList("""
                select id from fin_voucher where biz_type = ? order by id desc limit 1
                """, Long.class, bizType);
        assertTrue(!ids.isEmpty(), bizType + " 凭证应存在");
        Map<String, Object> totals = jdbc.queryForMap("""
                select coalesce(sum(debit), 0) as debit, coalesce(sum(credit), 0) as credit
                from fin_voucher_line where voucher_id = ?
                """, ids.get(0));
        BigDecimal debit = new BigDecimal(String.valueOf(totals.get("debit")));
        BigDecimal credit = new BigDecimal(String.valueOf(totals.get("credit")));
        assertEquals(0, debit.compareTo(credit), bizType + " 凭证借贷必须平衡");
        assertNotNull(ids.get(0));
    }
}
