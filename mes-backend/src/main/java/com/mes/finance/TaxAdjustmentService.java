package com.mes.finance;

import com.mes.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纳税调整规则：按科目发生额或研发辅助账自动计算调增/调减，并处理跨年结转。
 *
 * <p>规则口径：</p>
 * <ul>
 *   <li>业务招待费：按发生额 60% 与营业收入 5‰ 孰低扣除，超出部分调增，不能结转；</li>
 *   <li>广告费和业务宣传费：按营业收入 15% 限额扣除，超出部分调增并结转以后年度；
 *       以后年度先用当年额度，再用以前年度结转额（形成调减），结转余额记入跨年台账；</li>
 *   <li>罚款、滞纳金：全额调增；</li>
 *   <li>研发费用加计扣除：按研发辅助账归集金额 × 加计比例调减，默认 100%。</li>
 * </ul>
 */
@Service
public class TaxAdjustmentService {

    public static final String ADVERTISING_RULE = "ADVERTISING";

    private final JdbcTemplate jdbcTemplate;

    public TaxAdjustmentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 前端带出的调整项；传了 ruleCode 的金额由后端按规则重算 */
    public record Adjustment(String type, String item, BigDecimal amount, String remark, String ruleCode) {

        public Adjustment(String type, String item, BigDecimal amount, String remark) {
            this(type, item, amount, remark, null);
        }
    }

    public record ResolvedAdjustment(String type, String item, BigDecimal amount, String remark,
                                     String ruleCode, BigDecimal baseAmount, BigDecimal limitAmount,
                                     BigDecimal carryforwardOut) {
    }

    public List<Map<String, Object>> rules() {
        return jdbcTemplate.queryForList("""
                select code, name, adjust_type as "type", method,
                       expense_role as "expenseRole", ratio1, ratio2, enabled, remark
                from tax_adjustment_rule where enabled order by code
                """);
    }

