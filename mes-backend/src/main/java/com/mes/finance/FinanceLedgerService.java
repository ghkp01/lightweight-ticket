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
 * 总账查询与期间管理：科目、凭证、试算平衡、汇率、手工凭证。
 *
 * <p>凭证只能由 {@link PostingService} 写入，这里只负责查询与期间关账。</p>
 */
@Service
public class FinanceLedgerService {

    private final JdbcTemplate jdbcTemplate;
    private final PostingService postingService;

    public FinanceLedgerService(JdbcTemplate jdbcTemplate, PostingService postingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.postingService = postingService;
    }

    public List<Map<String, Object>> accounts() {
        return jdbcTemplate.queryForList("""
                select code, name, type, role, enabled
                from fin_account order by code
                """);
    }

    public List<Map<String, Object>> periods() {
        return jdbcTemplate.queryForList("""
                select x.period,
                       coalesce(p.status, 'OPEN') as "status",
                       p.closed_by as "closedBy",
                       to_char(p.closed_at, 'YYYY-MM-DD HH24:MI') as "closedAt",
                       (select count(*) from fin_voucher v where v.period = x.period) as "voucherCount"
                from (select distinct period from fin_voucher
                      union select period from fin_period) x
                left join fin_period p on p.period = x.period
                order by x.period desc
                """);
    }

