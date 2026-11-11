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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 税务申报：按期间从总账销项/进项科目汇总增值税，计算附加税，生成申报表并结转、缴款。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>销项税额 = 期间 POSTED 凭证中「销项税额」科目贷方净额；进项税额同理取借方净额；</li>
 *   <li>应交增值税 = max(销项 - 进项, 0)，进项大于销项时形成留抵，不结转；</li>
 *   <li>附加税默认城建 7%、教育 3%、地方教育 2%，按应交增值税计征；</li>
 *   <li>申报结转凭证记在税款所属期间最后一天，因此必须在关账前完成申报。</li>
 * </ul>
 */
@Service
public class TaxFilingService {

    private final JdbcTemplate jdbcTemplate;
    private final PostingService postingService;

    public TaxFilingService(JdbcTemplate jdbcTemplate, PostingService postingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.postingService = postingService;
    }

    public record GenerateRequest(String period, BigDecimal cityRate,
                                  BigDecimal eduRate, BigDecimal localEduRate) {
    }

    public PageResult list(String period, String status, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (period != null && !period.isBlank()) {
            where.append(" and period = ? ");
            args.add(period.trim());
        }
        if (status != null && !status.isBlank()) {
            where.append(" and status = ? ");
            args.add(status.trim());
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from tax_filing" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, period, tax_type as "taxType", output_tax as "outputTax",
                       input_tax as "inputTax", vat_payable as "vatPayable",
                       city_tax as "cityTax", edu_tax as "eduTax",
                       local_edu_tax as "localEduTax", total_surtax as "totalSurtax",
                       total_payable as "totalPayable", status, remark,
                       to_char(filed_at, 'YYYY-MM-DD HH24:MI') as "filedAt",
                       to_char(paid_at, 'YYYY-MM-DD HH24:MI') as "paidAt",
                       created_by as "createdBy"
                from tax_filing
                """ + where + " order by period desc, id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, period, tax_type as "taxType", output_tax as "outputTax",
                       input_tax as "inputTax", vat_payable as "vatPayable",
                       city_rate as "cityRate", edu_rate as "eduRate",
                       local_edu_rate as "localEduRate",
                       city_tax as "cityTax", edu_tax as "eduTax",
                       local_edu_tax as "localEduTax", total_surtax as "totalSurtax",
                       total_payable as "totalPayable", status, remark,
                       to_char(filed_at, 'YYYY-MM-DD HH24:MI') as "filedAt",
                       to_char(paid_at, 'YYYY-MM-DD HH24:MI') as "paidAt",
                       created_by as "createdBy",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from tax_filing where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "申报表不存在");
        }
        Map<String, Object> filing = new LinkedHashMap<>(rows.get(0));
        filing.put("vouchers", jdbcTemplate.queryForList("""
                select voucher_no as "voucherNo", voucher_date as "voucherDate",
                       biz_type as "bizType", summary
                from fin_voucher
                where biz_id = ? and biz_type in ('TAX_VAT_TRANSFER', 'TAX_SURTAX_TRANSFER', 'TAX_PAYMENT')
                order by id
                """, id));
        return filing;
    }

    @Transactional
    public Map<String, Object> generate(GenerateRequest request) {
        String period = request == null ? null : request.period();
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new BizException(1002, "期间格式必须是 YYYY-MM");
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select id, status from tax_filing where period = ? and tax_type = 'VAT'", period);
        if (!existing.isEmpty() && !"DRAFT".equals(existing.get(0).get("status"))) {
            throw new BizException(1002, "该期间已经申报，不能重新生成");
        }
        LocalDate start = YearMonth.parse(period).atDay(1);
        LocalDate end = YearMonth.parse(period).atEndOfMonth();
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                select coalesce(sum(case when a.role = 'OUTPUT_TAX' then l.credit - l.debit else 0 end), 0)
                           as "outputTax",
                       coalesce(sum(case when a.role = 'INPUT_TAX' then l.debit - l.credit else 0 end), 0)
                           as "inputTax"
                from fin_voucher_line l
                join fin_voucher v on v.id = l.voucher_id
                join fin_account a on a.code = l.account_code
                where v.status = 'POSTED' and v.voucher_date between ? and ?
                  and a.role in ('OUTPUT_TAX', 'INPUT_TAX')
                """, start, end);
        BigDecimal output = money((BigDecimal) totals.get("outputTax"));
        BigDecimal input = money((BigDecimal) totals.get("inputTax"));
        BigDecimal vat = output.subtract(input);
        if (vat.signum() < 0) {
            vat = BigDecimal.ZERO;
        }
        BigDecimal cityRate = rate(request.cityRate(), "0.0700");
        BigDecimal eduRate = rate(request.eduRate(), "0.0300");
        BigDecimal localRate = rate(request.localEduRate(), "0.0200");
        BigDecimal cityTax = money(vat.multiply(cityRate));
        BigDecimal eduTax = money(vat.multiply(eduRate));
        BigDecimal localTax = money(vat.multiply(localRate));
        BigDecimal surtax = cityTax.add(eduTax).add(localTax);
        BigDecimal total = vat.add(surtax);

        Long id;
        if (existing.isEmpty()) {
            id = jdbcTemplate.queryForObject("""
                    insert into tax_filing (period, tax_type, output_tax, input_tax, vat_payable,
                                            city_rate, edu_rate, local_edu_rate,
                                            city_tax, edu_tax, local_edu_tax,
                                            total_surtax, total_payable, status, created_by)
                    values (?, 'VAT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                    returning id
                    """, Long.class, period, output, input, vat, cityRate, eduRate, localRate,
                    cityTax, eduTax, localTax, surtax, total, CurrentUser.name());
        } else {
            id = ((Number) existing.get(0).get("id")).longValue();
            jdbcTemplate.update("""
                    update tax_filing set output_tax = ?, input_tax = ?, vat_payable = ?,
                           city_rate = ?, edu_rate = ?, local_edu_rate = ?,
                           city_tax = ?, edu_tax = ?, local_edu_tax = ?,
                           total_surtax = ?, total_payable = ?
                    where id = ?
                    """, output, input, vat, cityRate, eduRate, localRate,
                    cityTax, eduTax, localTax, surtax, total, id);
        }
        return detail(id);
    }

