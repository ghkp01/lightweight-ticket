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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业所得税汇算清缴：会计利润取总账、纳税调整、亏损弥补、计提与补退税。
 */
@SpringBootTest
@ActiveProfiles("test")
class IncomeTaxTest {

    @Autowired
    private IncomeTaxService service;
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
    @DisplayName("亏损年度可结转，次年调增后用亏损抵减并完成补税")
    void settlementLifecycle() {
        // 2087 年只发生一笔费用 1000，形成亏损
        ledger.createManualVoucher(LocalDate.of(2087, 6, 30), "测试亏损", "CNY", BigDecimal.ONE,
                List.of(new PostingService.ManualLine("6602", "测试亏损",
                                new BigDecimal("1000.00"), BigDecimal.ZERO),
                        new PostingService.ManualLine("1002", "测试亏损",
                                BigDecimal.ZERO, new BigDecimal("1000.00"))));

        Map<String, Object> lossYear = service.generate(new IncomeTaxService.SettleRequest(
                2087, new BigDecimal("0.25"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), "亏损年度"));
        assertEquals(0, new BigDecimal("-1000.00").compareTo(
                new BigDecimal(String.valueOf(lossYear.get("taxableIncome")))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                new BigDecimal(String.valueOf(lossYear.get("taxPayable")))));
        long lossId = ((Number) lossYear.get("id")).longValue();
        service.file(lossId);

        Map<String, Object> carry = service.lossCarryforward(2088);
        assertEquals(0, new BigDecimal("1000.00").compareTo(
                new BigDecimal(String.valueOf(carry.get("available")))));

        Map<String, Object> settlement = service.generate(new IncomeTaxService.SettleRequest(
                2088, new BigDecimal("0.25"), BigDecimal.ZERO, new BigDecimal("100.00"),
                new BigDecimal("1000.00"),
                List.of(new IncomeTaxService.Adjustment("ADD", "业务招待费调增",
                        new BigDecimal("2000.00"), "测试")),
                "汇算清缴"));
        // 0 + 2000 - 1000 = 1000；税 250；预缴 100 → 应补 150
        assertEquals(0, new BigDecimal("1000.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("taxableIncome")))));
        assertEquals(0, new BigDecimal("250.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("taxPayable")))));
        assertEquals(0, new BigDecimal("150.00").compareTo(
                new BigDecimal(String.valueOf(settlement.get("payableTax")))));
        long id = ((Number) settlement.get("id")).longValue();

        Map<String, Object> filed = service.file(id);
        assertEquals("FILED", filed.get("status"));
        assertVoucherBalanced("CIT_ACCRUAL", id);

        Map<String, Object> paid = service.pay(id, "银行扣款");
        assertEquals("PAID", paid.get("status"));
        assertVoucherBalanced("CIT_PAYMENT", id);

        assertThrows(BizException.class, () -> service.generate(
                new IncomeTaxService.SettleRequest(2088, new BigDecimal("0.25"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null)));

        // 亏损已被 2088 年用完
        assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(
                String.valueOf(service.lossCarryforward(2089).get("available")))));
    }

    @Test
    @DisplayName("弥补亏损不能超过可弥补余额")
    void lossCarryforwardIsBounded() {
        assertThrows(BizException.class, () -> service.generate(new IncomeTaxService.SettleRequest(
                2091, new BigDecimal("0.25"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("999999.00"), List.of(), null)));
    }

    // ---------------------------------------------------------------- 工具

    private void cleanup() {
        jdbc.update("""
                delete from fin_voucher_line where voucher_id in (
                    select id from fin_voucher where
                        biz_type in ('CIT_ACCRUAL', 'CIT_PAYMENT')
                        and biz_id in (select id from tax_income_settlement where tax_year in (2087, 2088))
                        or (biz_type = 'MANUAL' and summary = '测试亏损'))
                """);
        jdbc.update("""
                delete from fin_voucher where
                    (biz_type in ('CIT_ACCRUAL', 'CIT_PAYMENT')
                     and biz_id in (select id from tax_income_settlement where tax_year in (2087, 2088)))
                    or (biz_type = 'MANUAL' and summary = '测试亏损')
                """);
        jdbc.update("delete from tax_income_settlement where tax_year in (2087, 2088)");
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
