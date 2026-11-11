package com.mes.finance;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记账引擎：业务单据 → 凭证。
 *
 * <p>三条硬规则：</p>
 * <ol>
 *   <li>借贷必须平，且不允许一条分录同时有借有贷；</li>
 *   <li>已关账期间不允许记账；</li>
 *   <li>同一业务单据只能生成一张凭证（biz_type + biz_id 唯一），重复调用直接返回原凭证。</li>
 * </ol>
 *
 * <p>凭证按本位币（人民币）记账；外币单据的记账汇率由 {@link #rateFor} 取单据日期当天或之前最近一条，
 * 没维护汇率就拒绝记账，而不是默认按 1 折算。</p>
 */
@Service
public class PostingService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 自动记账用的角色科目，避免把科目编码硬编码到业务代码里 */
    private static final Map<String, String> ROLE_LABELS = Map.ofEntries(
            Map.entry("BANK", "银行存款"),
            Map.entry("AR", "应收账款"),
            Map.entry("AR_ADVANCE", "预收账款"),
            Map.entry("AP", "应付账款"),
            Map.entry("INVENTORY", "库存商品"),
            Map.entry("OUTPUT_TAX", "销项税额"),
            Map.entry("INPUT_TAX", "进项税额"),
            Map.entry("FIXED_ASSET", "固定资产"),
            Map.entry("ACCUM_DEPRECIATION", "累计折旧"),
            Map.entry("DEPRECIATION_EXPENSE", "折旧费用"),
            Map.entry("ASSET_DISPOSAL", "资产处置损益"),
            Map.entry("VAT_PAYABLE", "未交增值税"),
            Map.entry("SURTAX_PAYABLE", "应交附加税"),
            Map.entry("SURTAX_EXPENSE", "税金及附加"),
            Map.entry("INCOME_TAX_EXPENSE", "所得税费用"),
            Map.entry("INCOME_TAX_PAYABLE", "应交所得税"),
            Map.entry("REVENUE", "主营业务收入"),
            Map.entry("COST", "主营业务成本"));

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;

    public PostingService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
    }

    public record PostingLine(String role, String summary, BigDecimal debit, BigDecimal credit) {
    }

    public record ManualLine(String accountCode, String summary, BigDecimal debit, BigDecimal credit) {
    }

    /** 自动记账：按角色找科目，生成一张借贷平衡的凭证 */
    @Transactional
    public long post(LocalDate voucherDate, String bizType, long bizId, String bizNo,
                     String currency, BigDecimal exchangeRate, String summary,
                     List<PostingLine> lines) {
        List<Long> existing = jdbcTemplate.queryForList(
                "select id from fin_voucher where biz_type = ? and biz_id = ?",
                Long.class, bizType, bizId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        if (lines == null || lines.isEmpty()) {
            throw new BizException(1002, "凭证没有分录，不能记账");
        }
        String period = periodOf(voucherDate);
        assertOpen(period);

        List<Object[]> rows = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        int lineNo = 1;
        for (PostingLine line : lines) {
            BigDecimal debitAmount = money(line.debit());
            BigDecimal creditAmount = money(line.credit());
            if (debitAmount.signum() == 0 && creditAmount.signum() == 0) {
                continue;
            }
            if (debitAmount.signum() > 0 && creditAmount.signum() > 0) {
                throw new BizException(1002, "同一条分录不能同时有借方和贷方");
            }
            String account = accountOfRole(line.role());
            rows.add(new Object[]{lineNo++, account, blankToNull(line.summary()),
                    debitAmount, creditAmount});
            debit = debit.add(debitAmount);
            credit = credit.add(creditAmount);
        }
        if (rows.isEmpty()) {
            throw new BizException(1002, "凭证金额为 0，不能记账");
        }
        if (debit.compareTo(credit) != 0) {
            throw new BizException(1002, "凭证借贷不平：借方 " + debit + "，贷方 " + credit);
        }
        long voucherId = insertVoucher(voucherDate, period, bizType, bizId, bizNo,
                currency, exchangeRate, summary, rows);
        return voucherId;
    }

    /** 手工凭证：用于期初余额、调整分录；科目编码由用户直接指定 */
    @Transactional
    public long postManual(LocalDate voucherDate, String summary, String currency,
                           BigDecimal exchangeRate, List<ManualLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BizException(1002, "凭证没有分录，不能记账");
        }
        String period = periodOf(voucherDate);
        assertOpen(period);
        List<Object[]> rows = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        int lineNo = 1;
        for (ManualLine line : lines) {
            BigDecimal debitAmount = money(line.debit());
            BigDecimal creditAmount = money(line.credit());
            if (debitAmount.signum() == 0 && creditAmount.signum() == 0) {
                continue;
            }
            if (debitAmount.signum() > 0 && creditAmount.signum() > 0) {
                throw new BizException(1002, "同一条分录不能同时有借方和贷方");
            }
            Map<String, Object> account = accountByCode(line.accountCode());
            if (account == null) {
                throw new BizException(1002, "会计科目不存在：" + line.accountCode());
            }
            if (!Boolean.TRUE.equals(account.get("enabled"))) {
                throw new BizException(1002, "会计科目已停用：" + line.accountCode());
            }
            rows.add(new Object[]{lineNo++, String.valueOf(account.get("code")),
                    blankToNull(line.summary()), debitAmount, creditAmount});
            debit = debit.add(debitAmount);
            credit = credit.add(creditAmount);
        }
        if (rows.isEmpty()) {
            throw new BizException(1002, "凭证金额为 0，不能记账");
        }
        if (debit.compareTo(credit) != 0) {
            throw new BizException(1002, "凭证借贷不平：借方 " + debit + "，贷方 " + credit);
        }
        Long bizId = jdbcTemplate.queryForObject("select nextval('fin_manual_voucher_seq')", Long.class);
        return insertVoucher(voucherDate, period, "MANUAL", bizId == null ? 0 : bizId,
                "手工凭证", currency == null ? "CNY" : currency,
                exchangeRate == null ? BigDecimal.ONE : exchangeRate, summary, rows);
    }

    /** 红冲：生成一张借贷相反的新凭证，原凭证标记 REVERSED；原单据不能重复红冲 */
    @Transactional
    public long reverse(long voucherId, String reason) {
        List<Map<String, Object>> vouchers = jdbcTemplate.queryForList("""
                select id, voucher_date, period, biz_type as "bizType", biz_id as "bizId",
                       biz_no as "bizNo", currency, exchange_rate as "exchangeRate",
                       status, summary
                from fin_voucher where id = ?
                """, voucherId);
        if (vouchers.isEmpty()) {
            throw new BizException(1002, "凭证不存在");
        }
        Map<String, Object> voucher = vouchers.get(0);
        if (!"POSTED".equals(voucher.get("status"))) {
            throw new BizException(1002, "凭证已红冲，不能重复操作");
        }
        LocalDate today = LocalDate.now();
        String period = periodOf(today);
        assertOpen(period);
        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                select account_code as "accountCode", summary, debit, credit
                from fin_voucher_line where voucher_id = ? order by line_no
                """, voucherId);
        List<Object[]> rows = new ArrayList<>();
        int lineNo = 1;
        for (Map<String, Object> line : lines) {
            rows.add(new Object[]{lineNo++, String.valueOf(line.get("accountCode")),
                    "红冲：" + (line.get("summary") == null ? "" : line.get("summary")),
                    money((BigDecimal) line.get("credit")), money((BigDecimal) line.get("debit"))});
        }
        String summary = "红冲凭证：" + (reason == null || reason.isBlank() ? "业务取消" : reason);
        long reversalId = insertVoucher(today, period,
                String.valueOf(voucher.get("bizType")) + "_REV",
                ((Number) voucher.get("bizId")).longValue(),
                voucher.get("bizNo") == null ? null : String.valueOf(voucher.get("bizNo")),
                String.valueOf(voucher.get("currency")),
                (BigDecimal) voucher.get("exchangeRate"), summary, rows);
        jdbcTemplate.update("update fin_voucher set reversal_of = ? where id = ?", voucherId, reversalId);
        jdbcTemplate.update("update fin_voucher set status = 'REVERSED' where id = ?", voucherId);
        return reversalId;
    }

    /** 取单据日期当天或之前最近一条汇率；人民币固定 1，外币没维护汇率直接拒绝 */
    public BigDecimal rateFor(String currency, LocalDate date) {
        String code = currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase();
        if ("CNY".equals(code)) {
            return BigDecimal.ONE;
        }
        List<BigDecimal> rates = jdbcTemplate.queryForList("""
                select rate from fin_exchange_rate
                where currency = ? and rate_date <= ?
                order by rate_date desc limit 1
                """, BigDecimal.class, code, date);
        if (rates.isEmpty()) {
            throw new BizException(1002, "未维护 " + code + " 在 " + date + " 及之前的记账汇率，不能记账");
        }
        return rates.get(0);
    }

    public String periodOf(LocalDate date) {
        return date.format(PERIOD);
    }

    public void assertOpen(String period) {
        List<String> status = jdbcTemplate.queryForList(
                "select status from fin_period where period = ?", String.class, period);
        if (!status.isEmpty() && "CLOSED".equals(status.get(0))) {
            throw new BizException(1002, "会计期间 " + period + " 已关账，不能记账");
        }
    }

    // ---------------------------------------------------------------- 内部

    private long insertVoucher(LocalDate voucherDate, String period, String bizType, long bizId,
                               String bizNo, String currency, BigDecimal exchangeRate,
                               String summary, List<Object[]> lines) {
        String voucherNo = codeGenerator.next("FV");
        Long id = jdbcTemplate.queryForObject("""
                insert into fin_voucher (voucher_no, voucher_date, period, biz_type, biz_id, biz_no,
                                         currency, exchange_rate, summary, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, voucherNo, voucherDate, period, bizType, bizId, blankToNull(bizNo),
                currency == null ? "CNY" : currency, exchangeRate == null ? BigDecimal.ONE : exchangeRate,
                blankToNull(summary), CurrentUser.name());
        if (id == null) {
            throw new BizException(1002, "凭证生成失败");
        }
        for (Object[] line : lines) {
            jdbcTemplate.update("""
                    insert into fin_voucher_line (voucher_id, line_no, account_code, summary, debit, credit)
                    values (?, ?, ?, ?, ?, ?)
                    """, id, line[0], line[1], line[2], line[3], line[4]);
        }
        return id;
    }

    private String accountOfRole(String role) {
        if (role == null || role.isBlank()) {
            throw new BizException(1002, "凭证分录缺少科目角色");
        }
        List<String> codes = jdbcTemplate.queryForList(
                "select code from fin_account where role = ? and enabled", String.class, role);
        if (codes.isEmpty()) {
            throw new BizException(1002, "财务科目未配置：" + ROLE_LABELS.getOrDefault(role, role)
                    + "（请到总账-科目里补一个角色为 " + role + " 的科目）");
        }
        return codes.get(0);
    }

    private Map<String, Object> accountByCode(String code) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select code, name, enabled from fin_account where code = ?", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
