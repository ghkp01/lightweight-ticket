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
 * 企业所得税季度预缴。
 *
 * <p>按累计会计利润计算：本年累计应纳税额 = max(年初至本季末利润总额, 0) × 税率，
 * 本期应预缴 = 累计应纳税额 − 本年以前季度已申报预缴。季度按会计利润预缴，
 * 纳税调整与以前年度亏损弥补统一放到年度汇算清缴处理。</p>
 */
@Service
public class TaxPrepaymentService {

    private final JdbcTemplate jdbcTemplate;
    private final PostingService postingService;
    private final IncomeTaxService incomeTaxService;

    public TaxPrepaymentService(JdbcTemplate jdbcTemplate, PostingService postingService,
                                IncomeTaxService incomeTaxService) {
        this.jdbcTemplate = jdbcTemplate;
        this.postingService = postingService;
        this.incomeTaxService = incomeTaxService;
    }

    public record PrepayRequest(Integer year, Integer quarter, BigDecimal taxRate, String remark) {
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
                "select count(*) from tax_prepayment" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, tax_year as "taxYear", quarter, period_start as "periodStart",
                       period_end as "periodEnd", tax_rate as "taxRate",
                       ytd_profit as "ytdProfit", ytd_tax as "ytdTax",
                       prepaid_before as "prepaidBefore", current_prepaid as "currentPrepaid",
                       status, remark,
                       to_char(filed_at, 'YYYY-MM-DD HH24:MI') as "filedAt",
                       to_char(paid_at, 'YYYY-MM-DD HH24:MI') as "paidAt",
                       created_by as "createdBy"
                from tax_prepayment
                """ + where + " order by tax_year desc, quarter desc limit ? offset ?",
                pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, tax_year as "taxYear", quarter, period_start as "periodStart",
                       period_end as "periodEnd", tax_rate as "taxRate",
                       ytd_profit as "ytdProfit", ytd_tax as "ytdTax",
                       prepaid_before as "prepaidBefore", current_prepaid as "currentPrepaid",
                       status, remark,
                       to_char(filed_at, 'YYYY-MM-DD HH24:MI') as "filedAt",
                       to_char(paid_at, 'YYYY-MM-DD HH24:MI') as "paidAt",
                       created_by as "createdBy",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from tax_prepayment where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "预缴记录不存在");
        }
        Map<String, Object> prepayment = new LinkedHashMap<>(rows.get(0));
        prepayment.put("vouchers", jdbcTemplate.queryForList("""
                select voucher_no as "voucherNo", voucher_date as "voucherDate",
                       biz_type as "bizType", summary
                from fin_voucher
                where biz_id = ? and biz_type in ('CIT_PREPAY', 'CIT_PREPAY_PAYMENT')
                order by id
                """, id));
        return prepayment;
    }

    @Transactional
    public Map<String, Object> generate(PrepayRequest request) {
        if (request == null || request.year() == null || request.quarter() == null) {
            throw new BizException(1002, "请选择预缴年度和季度");
        }
        int year = request.year();
        int quarter = request.quarter();
        if (year < 2000 || year > 2100) {
            throw new BizException(1002, "预缴年度不合法");
        }
        if (quarter < 1 || quarter > 4) {
            throw new BizException(1002, "季度只能是 1~4");
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select id, status from tax_prepayment where tax_year = ? and quarter = ?",
                year, quarter);
        if (!existing.isEmpty() && !"DRAFT".equals(existing.get(0).get("status"))) {
            throw new BizException(1002, "该季度已经申报，不能重新生成");
        }
        BigDecimal rate = request.taxRate() == null ? new BigDecimal("0.2500") : request.taxRate();
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(1002, "所得税税率必须在 0~1 之间");
        }
        LocalDate periodStart = LocalDate.of(year, 1, 1);
        LocalDate periodEnd = quarterEnd(year, quarter);
        BigDecimal ytdProfit = incomeTaxService.pretaxProfit(periodStart, periodEnd);
        BigDecimal ytdTax = ytdProfit.signum() > 0
                ? money(ytdProfit.multiply(rate)) : BigDecimal.ZERO;
        BigDecimal before = jdbcTemplate.queryForObject("""
                select coalesce(sum(current_prepaid), 0) from tax_prepayment
                where tax_year = ? and quarter < ? and status in ('FILED', 'PAID')
                """, BigDecimal.class, year, quarter);
        BigDecimal prepaidBefore = money(before);
        BigDecimal current = ytdTax.subtract(prepaidBefore);
        if (current.signum() < 0) {
            current = BigDecimal.ZERO;
        }
        current = money(current);

        Long id;
        if (existing.isEmpty()) {
            id = jdbcTemplate.queryForObject("""
                    insert into tax_prepayment (tax_year, quarter, period_start, period_end,
                                                tax_rate, ytd_profit, ytd_tax,
                                                prepaid_before, current_prepaid, status,
                                                remark, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                    returning id
                    """, Long.class, year, quarter, periodStart, periodEnd, rate, ytdProfit, ytdTax,
                    prepaidBefore, current, blankToNull(request.remark()), CurrentUser.name());
        } else {
            id = ((Number) existing.get(0).get("id")).longValue();
            jdbcTemplate.update("""
                    update tax_prepayment
                    set period_start = ?, period_end = ?, tax_rate = ?, ytd_profit = ?,
                        ytd_tax = ?, prepaid_before = ?, current_prepaid = ?, remark = ?
                    where id = ?
                    """, periodStart, periodEnd, rate, ytdProfit, ytdTax, prepaidBefore, current,
                    blankToNull(request.remark()), id);
        }
        return detail(id);
    }

    @Transactional
    public Map<String, Object> file(long id) {
        Map<String, Object> prepayment = lock(id);
        if (!"DRAFT".equals(prepayment.get("status"))) {
            throw new BizException(1002, "只有草稿状态的预缴表可以申报");
        }
        BigDecimal current = (BigDecimal) prepayment.get("currentPrepaid");
        LocalDate periodEnd = (LocalDate) prepayment.get("periodEnd");
        if (current.signum() > 0) {
            postingService.post(periodEnd, "CIT_PREPAY", id,
                    String.valueOf(prepayment.get("taxYear")) + "-Q" + prepayment.get("quarter"),
                    "CNY", BigDecimal.ONE, "季度预缴企业所得税 " + prepayment.get("taxYear")
                            + " Q" + prepayment.get("quarter"),
                    List.of(new PostingService.PostingLine("INCOME_TAX_EXPENSE", "所得税费用",
                                    current, BigDecimal.ZERO),
                            new PostingService.PostingLine("INCOME_TAX_PAYABLE", "应交所得税",
                                    BigDecimal.ZERO, current)));
        }
        jdbcTemplate.update("update tax_prepayment set status = 'FILED', filed_at = now() where id = ?", id);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> pay(long id, String remark) {
        Map<String, Object> prepayment = lock(id);
        if (!"FILED".equals(prepayment.get("status"))) {
            throw new BizException(1002, "只有已申报状态可以登记缴款");
        }
        BigDecimal current = (BigDecimal) prepayment.get("currentPrepaid");
        if (current.signum() > 0) {
            postingService.post(LocalDate.now(), "CIT_PREPAY_PAYMENT", id,
                    String.valueOf(prepayment.get("taxYear")) + "-Q" + prepayment.get("quarter"),
                    "CNY", BigDecimal.ONE, "缴纳季度预缴所得税 " + prepayment.get("taxYear")
                            + " Q" + prepayment.get("quarter"),
                    List.of(new PostingService.PostingLine("INCOME_TAX_PAYABLE", "缴纳所得税",
                                    current, BigDecimal.ZERO),
                            new PostingService.PostingLine("BANK", "缴纳所得税",
                                    BigDecimal.ZERO, current)));
        }
        jdbcTemplate.update("""
                update tax_prepayment set status = 'PAID', paid_at = now(),
                       remark = coalesce(?, remark)
                where id = ?
                """, blankToNull(remark), id);
        return detail(id);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> lock(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, tax_year as "taxYear", quarter, period_end as "periodEnd",
                       current_prepaid as "currentPrepaid", status
                from tax_prepayment where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "预缴记录不存在");
        }
        Map<String, Object> row = rows.get(0);
        Object end = row.remove("periodEnd");
        if (end instanceof java.sql.Date sqlDate) {
            row.put("periodEnd", sqlDate.toLocalDate());
        } else if (end instanceof LocalDate localDate) {
            row.put("periodEnd", localDate);
        } else {
            row.put("periodEnd", LocalDate.parse(String.valueOf(end)));
        }
        return row;
    }

    private LocalDate quarterEnd(int year, int quarter) {
        int month = quarter * 3;
        LocalDate first = LocalDate.of(year, month, 1);
        return first.withDayOfMonth(first.lengthOfMonth());
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