    /** 关账：校验该期间借贷平衡后置为 CLOSED，之后不能再记账 */
    @Transactional
    public Map<String, Object> closePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new BizException(1002, "期间格式必须是 YYYY-MM");
        }
        List<String> status = jdbcTemplate.queryForList(
                "select status from fin_period where period = ?", String.class, period);
        if (!status.isEmpty() && "CLOSED".equals(status.get(0))) {
            throw new BizException(1002, "期间 " + period + " 已经关账");
        }
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                select count(*) as "voucherCount",
                       coalesce(sum(l.debit), 0) as "debit",
                       coalesce(sum(l.credit), 0) as "credit"
                from fin_voucher v
                join fin_voucher_line l on l.voucher_id = v.id
                where v.period = ? and v.status = 'POSTED'
                """, period);
        BigDecimal debit = new BigDecimal(String.valueOf(totals.get("debit")));
        BigDecimal credit = new BigDecimal(String.valueOf(totals.get("credit")));
        if (debit.compareTo(credit) != 0) {
            throw new BizException(1002, "期间 " + period + " 借贷不平：借方 " + debit
                    + "，贷方 " + credit + "，请先排查凭证");
        }
        jdbcTemplate.update("""
                insert into fin_period (period, status, closed_by, closed_at)
                values (?, 'CLOSED', ?, now())
                on conflict (period) do update
                set status = 'CLOSED', closed_by = excluded.closed_by, closed_at = now()
                """, period, CurrentUser.name());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("voucherCount", totals.get("voucherCount"));
        result.put("debit", debit);
        result.put("credit", credit);
        result.put("status", "CLOSED");
        return result;
    }

    public PageResult vouchers(String period, String bizType, String keyword,
                               int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (period != null && !period.isBlank()) {
            where.append(" and v.period = ? ");
            args.add(period.trim());
        }
        if (bizType != null && !bizType.isBlank()) {
            where.append(" and v.biz_type = ? ");
            args.add(bizType.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (v.voucher_no ilike ? or v.biz_no ilike ? or v.summary ilike ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from fin_voucher v" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select v.id, v.voucher_no as "voucherNo", v.voucher_date as "voucherDate",
                       v.period, v.biz_type as "bizType", v.biz_id as "bizId", v.biz_no as "bizNo",
                       v.currency, v.exchange_rate as "exchangeRate", v.summary, v.status,
                       v.reversal_of as "reversalOf", v.created_by as "createdBy",
                       to_char(v.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       (select coalesce(sum(l.debit), 0) from fin_voucher_line l
                         where l.voucher_id = v.id) as "amount"
                from fin_voucher v
                """ + where + " order by v.voucher_date desc, v.id desc limit ? offset ?",
                pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> voucherDetail(long id) {
        List<Map<String, Object>> vouchers = jdbcTemplate.queryForList("""
                select v.id, v.voucher_no as "voucherNo", v.voucher_date as "voucherDate",
                       v.period, v.biz_type as "bizType", v.biz_id as "bizId", v.biz_no as "bizNo",
                       v.currency, v.exchange_rate as "exchangeRate", v.summary, v.status,
                       v.reversal_of as "reversalOf", v.created_by as "createdBy",
                       to_char(v.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from fin_voucher v where v.id = ?
                """, id);
        if (vouchers.isEmpty()) {
            throw new BizException(1002, "凭证不存在");
        }
        Map<String, Object> voucher = new LinkedHashMap<>(vouchers.get(0));
        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                select l.line_no as "lineNo", l.account_code as "accountCode",
                       a.name as "accountName", l.summary, l.debit, l.credit
                from fin_voucher_line l
                left join fin_account a on a.code = l.account_code
                where l.voucher_id = ? order by l.line_no
                """, id);
        voucher.put("lines", lines);
        return voucher;
    }

    /** 试算平衡：期间发生额 + 余额（借方为正、贷方为负） */
    public Map<String, Object> trialBalance(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BizException(1002, "请选择试算平衡的起止日期");
        }
        if (from.isAfter(to)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select a.code, a.name, a.type, a.role,
                       coalesce(l.debit, 0) as "debit",
                       coalesce(l.credit, 0) as "credit",
                       coalesce(l.debit, 0) - coalesce(l.credit, 0) as "balance"
                from fin_account a
                left join (
                    select l.account_code, sum(l.debit) as debit, sum(l.credit) as credit
                    from fin_voucher_line l
                    join fin_voucher v on v.id = l.voucher_id
                    where v.status = 'POSTED' and v.voucher_date between ? and ?
                    group by l.account_code
                ) l on l.account_code = a.code
                order by a.code
                """, from, to);
        BigDecimal debit = rows.stream()
                .map(row -> new BigDecimal(String.valueOf(row.get("debit"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream()
                .map(row -> new BigDecimal(String.valueOf(row.get("credit"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("rows", rows);
        result.put("debit", debit.setScale(2, RoundingMode.HALF_UP));
        result.put("credit", credit.setScale(2, RoundingMode.HALF_UP));
        result.put("balanced", debit.compareTo(credit) == 0);
        return result;
    }

    public List<Map<String, Object>> exchangeRates(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase();
        if (code.isBlank()) {
            return jdbcTemplate.queryForList("""
                    select id, currency, rate_date as "rateDate", rate, created_by as "createdBy",
                           to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                    from fin_exchange_rate order by rate_date desc, currency limit 200
                    """);
        }
        return jdbcTemplate.queryForList("""
                select id, currency, rate_date as "rateDate", rate, created_by as "createdBy",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from fin_exchange_rate where currency = ? order by rate_date desc limit 200
                """, code);
    }

    @Transactional
    public void saveRate(String currency, LocalDate rateDate, BigDecimal rate) {
        String code = currency == null ? "" : currency.trim().toUpperCase();
        if (!List.of("USD", "EUR").contains(code)) {
            throw new BizException(1002, "汇率币种只支持 USD / EUR");
        }
        if (rateDate == null) {
            throw new BizException(1002, "请填写汇率生效日期");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new BizException(1002, "汇率必须大于 0");
        }
        jdbcTemplate.update("""
                insert into fin_exchange_rate (currency, rate_date, rate, created_by)
                values (?, ?, ?, ?)
                on conflict (currency, rate_date) do update
                set rate = excluded.rate, created_by = excluded.created_by, created_at = now()
                """, code, rateDate, rate, CurrentUser.name());
    }

    public long createManualVoucher(LocalDate voucherDate, String summary, String currency,
                                    BigDecimal exchangeRate,
                                    List<PostingService.ManualLine> lines) {
        if (voucherDate == null) {
            throw new BizException(1002, "请填写凭证日期");
        }
        return postingService.postManual(voucherDate, summary, currency, exchangeRate, lines);
    }
}
