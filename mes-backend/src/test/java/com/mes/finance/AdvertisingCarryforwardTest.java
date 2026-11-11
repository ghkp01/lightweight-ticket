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

/**
 * 广告费超限结转：超限调增、下一年度结转扣除、跨年台账余额。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdvertisingCarryforwardTest {

    @Autowired
    private IncomeTaxService incomeTax;
    @Autowired
    private TaxAdjustmentService adjustments;
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
        // 2093：收入 100 万，广告费 20 万（限额 15 万，超限 5 万）
        voucher("ADV93-REV", LocalDate.of(2093, 3, 31), "1002", "6001",
                new BigDecimal("1000000.00"));
        voucher("ADV93-ADV", LocalDate.of(2093, 6, 30), "660202", "1002",
                new BigDecimal("200000.00"));
        // 2094：收入 100 万，广告费 10 万（当年额度用满后结转扣除 5 万）
        voucher("ADV94-REV", LocalDate.of(2094, 3, 31), "1002", "6001",
                new BigDecimal("1000000.00"));
        voucher("ADV94-ADV", LocalDate.of(2094, 6, 30), "660202", "1002",
                new BigDecimal("100000.00"));
    }

    @Test
    @DisplayName("广告费超限 5 万结转到次年扣除，台账余额归零")
    void advertisingCarryforward() {
        List<Map<String, Object>> preview2093 = adjustments.preview(2093);
        assertEquals(0, new BigDecimal("50000.00").compareTo(amountOf(preview2093, "ADD")));
        assertEquals(0, new BigDecimal("50000.00").compareTo(carryOf(preview2093)));

        Map<String, Object> settlement2093 = incomeTax.generate(new IncomeTaxService.SettleRequest(
                2093, new BigDecimal("0.25"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                toAdjustments(preview2093), "2093 汇算"));
        assertEquals(0, new BigDecimal("50000.00").compareTo(
                new BigDecimal(String.valueOf(settlement2093.get("adjustmentAdd")))));
        incomeTax.file(((Number) settlement2093.get("id")).longValue());

        List<Map<String, Object>> preview2094 = adjustments.preview(2094);
        assertEquals(0, new BigDecimal("50000.00").compareTo(amountOf(preview2094, "DEDUCT")));
        assertEquals(0, BigDecimal.ZERO.compareTo(carryOf(preview2094)));

        Map<String, Object> settlement2094 = incomeTax.generate(new IncomeTaxService.SettleRequest(
                2094, new BigDecimal("0.25"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                toAdjustments(preview2094), "2094 汇算"));
        assertEquals(0, new BigDecimal("50000.00").compareTo(
                new BigDecimal(String.valueOf(settlement2094.get("adjustmentDeduct")))));
        incomeTax.file(((Number) settlement2094.get("id")).longValue());

        var page = incomeTax.adjustmentLedger("ADVERTISING", 2093, 2094, 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) (List<?>) page.records();
        assertEquals(2, rows.size());
        Map<String, Object> first = rows.stream()
                .filter(row -> ((Number) row.get("taxYear")).intValue() == 2093)
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("50000.00").compareTo(
                new BigDecimal(String.valueOf(first.get("carryforwardBalance")))));
        Map<String, Object> second = rows.stream()
                .filter(row -> ((Number) row.get("taxYear")).intValue() == 2094)
                .findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(
                new BigDecimal(String.valueOf(second.get("carryforwardBalance")))));
    }

    // ---------------------------------------------------------------- 工具

    private List<IncomeTaxService.Adjustment> toAdjustments(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> new IncomeTaxService.Adjustment(
                        String.valueOf(row.get("type")), String.valueOf(row.get("item")),
                        new BigDecimal(String.valueOf(row.get("amount"))),
                        row.get("remark") == null ? null : String.valueOf(row.get("remark")),
                        String.valueOf(row.get("ruleCode"))))
                .toList();
    }

    private BigDecimal amountOf(List<Map<String, Object>> rows, String type) {
        return rows.stream()
                .filter(row -> "ADVERTISING".equals(String.valueOf(row.get("ruleCode")))
                        && type.equals(String.valueOf(row.get("type"))))
                .map(row -> new BigDecimal(String.valueOf(row.get("amount"))))
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private BigDecimal carryOf(List<Map<String, Object>> rows) {
        return rows.stream()
                .filter(row -> "ADVERTISING".equals(String.valueOf(row.get("ruleCode"))))
                .map(row -> new BigDecimal(String.valueOf(row.get("carryforwardOut"))))
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private void voucher(String summary, LocalDate date, String debitAccount, String creditAccount,
                         BigDecimal amount) {
        ledger.createManualVoucher(date, summary, "CNY", BigDecimal.ONE,
                List.of(new PostingService.ManualLine(debitAccount, summary, amount, BigDecimal.ZERO),
                        new PostingService.ManualLine(creditAccount, summary,
                                BigDecimal.ZERO, amount)));
    }

    private void cleanup() {
        jdbc.update("""
                delete from fin_voucher_line where voucher_id in (
                    select id from fin_voucher where summary in
                        ('ADV93-REV', 'ADV93-ADV', 'ADV94-REV', 'ADV94-ADV')
                    or (biz_type = 'CIT_ACCRUAL'
                        and biz_id in (select id from tax_income_settlement where tax_year in (2093, 2094)))
                )
                """);
        jdbc.update("""
                delete from fin_voucher where summary in
                    ('ADV93-REV', 'ADV93-ADV', 'ADV94-REV', 'ADV94-ADV')
                    or (biz_type = 'CIT_ACCRUAL'
                        and biz_id in (select id from tax_income_settlement where tax_year in (2093, 2094)))
                """);
        jdbc.update("delete from tax_income_settlement where tax_year in (2093, 2094)");
        // 其他用例（如 2092 高级汇算）申报后会在台账留下广告费结转余额，
        // 会串进本用例的 priorCarry(2093)，这里清掉 2093 之前的残留台账保证用例隔离
        jdbc.update("""
                delete from tax_adjustment_ledger
                where rule_code = 'ADVERTISING' and tax_year < 2093
                """);
    }
}