    @Transactional
    public Map<String, Object> file(long id) {
        Map<String, Object> filing = lock(id);
        if (!"DRAFT".equals(filing.get("status"))) {
            throw new BizException(1002, "只有草稿状态的申报表可以申报");
        }
        String period = String.valueOf(filing.get("period"));
        LocalDate periodEnd = YearMonth.parse(period).atEndOfMonth();
        postingService.assertOpen(period);
        BigDecimal output = (BigDecimal) filing.get("outputTax");
        BigDecimal input = (BigDecimal) filing.get("inputTax");
        BigDecimal vat = (BigDecimal) filing.get("vatPayable");
        BigDecimal surtax = (BigDecimal) filing.get("totalSurtax");
        if (vat.signum() > 0) {
            postingService.post(periodEnd, "TAX_VAT_TRANSFER", id, period, "CNY", BigDecimal.ONE,
                    "结转增值税 " + period,
                    List.of(new PostingService.PostingLine("OUTPUT_TAX", "结转销项税额", output, BigDecimal.ZERO),
                            new PostingService.PostingLine("INPUT_TAX", "结转进项税额", BigDecimal.ZERO, input),
                            new PostingService.PostingLine("VAT_PAYABLE", "转出未交增值税",
                                    BigDecimal.ZERO, vat)));
        }
        if (surtax.signum() > 0) {
            postingService.post(periodEnd, "TAX_SURTAX_TRANSFER", id, period, "CNY", BigDecimal.ONE,
                    "计提附加税 " + period,
                    List.of(new PostingService.PostingLine("SURTAX_EXPENSE", "税金及附加",
                                    surtax, BigDecimal.ZERO),
                            new PostingService.PostingLine("SURTAX_PAYABLE", "应交附加税",
                                    BigDecimal.ZERO, surtax)));
        }
        jdbcTemplate.update("""
                update tax_filing set status = 'FILED', filed_at = now() where id = ?
                """, id);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> pay(long id, String remark) {
        Map<String, Object> filing = lock(id);
        if (!"FILED".equals(filing.get("status"))) {
            throw new BizException(1002, "只有已申报状态可以登记缴款");
        }
        BigDecimal vat = (BigDecimal) filing.get("vatPayable");
        BigDecimal surtax = (BigDecimal) filing.get("totalSurtax");
        BigDecimal total = vat.add(surtax);
        if (total.signum() > 0) {
            List<PostingService.PostingLine> lines = new ArrayList<>();
            if (vat.signum() > 0) {
                lines.add(new PostingService.PostingLine("VAT_PAYABLE", "缴纳增值税", vat, BigDecimal.ZERO));
            }
            if (surtax.signum() > 0) {
                lines.add(new PostingService.PostingLine("SURTAX_PAYABLE", "缴纳附加税",
                        surtax, BigDecimal.ZERO));
            }
            lines.add(new PostingService.PostingLine("BANK", "缴纳税款", BigDecimal.ZERO, total));
            postingService.post(LocalDate.now(), "TAX_PAYMENT", id,
                    String.valueOf(filing.get("period")), "CNY", BigDecimal.ONE,
                    "缴纳税款 " + filing.get("period"), lines);
        }
        jdbcTemplate.update("""
                update tax_filing set status = 'PAID', paid_at = now(),
                       remark = coalesce(?, remark)
                where id = ?
                """, remark == null || remark.isBlank() ? null : remark.trim(), id);
        return detail(id);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> lock(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, period, output_tax as "outputTax", input_tax as "inputTax",
                       vat_payable as "vatPayable", total_surtax as "totalSurtax",
                       total_payable as "totalPayable", status
                from tax_filing where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "申报表不存在");
        }
        return rows.get(0);
    }

    private BigDecimal rate(BigDecimal value, String defaultValue) {
        BigDecimal rate = value == null ? new BigDecimal(defaultValue) : value;
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(1002, "附加税税率必须在 0~1 之间");
        }
        return rate;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