    /** 按规则试算指定年度的纳税调整，金额为 0 的规则不返回 */
    public List<Map<String, Object>> preview(int year) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> rule : rules()) {
            for (ResolvedAdjustment resolved : resolve(rule, year)) {
                if (resolved.amount().signum() <= 0) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ruleCode", resolved.ruleCode());
                row.put("item", resolved.item());
                row.put("type", resolved.type());
                row.put("amount", resolved.amount());
                row.put("baseAmount", resolved.baseAmount());
                row.put("limitAmount", resolved.limitAmount());
                row.put("carryforwardOut", resolved.carryforwardOut());
                row.put("remark", resolved.remark());
                result.add(row);
            }
        }
        return result;
    }

    /** 汇算生成时解析调整项：规则项以后端计算为准，手工项按录入金额 */
    public List<ResolvedAdjustment> compute(List<Adjustment> adjustments, int year) {
        List<ResolvedAdjustment> result = new ArrayList<>();
        if (adjustments == null) {
            return result;
        }
        Set<String> ruleCodes = new LinkedHashSet<>();
        List<Adjustment> manualLines = new ArrayList<>();
        for (Adjustment adjustment : adjustments) {
            if (adjustment == null) {
                continue;
            }
            if (adjustment.ruleCode() != null && !adjustment.ruleCode().isBlank()) {
                ruleCodes.add(adjustment.ruleCode().trim());
            } else {
                manualLines.add(adjustment);
            }
        }
        // 同一规则只算一次：广告费会同时产出「超限调增」和「结转扣除」两条
        for (String code : ruleCodes) {
            List<Map<String, Object>> rules = jdbcTemplate.queryForList("""
                    select code, name, adjust_type as "type", method,
                           expense_role as "expenseRole", ratio1, ratio2, enabled, remark
                    from tax_adjustment_rule where code = ?
                    """, code);
            if (rules.isEmpty() || !Boolean.TRUE.equals(rules.get(0).get("enabled"))) {
                throw new BizException(1002, "纳税调整规则不存在或已停用：" + code);
            }
            for (ResolvedAdjustment resolved : resolve(rules.get(0), year)) {
                if (resolved.amount().signum() > 0) {
                    result.add(resolved);
                }
            }
        }
        for (Adjustment adjustment : manualLines) {
            if (adjustment.amount() == null || adjustment.amount().signum() <= 0) {
                continue;
            }
            String type = adjustment.type() == null ? "" : adjustment.type().trim().toUpperCase();
            if (!List.of("ADD", "DEDUCT").contains(type)) {
                throw new BizException(1002, "纳税调整类型只支持 ADD / DEDUCT");
            }
            if (adjustment.item() == null || adjustment.item().isBlank()) {
                throw new BizException(1002, "请填写纳税调整项目");
            }
            result.add(new ResolvedAdjustment(type, adjustment.item().trim(),
                    money(adjustment.amount()), blankToNull(adjustment.remark()),
                    null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return result;
    }

    /** 某规则截至上一年度末的结转余额 */
    public BigDecimal priorCarry(int year, String ruleCode) {
        List<BigDecimal> balances = jdbcTemplate.queryForList("""
                select carryforward_balance from tax_adjustment_ledger
                where rule_code = ? and tax_year < ?
                order by tax_year desc, id desc limit 1
                """, BigDecimal.class, ruleCode, year);
        return balances.isEmpty() ? BigDecimal.ZERO : money(balances.get(0));
    }

    // ---------------------------------------------------------------- 内部

    private List<ResolvedAdjustment> resolve(Map<String, Object> rule, int year) {
        String code = String.valueOf(rule.get("code"));
        String name = String.valueOf(rule.get("name"));
        String type = String.valueOf(rule.get("type"));
        String method = String.valueOf(rule.get("method"));
        BigDecimal ratio1 = (BigDecimal) rule.get("ratio1");
        BigDecimal ratio2 = (BigDecimal) rule.get("ratio2");
        String remark = rule.get("remark") == null ? null : String.valueOf(rule.get("remark"));
        List<ResolvedAdjustment> rows = new ArrayList<>();
        switch (method) {
            case "ENTERTAINMENT" -> {
                BigDecimal base = roleAmount(String.valueOf(rule.get("expenseRole")), year);
                BigDecimal limit = min(money(base.multiply(ratio1)),
                        money(revenue(year).multiply(ratio2)));
                BigDecimal amount = base.subtract(limit);
                if (amount.signum() < 0) {
                    amount = BigDecimal.ZERO;
                }
                rows.add(new ResolvedAdjustment(type, name, money(amount), remark, code,
                        money(base), money(limit), BigDecimal.ZERO));
            }
            case "ADVERTISING" -> {
                BigDecimal base = roleAmount(String.valueOf(rule.get("expenseRole")), year);
                BigDecimal limit = money(revenue(year).multiply(ratio1));
                BigDecimal prior = priorCarry(year, code);
                BigDecimal available = base.add(prior);
                BigDecimal deductible = min(available, limit);
                BigDecimal currentDeductible = min(base, deductible);
                BigDecimal addAmount = base.subtract(currentDeductible);
                BigDecimal priorUsed = deductible.subtract(currentDeductible);
                BigDecimal carryOut = available.subtract(deductible);
                if (addAmount.signum() > 0) {
                    rows.add(new ResolvedAdjustment("ADD", "广告费和业务宣传费超限调增",
                            money(addAmount), remark, code, money(base), money(limit),
                            money(carryOut)));
                }
                if (priorUsed.signum() > 0) {
                    rows.add(new ResolvedAdjustment("DEDUCT", "以前年度广告费结转扣除",
                            money(priorUsed), remark, code, money(prior), money(limit),
                            money(carryOut)));
                }
            }
            case "FULL" -> {
                BigDecimal base = roleAmount(String.valueOf(rule.get("expenseRole")), year);
                rows.add(new ResolvedAdjustment(type, name, base, remark, code,
                        base, BigDecimal.ZERO, BigDecimal.ZERO));
            }
            case "RD" -> {
                BigDecimal base = rdAmount(year);
                BigDecimal amount = money(base.multiply(ratio1 == null ? BigDecimal.ONE : ratio1));
                rows.add(new ResolvedAdjustment(type, name, amount, remark, code,
                        base, BigDecimal.ZERO, BigDecimal.ZERO));
            }
            default -> {
                return rows;
            }
        }
        return rows;
    }

    private BigDecimal roleAmount(String role, int year) {
        if (role == null || role.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = jdbcTemplate.queryForObject("""
                select coalesce(sum(l.debit - l.credit), 0)
                from fin_voucher_line l
                join fin_voucher v on v.id = l.voucher_id
                join fin_account a on a.code = l.account_code
                where v.status = 'POSTED' and a.role = ?
                  and v.voucher_date between ? and ?
                """, BigDecimal.class, role, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        return money(amount);
    }

    private BigDecimal revenue(int year) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                select coalesce(sum(l.credit - l.debit), 0)
                from fin_voucher_line l
                join fin_voucher v on v.id = l.voucher_id
                join fin_account a on a.code = l.account_code
                where v.status = 'POSTED' and a.type = 'REVENUE'
                  and v.voucher_date between ? and ?
                """, BigDecimal.class, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        return money(value);
    }

    private BigDecimal rdAmount(int year) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                select coalesce(sum(e.amount), 0)
                from rd_expense e join rd_project p on p.id = e.project_id
                where p.tax_year = ?
                """, BigDecimal.class, year);
        return money(value);
    }

    private BigDecimal min(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
