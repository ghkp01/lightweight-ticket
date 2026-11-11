package com.mes.finance;

import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业所得税年度汇算清缴。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>会计利润取总账收入类与费用类科目全年发生额（收入 − 费用）；</li>
 *   <li>应纳税所得额 = 会计利润 + 纳税调增 − 纳税调减 − 弥补以前年度亏损；</li>
 *   <li>亏损弥补取近 5 个年度已申报的负应纳税所得额，扣除之后年度已使用的弥补额；</li>
 *   <li>应补（退）税额 = 应纳税额 − 减免税额 − 已预缴税额，负数表示应退；</li>
 *   <li>计提凭证记在税款所属年度 12-31，因此要先汇算再关账。</li>
 * </ul>
 */
@Service
public class IncomeTaxService {

    private final JdbcTemplate jdbcTemplate;
    private final PostingService postingService;
    private final TaxAdjustmentService adjustmentService;
    private final TaxBranchService branchService;

    public IncomeTaxService(JdbcTemplate jdbcTemplate, PostingService postingService,
                            TaxAdjustmentService adjustmentService, TaxBranchService branchService) {
        this.jdbcTemplate = jdbcTemplate;
        this.postingService = postingService;
        this.adjustmentService = adjustmentService;
        this.branchService = branchService;
    }

    public record Adjustment(String type, String item, BigDecimal amount, String remark, String ruleCode) {

        /** 兼容手工录入：不带规则编码 */
        public Adjustment(String type, String item, BigDecimal amount, String remark) {
            this(type, item, amount, remark, null);
        }
    }

    public record SettleRequest(Integer year, BigDecimal taxRate, BigDecimal taxRelief,
                                BigDecimal prepaidTax, BigDecimal lossCarryforward,
                                List<Adjustment> adjustments, String remark,
                                String enterpriseType, Integer employeeCount,
                                BigDecimal totalAssets, Boolean smallProfit) {

        /** 兼容原调用：不传企业类型与小型微利参数 */
        public SettleRequest(Integer year, BigDecimal taxRate, BigDecimal taxRelief,
                             BigDecimal prepaidTax, BigDecimal lossCarryforward,
                             List<Adjustment> adjustments, String remark) {
            this(year, taxRate, taxRelief, prepaidTax, lossCarryforward, adjustments, remark,
                    null, null, null, null);
        }
    }

