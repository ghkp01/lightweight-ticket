package com.mes.finance;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
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
 * 固定资产：卡片、直线法折旧、处置。
 *
 * <p>折旧规则：月折旧额 =（原值 ×（1-残值率））÷ 使用月数，最后一个月按剩余可折旧额补足；
 * 同一资产同一期间只能计提一次（唯一键兜底）。处置凭证一次结平原值、累计折旧、收款与处置损益。</p>
 */
@Service
public class FixedAssetService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final PostingService postingService;

    public FixedAssetService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                             PostingService postingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.postingService = postingService;
    }

    public record AssetRequest(String name, String category, String location, String department,
                               LocalDate acquisitionDate, BigDecimal originalValue,
                               BigDecimal residualRate, Integer usefulLifeMonths,
                               String remark, Boolean postVoucher) {
    }

    public record DisposeRequest(LocalDate disposalDate, BigDecimal amount, String remark) {
    }

    public record DepreciateRequest(String period) {
    }

    public Map<String, Object> summary() {
        return jdbcTemplate.queryForMap("""
                select count(*) filter (where status = 'IN_USE') as "inUse",
                       count(*) filter (where status = 'DISPOSED') as "disposed",
                       coalesce(sum(original_value) filter (where status = 'IN_USE'), 0) as "originalValue",
                       coalesce(sum(accumulated_depreciation) filter (where status = 'IN_USE'), 0) as "accumulated",
                       coalesce(sum(original_value - accumulated_depreciation)
                                filter (where status = 'IN_USE'), 0) as "netValue"
                from fa_asset
                """);
    }

    public PageResult list(String keyword, String status, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (status != null && !status.isBlank()) {
            where.append(" and a.status = ? ");
            args.add(status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append("""
                     and (a.asset_no ilike ? or a.name ilike ?
                          or coalesce(a.department, '') ilike ? or coalesce(a.category, '') ilike ?)
                    """);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from fa_asset a" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select a.id, a.asset_no as "assetNo", a.name, a.category, a.location, a.department,
                       a.acquisition_date as "acquisitionDate",
                       a.original_value as "originalValue", a.residual_rate as "residualRate",
                       a.useful_life_months as "usefulLifeMonths",
                       a.depreciation_start as "depreciationStart",
                       a.accumulated_depreciation as "accumulatedDepreciation",
                       (a.original_value - a.accumulated_depreciation) as "netValue",
                       a.status, a.disposal_date as "disposalDate",
                       a.disposal_amount as "disposalAmount",
                       a.disposal_gain_loss as "disposalGainLoss",
                       a.remark, a.created_by as "createdBy",
                       to_char(a.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from fa_asset a
                """ + where + " order by a.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    @Transactional
    public long create(AssetRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BizException(1002, "请填写资产名称");
        }
        if (request.acquisitionDate() == null) {
            throw new BizException(1002, "请填写购置日期");
        }
        BigDecimal original = money(request.originalValue());
        if (original.signum() <= 0) {
            throw new BizException(1002, "资产原值必须大于 0");
        }
        int life = request.usefulLifeMonths() == null ? 60 : request.usefulLifeMonths();
        if (life <= 0) {
            throw new BizException(1002, "使用月数必须大于 0");
        }
        BigDecimal residual = request.residualRate() == null
                ? new BigDecimal("0.0500") : request.residualRate();
        if (residual.signum() < 0 || residual.compareTo(BigDecimal.ONE) >= 0) {
            throw new BizException(1002, "残值率必须在 0~1 之间");
        }
        // 固定资产从购置次月起计提折旧
        LocalDate start = request.acquisitionDate().plusMonths(1).withDayOfMonth(1);
        String assetNo = codeGenerator.next("FA");
        Long id = jdbcTemplate.queryForObject("""
                insert into fa_asset (asset_no, name, category, location, department,
                                      acquisition_date, original_value, residual_rate,
                                      useful_life_months, depreciation_start, remark, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, assetNo, request.name().trim(), blankToNull(request.category()),
                blankToNull(request.location()), blankToNull(request.department()),
                request.acquisitionDate(), original, residual, life, start,
                blankToNull(request.remark()), CurrentUser.name());
        if (id == null) {
            throw new BizException(1002, "资产卡片创建失败");
        }
        // 期初导入的资产不生成购置凭证（否则会重复计入银行付款）
        if (request.postVoucher() == null || request.postVoucher()) {
            postingService.post(request.acquisitionDate(), "FA_ACQUIRE", id, assetNo, "CNY",
                    BigDecimal.ONE, "购置固定资产 " + assetNo,
                    List.of(new PostingService.PostingLine("FIXED_ASSET", "固定资产入账", original, BigDecimal.ZERO),
                            new PostingService.PostingLine("BANK", "购置固定资产付款", BigDecimal.ZERO, original)));
        }
        return id;
    }

    /** 按月计提折旧：一条资产一条明细，汇总生成一张凭证；重复执行不会重复计提 */
    @Transactional
    public Map<String, Object> depreciate(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new BizException(1002, "期间格式必须是 YYYY-MM");
        }
        LocalDate periodEnd = YearMonth.parse(period).atEndOfMonth();
        postingService.assertOpen(period);
        List<Map<String, Object>> assets = jdbcTemplate.queryForList("""
                select a.id, a.asset_no as "assetNo", a.name, a.original_value as "originalValue",
                       a.residual_rate as "residualRate", a.useful_life_months as "usefulLifeMonths",
                       a.accumulated_depreciation as "accumulated"
                from fa_asset a
                where a.status = 'IN_USE'
                  and a.depreciation_start <= ?
                  and a.accumulated_depreciation < a.original_value * (1 - a.residual_rate)
                  and not exists (select 1 from fa_depreciation d
                                   where d.asset_id = a.id and d.period = ?)
                order by a.id
                """, periodEnd, period);
        if (assets.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("period", period);
            empty.put("assets", 0);
            empty.put("amount", BigDecimal.ZERO);
            empty.put("voucherNo", null);
            return empty;
        }
        Long runId = jdbcTemplate.queryForObject("select nextval('fa_depreciation_run_seq')", Long.class);
        BigDecimal total = BigDecimal.ZERO;
        List<Long> detailIds = new ArrayList<>();
        for (Map<String, Object> asset : assets) {
            BigDecimal original = (BigDecimal) asset.get("originalValue");
            BigDecimal residual = (BigDecimal) asset.get("residualRate");
            int life = ((Number) asset.get("usefulLifeMonths")).intValue();
            BigDecimal accumulated = (BigDecimal) asset.get("accumulated");
            BigDecimal depreciable = money(original.multiply(BigDecimal.ONE.subtract(residual)));
            BigDecimal monthly = depreciable.divide(BigDecimal.valueOf(life), 2, RoundingMode.HALF_UP);
            BigDecimal remaining = depreciable.subtract(accumulated);
            BigDecimal amount = monthly.min(remaining);
            if (amount.signum() <= 0) {
                continue;
            }
            BigDecimal after = accumulated.add(amount);
            Long detailId = jdbcTemplate.queryForObject("""
                    insert into fa_depreciation (asset_id, period, amount, accumulated_after)
                    values (?, ?, ?, ?) returning id
                    """, Long.class, asset.get("id"), period, amount, after);
            jdbcTemplate.update("""
                    update fa_asset set accumulated_depreciation = ? where id = ?
                    """, after, asset.get("id"));
            detailIds.add(detailId);
            total = total.add(amount);
        }
        if (total.signum() <= 0) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("period", period);
            empty.put("assets", 0);
            empty.put("amount", BigDecimal.ZERO);
            empty.put("voucherNo", null);
            return empty;
        }
        long voucherId = postingService.post(periodEnd, "FA_DEPRECIATION", runId == null ? 0 : runId,
                period, "CNY", BigDecimal.ONE, "固定资产折旧 " + period,
                List.of(new PostingService.PostingLine("DEPRECIATION_EXPENSE", "计提折旧", total, BigDecimal.ZERO),
                        new PostingService.PostingLine("ACCUM_DEPRECIATION", "计提折旧", BigDecimal.ZERO, total)));
        for (Long detailId : detailIds) {
            jdbcTemplate.update("update fa_depreciation set voucher_id = ? where id = ?",
                    voucherId, detailId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("assets", detailIds.size());
        result.put("amount", money(total));
        result.put("voucherNo", jdbcTemplate.queryForObject(
                "select voucher_no from fin_voucher where id = ?", String.class, voucherId));
        return result;
    }

    public PageResult depreciations(String period, Long assetId, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (period != null && !period.isBlank()) {
            where.append(" and d.period = ? ");
            args.add(period.trim());
        }
        if (assetId != null) {
            where.append(" and d.asset_id = ? ");
            args.add(assetId);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from fa_depreciation d" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select d.id, d.asset_id as "assetId", a.asset_no as "assetNo", a.name as "assetName",
                       d.period, d.amount, d.accumulated_after as "accumulatedAfter",
                       d.voucher_id as "voucherId",
                       (select v.voucher_no from fin_voucher v where v.id = d.voucher_id) as "voucherNo",
                       to_char(d.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from fa_depreciation d
                join fa_asset a on a.id = d.asset_id
                """ + where + " order by d.period desc, d.id desc limit ? offset ?",
                pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    @Transactional
    public Map<String, Object> dispose(long id, DisposeRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, asset_no as "assetNo", name, status, original_value as "originalValue",
                       accumulated_depreciation as "accumulated"
                from fa_asset where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "资产不存在");
        }
        Map<String, Object> asset = rows.get(0);
        if ("DISPOSED".equals(asset.get("status"))) {
            throw new BizException(1002, "资产已经处置");
        }
        LocalDate disposalDate = request == null || request.disposalDate() == null
                ? LocalDate.now() : request.disposalDate();
        BigDecimal proceeds = money(request == null ? null : request.amount());
        if (proceeds.signum() < 0) {
            throw new BizException(1002, "处置金额不能为负");
        }
        BigDecimal original = (BigDecimal) asset.get("originalValue");
        BigDecimal accumulated = (BigDecimal) asset.get("accumulated");
        BigDecimal netValue = original.subtract(accumulated);
        BigDecimal gainLoss = money(proceeds.subtract(netValue));

        List<PostingService.PostingLine> lines = new ArrayList<>();
        if (accumulated.signum() > 0) {
            lines.add(new PostingService.PostingLine("ACCUM_DEPRECIATION", "处置转出累计折旧",
                    accumulated, BigDecimal.ZERO));
        }
        if (proceeds.signum() > 0) {
            lines.add(new PostingService.PostingLine("BANK", "处置收款", proceeds, BigDecimal.ZERO));
        }
        if (gainLoss.signum() < 0) {
            lines.add(new PostingService.PostingLine("ASSET_DISPOSAL", "处置损失",
                    gainLoss.negate(), BigDecimal.ZERO));
        }
        lines.add(new PostingService.PostingLine("FIXED_ASSET", "处置转出固定资产",
                BigDecimal.ZERO, original));
        if (gainLoss.signum() > 0) {
            lines.add(new PostingService.PostingLine("ASSET_DISPOSAL", "处置收益",
                    BigDecimal.ZERO, gainLoss));
        }
        postingService.post(disposalDate, "FA_DISPOSAL", id, String.valueOf(asset.get("assetNo")),
                "CNY", BigDecimal.ONE, "固定资产处置 " + asset.get("assetNo"), lines);
        jdbcTemplate.update("""
                update fa_asset set status = 'DISPOSED', disposal_date = ?, disposal_amount = ?,
                       disposal_gain_loss = ?, remark = coalesce(?, remark)
                where id = ?
                """, disposalDate, proceeds, gainLoss,
                request == null ? null : blankToNull(request.remark()), id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("netValue", netValue);
        result.put("proceeds", proceeds);
        result.put("gainLoss", gainLoss);
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
