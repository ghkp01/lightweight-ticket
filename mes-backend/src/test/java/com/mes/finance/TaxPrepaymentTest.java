package com.mes.finance;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业所得税季度预缴：累计利润计算、扣除以前季度、年度汇算自动带出已预缴。
 */
@SpringBootTest
@ActiveProfiles("test")
class TaxPrepaymentTest {

    @Autowired
    private TaxPrepaymentService prepayments;
    @Autowired
    private IncomeTaxService incomeTax;
    @Autowired
    private FinanceLedgerService ledger;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        cleanup();
    }

    @Test
    @DisplayName("Q1 无利润不预缴，Q2 按累计利润预缴，Q3 扣除已预缴，年度汇算自动带出")
    void quarterlyLifecycle() {
        Map<String, Object> q1 = prepayments.generate(new TaxPrepaymentService.PrepayRequest(
                2089, 1, new BigDecimal("0.25"), "Q1"));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                new BigDecimal(String.valueOf(q1.get("currentPrepaid")))));
        long q1Id = ((Number) q1.get("id")).longValue();
        prepayments.file(q1Id);
        prepayments.pay(q1Id, null);

        ledger.createManualVoucher(LocalDate.of(2089, 5, 20), "预缴测试收入", "CNY", BigDecimal.ONE,
                List.of(new PostingService.ManualLine("1002", "预缴测试收入",
                                new BigDecimal("10000.00"), BigDecimal.ZERO),
                        new PostingService.ManualLine("6001", "预缴测试收入",
                                BigDecimal.ZERO, new BigDecimal("10000.00"))));

        Map<String, Object> q2 = prepayments.generate(new TaxPrepaymentService.PrepayRequest(
                2089, 2, new BigDecimal("0.25"), "Q2"));
        assertEquals(0, new BigDecimal("10000.00").compareTo(
                new BigDecimal(String.valueOf(q2.get("ytdProfit")))));
        assertEquals(0, new BigDecimal("2500.00").compareTo(
                new BigDecimal(String.valueOf(q2.get("currentPrepaid")))));
        long q2Id = ((Number) q2.get("id")).longValue();
        prepayments.file(q2Id);
        assertVoucherBalanced("CIT_PREPAY", q2Id);
        prepayments.pay(q2Id, "缴款");
        assertVoucherBalanced("CIT_PREPAY_PAYMENT", q2Id);

        Map<String, Object> q3 = prepayments.generate(new TaxPrepaymentService.PrepayRequest(
                2089, 3, new BigDecimal("0.25"), "Q3"));
        assertEquals(0, new BigDecimal("2500.00").compareTo(
                new BigDecimal(String.valueOf(q3.get("prepaidBefore")))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                new BigDecimal(String.valueOf(q3.get("currentPrepaid")))));

        // 年度汇算：利润 10000，税 2500，自动带出已预缴 2500，应补 0
        Map<String, Object> annual = incomeTax.generate(new IncomeTaxService.SettleRequest(
                2089, new BigDecimal("0.25"), BigDecimal.ZERO, null, BigDecimal.ZERO,
                List.of(), "年度汇算"));
        assertEquals(0, new BigDecimal("10000.00").compareTo(
                new BigDecimal(String.valueOf(annual.get("accountingProfit")))));
        assertEquals(0, new BigDecimal("2500.00").compareTo(
                new BigDecimal(String.valueOf(annual.get("prepaidTax")))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                new BigDecimal(String.valueOf(annual.get("payableTax")))));
    }

    // ---------------------------------------------------------------- 工具

    private void cleanup() {
        jdbc.update("""
                delete from fin_voucher_line where voucher_id in (
                    select id from fin_voucher where
                        (biz_type in ('CIT_PREPAY', 'CIT_PREPAY_PAYMENT')
                         and biz_id in (select id from tax_prepayment where tax_year = 2089))
                        or (biz_type in ('CIT_ACCRUAL', 'CIT_PAYMENT')
                            and biz_id in (select id from tax_income_settlement where tax_year = 2089))
                        or (biz_type = 'MANUAL' and summary = '预缴测试收入'))
                """);
        jdbc.update("""
                delete from fin_voucher where
                    (biz_type in ('CIT_PREPAY', 'CIT_PREPAY_PAYMENT')
                     and biz_id in (select id from tax_prepayment where tax_year = 2089))
                    or (biz_type in ('CIT_ACCRUAL', 'CIT_PAYMENT')
                        and biz_id in (select id from tax_income_settlement where tax_year = 2089))
                    or (biz_type = 'MANUAL' and summary = '预缴测试收入')
                """);
        jdbc.update("delete from tax_prepayment where tax_year = 2089");
        jdbc.update("delete from tax_income_settlement where tax_year = 2089");
    }

    private void assertVoucherBalanced(String bizType, long bizId) {
        List<Long> ids = jdbc.queryForList("""
                select id from fin_voucher where biz_type = ? and biz_id = ?
                """, Long.class, bizType, bizId);
        assertTrue(!ids.isEmpty(), bizType + " 凭证应存在");
        Map<String, Object> totals = jdbc.queryForMap("""
                select coalesce(sum(debit), 0) as debit, coalesce(sum(credit), 0) as credit
                from fin_voucher_line where voucher_id = ?
                """, ids.get(0));
        assertEquals(0, new BigDecimal(String.valueOf(totals.get("debit")))
                .compareTo(new BigDecimal(String.valueOf(totals.get("credit")))));
    }
}