    public PageResult list(Integer year, String status, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (year != null) {
            where.append(" and tax_year = ? ");
            args.add(year);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and status = ? ");
            args.add(status.trim());
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from tax_income_settlement" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, tax_year as "taxYear", accounting_profit as "accountingProfit",
                       adjustment_add as "adjustmentAdd", adjustment_deduct as "adjustmentDeduct",
                       loss_carryforward as "lossCarryforward", taxable_income as "taxableIncome",
                       tax_rate as "taxRate", tax_payable as "taxPayable",
                       tax_relief as "taxRelief", prepaid_tax as "prepaidTax",
                       payable_tax as "payableTax", status, remark,
                       enterprise_type as "enterpriseType", small_profit as "smallProfit",
                       small_relief as "smallRelief", employee_count as "employeeCount",
                       total_assets as "totalAssets",
                       to_char(filed_at, 'YYYY-MM-DD HH24:MI') as "filedAt",
                       to_char(paid_at, 'YYYY-MM-DD HH24:MI') as "paidAt",
                       created_by as "createdBy"
                from tax_income_settlement
                """ + where + " order by tax_year desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, tax_year as "taxYear", accounting_profit as "accountingProfit",
                       adjustment_add as "adjustmentAdd", adjustment_deduct as "adjustmentDeduct",
                       loss_carryforward as "lossCarryforward", taxable_income as "taxableIncome",
                       tax_rate as "taxRate", tax_payable as "taxPayable",
                       tax_relief as "taxRelief", prepaid_tax as "prepaidTax",
                       payable_tax as "payableTax", status, remark,
                       enterprise_type as "enterpriseType", small_profit as "smallProfit",
                       small_relief as "smallRelief", employee_count as "employeeCount",
                       total_assets as "totalAssets",
                       to_char(filed_at, 'YYYY-MM-DD HH24:MI') as "filedAt",
                       to_char(paid_at, 'YYYY-MM-DD HH24:MI') as "paidAt",
                       created_by as "createdBy",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from tax_income_settlement where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "汇算清缴记录不存在");
        }
        Map<String, Object> settlement = new LinkedHashMap<>(rows.get(0));
        settlement.put("adjustments", jdbcTemplate.queryForList("""
                select id, adjust_type as "type", item, amount, remark,
                       rule_code as "ruleCode", base_amount as "baseAmount",
                       limit_amount as "limitAmount",
                       carryforward_balance as "carryforwardBalance"
                from tax_adjustment where settlement_id = ? order by id
                """, id));
        settlement.put("allocations", jdbcTemplate.queryForList("""
                select branch_name as "branchName", share_ratio as "shareRatio", amount
                from tax_branch_allocation where settlement_id = ? order by id
                """, id));
        settlement.put("vouchers", jdbcTemplate.queryForList("""
                select voucher_no as "voucherNo", voucher_date as "voucherDate",
                       biz_type as "bizType", summary
                from fin_voucher
                where biz_id = ? and biz_type in ('CIT_ACCRUAL', 'CIT_PAYMENT')
                order by id
                """, id));
        return settlement;
    }

    /** 可弥补的以前年度亏损：近 5 年负应纳税所得额，扣除已使用的弥补额 */
    public Map<String, Object> lossCarryforward(int year) {
        int from = year - 5;
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                select coalesce(sum(case when taxable_income < 0 then -taxable_income else 0 end), 0)
                           as "losses",
                       coalesce(sum(loss_carryforward), 0) as "used"
                from tax_income_settlement
                where tax_year between ? and ? and status in ('FILED', 'PAID')
                """, from, year - 1);
        BigDecimal losses = new BigDecimal(String.valueOf(totals.get("losses")));
        BigDecimal used = new BigDecimal(String.valueOf(totals.get("used")));
        BigDecimal available = losses.subtract(used);
        if (available.signum() < 0) {
            available = BigDecimal.ZERO;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("fromYear", from);
        result.put("losses", losses);
        result.put("used", used);
        result.put("available", available);
        return result;
    }

    @Transactional
    public Map<String, Object> generate(SettleRequest request) {
        if (request == null || request.year() == null) {
            throw new BizException(1002, "请填写汇算年度");
        }
        int year = request.year();
        if (year < 2000 || year > 2100) {
            throw new BizException(1002, "汇算年度不合法");
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select id, status from tax_income_settlement where tax_year = ?", year);
        if (!existing.isEmpty() && !"DRAFT".equals(existing.get(0).get("status"))) {
            throw new BizException(1002, "该年度已经申报，不能重新生成");
        }
        BigDecimal profit = accountingProfit(year);

        List<TaxAdjustmentService.Adjustment> rawAdjustments = request.adjustments() == null ? List.of()
                : request.adjustments().stream()
                .filter(line -> line != null)
                .map(line -> new TaxAdjustmentService.Adjustment(
                        line.type(), line.item(), line.amount(), line.remark(), line.ruleCode()))
                .toList();
        List<TaxAdjustmentService.ResolvedAdjustment> resolved =
                adjustmentService.compute(rawAdjustments, year);
        BigDecimal add = BigDecimal.ZERO;
        BigDecimal deduct = BigDecimal.ZERO;
        for (TaxAdjustmentService.ResolvedAdjustment adjustment : resolved) {
            if ("ADD".equals(adjustment.type())) {
                add = add.add(adjustment.amount());
            } else {
                deduct = deduct.add(adjustment.amount());
            }
        }

        BigDecimal available = new BigDecimal(String.valueOf(lossCarryforward(year).get("available")));
        BigDecimal loss = money(request.lossCarryforward());
        if (loss.signum() < 0) {
            throw new BizException(1002, "弥补亏损金额不能为负");
        }
        if (loss.compareTo(available) > 0) {
            throw new BizException(1002, "弥补亏损超过可弥补余额 " + available);
        }
        BigDecimal taxable = money(profit.add(add).subtract(deduct).subtract(loss));

        String enterpriseType = request.enterpriseType() == null ? "GENERAL"
                : request.enterpriseType().trim().toUpperCase();
        if (!List.of("GENERAL", "SMALL").contains(enterpriseType)) {
            throw new BizException(1002, "企业类型只支持 GENERAL / SMALL");
        }
        boolean smallProfit = "SMALL".equals(enterpriseType) || Boolean.TRUE.equals(request.smallProfit());
        BigDecimal standardRate = new BigDecimal("0.2500");
        BigDecimal rate = request.taxRate() == null ? standardRate : request.taxRate();
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(1002, "所得税税率必须在 0~1 之间");
        }
        BigDecimal taxPayable;
        BigDecimal smallRelief = BigDecimal.ZERO;
        if (smallProfit) {
            // 小型微利：应纳税所得额不超过 300 万、从业人数不超过 300、资产总额不超过 5000 万，
            // 实际税负 5%（25% 名义税率下减免 20 个百分点）
            if (taxable.compareTo(new BigDecimal("3000000")) > 0) {
                throw new BizException(1002, "小型微利企业应纳税所得额不能超过 300 万元");
            }
            if (request.employeeCount() != null && request.employeeCount() > 300) {
                throw new BizException(1002, "小型微利企业从业人数不能超过 300 人");
            }
            if (request.totalAssets() != null
                    && request.totalAssets().compareTo(new BigDecimal("50000000")) > 0) {
                throw new BizException(1002, "小型微利企业资产总额不能超过 5000 万元");
            }
            taxPayable = taxable.signum() > 0 ? money(taxable.multiply(standardRate)) : BigDecimal.ZERO;
            BigDecimal effective = taxable.signum() > 0
                    ? money(taxable.multiply(new BigDecimal("0.0500"))) : BigDecimal.ZERO;
            smallRelief = taxPayable.subtract(effective);
            rate = standardRate;
            enterpriseType = "SMALL";
        } else {
            smallRelief = BigDecimal.ZERO;
            taxPayable = taxable.signum() > 0 ? money(taxable.multiply(rate)) : BigDecimal.ZERO;
        }
        BigDecimal userRelief = money(request.taxRelief());
        if (userRelief.signum() < 0 || userRelief.add(smallRelief).compareTo(taxPayable) > 0) {
            throw new BizException(1002, "减免税额必须在 0 与应纳税额之间");
        }
        BigDecimal relief = money(userRelief.add(smallRelief));
        // 不传预缴税额时按季度预缴模块自动汇总，传了以手工为准
        BigDecimal prepaid = request.prepaidTax() == null
                ? autoPrepaid(year) : money(request.prepaidTax());
        if (prepaid.signum() < 0) {
            throw new BizException(1002, "已预缴税额不能为负");
        }
        BigDecimal payable = money(taxPayable.subtract(relief).subtract(prepaid));

        Long id;
        if (existing.isEmpty()) {
            id = jdbcTemplate.queryForObject("""
                    insert into tax_income_settlement (tax_year, accounting_profit, adjustment_add,
                                                       adjustment_deduct, loss_carryforward,
                                                       taxable_income, tax_rate, tax_payable,
                                                       tax_relief, prepaid_tax, payable_tax,
                                                       enterprise_type, employee_count, total_assets,
                                                       small_profit, small_relief,
                                                       status, remark, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                    returning id
                    """, Long.class, year, profit, add, deduct, loss, taxable, rate, taxPayable,
                    relief, prepaid, payable, enterpriseType, request.employeeCount(),
                    request.totalAssets(), smallProfit, smallRelief,
                    blankToNull(request.remark()), CurrentUser.name());
        } else {
            id = ((Number) existing.get(0).get("id")).longValue();
            jdbcTemplate.update("""
                    update tax_income_settlement
                    set accounting_profit = ?, adjustment_add = ?, adjustment_deduct = ?,
                        loss_carryforward = ?, taxable_income = ?, tax_rate = ?, tax_payable = ?,
                        tax_relief = ?, prepaid_tax = ?, payable_tax = ?, enterprise_type = ?,
                        employee_count = ?, total_assets = ?, small_profit = ?, small_relief = ?,
                        remark = ?
                    where id = ?
                    """, profit, add, deduct, loss, taxable, rate, taxPayable,
                    relief, prepaid, payable, enterpriseType, request.employeeCount(),
                    request.totalAssets(), smallProfit, smallRelief,
                    blankToNull(request.remark()), id);
            jdbcTemplate.update("delete from tax_adjustment where settlement_id = ?", id);
        }
        for (TaxAdjustmentService.ResolvedAdjustment adjustment : resolved) {
            jdbcTemplate.update("""
                    insert into tax_adjustment (settlement_id, adjust_type, item, amount, remark,
                                                rule_code, base_amount, limit_amount,
                                                carryforward_balance)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, adjustment.type(), adjustment.item(), adjustment.amount(),
                    blankToNull(adjustment.remark()), adjustment.ruleCode(),
                    adjustment.baseAmount(), adjustment.limitAmount(),
                    adjustment.carryforwardOut());
        }
        return detail(id);
    }

    @Transactional
    public Map<String, Object> file(long id) {
        Map<String, Object> settlement = lock(id);
        if (!"DRAFT".equals(settlement.get("status"))) {
            throw new BizException(1002, "只有草稿状态的汇算表可以申报");
        }
        int year = ((Number) settlement.get("taxYear")).intValue();
        BigDecimal taxPayable = (BigDecimal) settlement.get("taxPayable");
        BigDecimal relief = (BigDecimal) settlement.get("taxRelief");
        BigDecimal accrual = money(taxPayable.subtract(relief));
        if (accrual.signum() > 0) {
            LocalDate yearEnd = LocalDate.of(year, 12, 31);
            postingService.post(yearEnd, "CIT_ACCRUAL", id, String.valueOf(year), "CNY",
                    BigDecimal.ONE, "计提企业所得税 " + year,
                    List.of(new PostingService.PostingLine("INCOME_TAX_EXPENSE", "所得税费用",
                                    accrual, BigDecimal.ZERO),
                            new PostingService.PostingLine("INCOME_TAX_PAYABLE", "应交所得税",
                                    BigDecimal.ZERO, accrual)));
        }
        jdbcTemplate.update("""
                update tax_income_settlement set status = 'FILED', filed_at = now() where id = ?
                """, id);
        // 申报时按分支机构分摊比例落分摊表，总机构为剩余比例（尾差归总机构）
        allocateBranches(id, (BigDecimal) settlement.get("payableTax"));
        // 申报时把调整明细落入跨年台账，广告费结转余额随年传递
        writeLedger(id, year);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> pay(long id, String remark) {
        Map<String, Object> settlement = lock(id);
        if (!"FILED".equals(settlement.get("status"))) {
            throw new BizException(1002, "只有已申报状态可以登记补税或退税");
        }
        BigDecimal payable = (BigDecimal) settlement.get("payableTax");
        if (payable.signum() > 0) {
            postingService.post(LocalDate.now(), "CIT_PAYMENT", id,
                    String.valueOf(settlement.get("taxYear")), "CNY", BigDecimal.ONE,
                    "缴纳企业所得税 " + settlement.get("taxYear"),
                    List.of(new PostingService.PostingLine("INCOME_TAX_PAYABLE", "缴纳所得税",
                                    payable, BigDecimal.ZERO),
                            new PostingService.PostingLine("BANK", "缴纳所得税",
                                    BigDecimal.ZERO, payable)));
        } else if (payable.signum() < 0) {
            BigDecimal refund = payable.negate();
            postingService.post(LocalDate.now(), "CIT_PAYMENT", id,
                    String.valueOf(settlement.get("taxYear")), "CNY", BigDecimal.ONE,
                    "企业所得税退税 " + settlement.get("taxYear"),
                    List.of(new PostingService.PostingLine("BANK", "收到所得税退税",
                                    refund, BigDecimal.ZERO),
                            new PostingService.PostingLine("INCOME_TAX_PAYABLE", "收到所得税退税",
                                    BigDecimal.ZERO, refund)));
        }
        jdbcTemplate.update("""
                update tax_income_settlement set status = 'PAID', paid_at = now(),
                       remark = coalesce(?, remark)
                where id = ?
                """, blankToNull(remark), id);
        return detail(id);
    }

    // ---------------------------------------------------------------- 内部

    /** 本年已预缴所得税：季度预缴已申报（含已缴款）的金额合计 */
    public BigDecimal autoPrepaid(int year) {
        BigDecimal total = jdbcTemplate.queryForObject("""
                select coalesce(sum(current_prepaid), 0) from tax_prepayment
                where tax_year = ? and status in ('FILED', 'PAID')
                """, BigDecimal.class, year);
        return money(total);
    }

    /** 利润总额（不含所得税费用本身），年度汇算与季度预缴共用 */
    public BigDecimal pretaxProfit(LocalDate start, LocalDate end) {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                select coalesce(sum(case when a.type = 'REVENUE' then l.credit - l.debit
                                         when a.type = 'EXPENSE' then l.credit - l.debit
                                         else 0 end), 0) as "profit"
                from fin_voucher_line l
                join fin_voucher v on v.id = l.voucher_id
                join fin_account a on a.code = l.account_code
                where v.status = 'POSTED' and v.voucher_date between ? and ?
                  and a.type in ('REVENUE', 'EXPENSE')
                  and (a.role is null or a.role <> 'INCOME_TAX_EXPENSE')
                """, start, end);
        return money((BigDecimal) totals.get("profit"));
    }

    private BigDecimal accountingProfit(int year) {
        return pretaxProfit(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /** 纳税调整跨年台账：按年度查看调整明细与结转余额 */
    public PageResult adjustmentLedger(String ruleCode, Integer yearFrom, Integer yearTo,
                                       int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (ruleCode != null && !ruleCode.isBlank()) {
            where.append(" and rule_code = ? ");
            args.add(ruleCode.trim());
        }
        if (yearFrom != null) {
            where.append(" and tax_year >= ? ");
            args.add(yearFrom);
        }
        if (yearTo != null) {
            where.append(" and tax_year <= ? ");
            args.add(yearTo);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from tax_adjustment_ledger" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, tax_year as "taxYear", settlement_id as "settlementId",
                       rule_code as "ruleCode", item, adjust_type as "type", amount,
                       base_amount as "baseAmount", limit_amount as "limitAmount",
                       carryforward_balance as "carryforwardBalance",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from tax_adjustment_ledger
                """ + where + " order by tax_year desc, id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    /** 分支机构分摊：比例之和小于 1 时，剩余归总机构；尾差也归总机构 */
    private void allocateBranches(long settlementId, BigDecimal payable) {
        jdbcTemplate.update("delete from tax_branch_allocation where settlement_id = ?", settlementId);
        List<Map<String, Object>> branches = branchService.enabledBranches();
        if (branches.isEmpty()) {
            return;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal ratioSum = BigDecimal.ZERO;
        for (Map<String, Object> branch : branches) {
            BigDecimal ratio = (BigDecimal) branch.get("shareRatio");
            BigDecimal amount = money(payable.multiply(ratio));
            jdbcTemplate.update("""
                    insert into tax_branch_allocation (settlement_id, branch_id, branch_name,
                                                       share_ratio, amount)
                    values (?, ?, ?, ?, ?)
                    """, settlementId, ((Number) branch.get("id")).longValue(),
                    String.valueOf(branch.get("name")), ratio, amount);
            allocated = allocated.add(amount);
            ratioSum = ratioSum.add(ratio);
        }
        BigDecimal headRatio = BigDecimal.ONE.subtract(ratioSum);
        BigDecimal headAmount = payable.subtract(allocated);
        jdbcTemplate.update("""
                insert into tax_branch_allocation (settlement_id, branch_id, branch_name,
                                                   share_ratio, amount)
                values (?, null, '总机构', ?, ?)
                """, settlementId, headRatio, money(headAmount));
    }

    /** 把某次申报的调整明细写入跨年台账 */
    private void writeLedger(long settlementId, int year) {
        jdbcTemplate.update("delete from tax_adjustment_ledger where settlement_id = ?", settlementId);
        jdbcTemplate.update("""
                insert into tax_adjustment_ledger (tax_year, settlement_id, rule_code, item,
                                                   adjust_type, amount, base_amount, limit_amount,
                                                   carryforward_balance)
                select ?, settlement_id, rule_code, item, adjust_type, amount, base_amount,
                       limit_amount, carryforward_balance
                from tax_adjustment where settlement_id = ?
                """, year, settlementId);
    }

    private Map<String, Object> lock(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, tax_year as "taxYear", tax_payable as "taxPayable",
                       tax_relief as "taxRelief", prepaid_tax as "prepaidTax",
                       payable_tax as "payableTax", status
                from tax_income_settlement where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "汇算清缴记录不存在");
        }
        return rows.get(0);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
