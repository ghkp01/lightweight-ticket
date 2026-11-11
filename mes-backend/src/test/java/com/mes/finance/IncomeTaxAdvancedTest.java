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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 汇算增强：纳税调整规则自动取数、小型微利、研发加计扣除、分支机构分摊。
 */
@SpringBootTest
@ActiveProfiles("test")
class IncomeTaxAdvancedTest {

    @Autowired
    private IncomeTaxService incomeTax;
    @Autowired
    private TaxAdjustmentService adjustments;
    @Autowired
    private RdProjectService rdProjects;
    @Autowired
    private TaxBranchService branches;
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
        // 收入 100 万
        voucher("ADV-REV", LocalDate.of(2092, 3, 31), "1002", "6001",
                new BigDecimal("1000000.00"));
        // 业务招待费 1 万、广告费 20 万、罚款 5 千
        voucher("ADV-ENT", LocalDate.of(2092, 4, 10), "660201", "1002", new BigDecimal("10000.00"));
        voucher("ADV-ADV", LocalDate.of(2092, 4, 15), "660202", "1002", new BigDecimal("200000.00"));
        voucher("ADV-FINE", LocalDate.of(2092, 5, 10), "660203", "1002", new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("规则自动调增、研发加计、小型微利 5% 与分支机构分摊")
    void advancedSettlement() {
        long projectId = rdProjects.create(new RdProjectService.ProjectRequest(
                "高效切割工艺研发", 2092, "技术部", "辅助账测试"));
        rdProjects.addExpense(projectId, new RdProjectService.ExpenseRequest(
                LocalDate.of(2092, 6, 30), "PERSONNEL", new BigDecimal("50000.00"), "研发人员"));

        List<Map<String, Object>> preview = adjustments.preview(2092);
        assertEquals(0, new BigDecimal("5000.00").compareTo(amountOf(preview, "ENTERTAINMENT")));
        assertEquals(0, new BigDecimal("50000.00").compareTo(amountOf(preview, "ADVERTISING")));
        assertEquals(0, new BigDecimal("5000.00").compareTo(amountOf(preview, "FINE")));
        assertEquals(0, new BigDecimal("50000.00").compareTo(amountOf(preview, "RD_SUPER")));

        List<IncomeTaxService.Adjustment> lines = preview.stream()
                .map(row -> new IncomeTaxService.Adjustment(
                        String.valueOf(row.get("type")), String.valueOf(row.get("item")),
                        new BigDecimal(String.valueOf(row.get("amount"))),
                        String.valueOf(row.get("remark")), String.valueOf(row.get("ruleCode"))))
                .toList();

        branch("ADV-BR-A", "0.300000");
        branch("ADV-BR-B", "0.200000");

        Map<String, Object> settlement = incomeTax.generate(new IncomeTaxService.SettleRequest(
                2092, new BigDecimal("0.25"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                lines, "小型微利测试", "SMALL", 100, new BigDecimal("10000000.00"), true));

        // 利润 785000；调增 60000、调减 50000 → 应纳税所得额 795000
        assertEquals(0, new BigDecimal("785000.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("accountingProfit")))));
        assertEquals(0, new BigDecimal("60000.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("adjustmentAdd")))));
        assertEquals(0, new BigDecimal("50000.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("adjustmentDeduct")))));
        assertEquals(0, new BigDecimal("795000.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("taxableIncome")))));
        // 25% 应纳税额 198750，小型微利减免 159000，实际应补 39750
        assertEquals(0, new BigDecimal("198750.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("taxPayable")))));
        assertEquals(0, new BigDecimal("159000.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("smallRelief")))));
        assertEquals(0, new BigDecimal("39750.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("payableTax")))));

        long id = ((Number) settlement.get("id")).longValue();
        Map<String, Object> filed = incomeTax.file(id);
        assertEquals("FILED", filed.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocations = (List<Map<String, Object>>) filed.get("allocations");
        assertEquals(3, allocations.size());
        BigDecimal allocated = allocations.stream()
                .map(row -> new BigDecimal(String.valueOf(row.get("amount"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("39750.00").compareTo(allocated));
        assertEquals(0, new BigDecimal("11925.00").compareTo(amountByName(allocations, "ADV-BR-A")));
        assertEquals(0, new BigDecimal("7950.00").compareTo(amountByName(allocations, "ADV-BR-B")));
        assertEquals(0, new BigDecimal("19875.00").compareTo(amountByName(allocations, "总机构")));

        // 超过 300 万不能再按小型微利
        assertThrows(com.mes.common.BizException.class, () -> incomeTax.generate(
                new IncomeTaxService.SettleRequest(2091, new BigDecimal("0.25"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null,
                        "SMALL", 500, new BigDecimal("60000000.00"), true)));
    }

    // ---------------------------------------------------------------- 工具

    private void voucher(String summary, LocalDate date, String debitAccount, String creditAccount,
                         BigDecimal amount) {
        ledger.createManualVoucher(date, summary, "CNY", BigDecimal.ONE,
                List.of(new PostingService.ManualLine(debitAccount, summary, amount, BigDecimal.ZERO),
                        new PostingService.ManualLine(creditAccount, summary,
                                BigDecimal.ZERO, amount)));
    }

    private void branch(String name, String ratio) {
        branches.save(new TaxBranchService.BranchRequest(
                null, name, "TAX-" + name, new BigDecimal(ratio), true, null));
    }

    private BigDecimal amountOf(List<Map<String, Object>> rows, String ruleCode) {
        return rows.stream()
                .filter(row -> ruleCode.equals(String.valueOf(row.get("ruleCode"))))
                .map(row -> new BigDecimal(String.valueOf(row.get("amount"))))
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private BigDecimal amountByName(List<Map<String, Object>> rows, String name) {
        return rows.stream()
                .filter(row -> name.equals(String.valueOf(row.get("branchName"))))
                .map(row -> new BigDecimal(String.valueOf(row.get("amount"))))
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private void cleanup() {
        jdbc.update("""
                delete from fin_voucher_line where voucher_id in (
                    select id from fin_voucher where summary in
                        ('ADV-REV', 'ADV-ENT', 'ADV-ADV', 'ADV-FINE')
                    or (biz_type = 'CIT_ACCRUAL'
                        and biz_id in (select id from tax_income_settlement where tax_year = 2092)))
                """);
        jdbc.update("""
                delete from fin_voucher where summary in
                    ('ADV-REV', 'ADV-ENT', 'ADV-ADV', 'ADV-FINE')
                    or (biz_type = 'CIT_ACCRUAL'
                        and biz_id in (select id from tax_income_settlement where tax_year = 2092))
                """);
        jdbc.update("delete from tax_income_settlement where tax_year in (2091, 2092)");
        jdbc.update("delete from tax_branch where name like 'ADV-BR-%'");
        jdbc.update("delete from rd_project where tax_year = 2092");
    }
}
