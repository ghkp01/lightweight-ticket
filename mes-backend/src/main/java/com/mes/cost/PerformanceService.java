package com.mes.cost;

import com.mes.approval.api.ApprovalPort;
import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.platform.audit.OperLogService;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度绩效核算：计件工资 + 效率 + 良率 → 绩效系数 → 绩效工资。
 *
 * <p><b>口径</b>（这套规则就是「工人绩效」的全部，不做考勤与工资发放）：</p>
 * <ol>
 *   <li>报工量、合格量、不良量：来自 {@code work_report}，按人按期间汇总；</li>
 *   <li>标准工时 = Σ(报工量 × 工序标准分钟 ÷ 工序计划量) —— 用「单位标准工时」折算，
 *       避免直接用工序整单标准工时导致多报一次就翻倍；</li>
 *   <li>计件工资 = Σ(合格量 × 工序计件单价)，与生产报表的计件工资口径一致；</li>
 *   <li>实际工时：**系统没有考勤数据，由车间在绩效单上录入**。没填时效率留空、效率系数按 1.0，
 *       不猜也不编；</li>
 *   <li>效率 = 标准工时 ÷ 实际工时；良率 = 合格量 ÷ 报工量；</li>
 *   <li>绩效系数 = 效率系数 × 良率系数（分段见 {@link #efficiencyFactor} / {@link #yieldFactor}）；</li>
 *   <li>绩效工资 = 计件工资 × 绩效系数。</li>
 * </ol>
 *
 * <p><b>状态机</b>：草稿（可改工时）→ 已提交 → 已通过 / 已驳回（驳回后可改再提交）。
 * 已提交与已通过可以用 {@link #reopen} 撤回成草稿重算。
 * 只有草稿与已驳回会被「重新生成」覆盖，已提交与已通过的原样保留，避免月底一发工资数据就变了。</p>
 */
@Service
public class PerformanceService {

    /** 效率分段：≥100% 奖 10%，90%~100% 不奖不罚，80%~90% 罚 5%，<80% 罚 10% */
    private static final BigDecimal EFFICIENCY_BONUS = new BigDecimal("1.10");
    private static final BigDecimal EFFICIENCY_FULL = new BigDecimal("1.00");
    private static final BigDecimal EFFICIENCY_MINOR = new BigDecimal("0.95");
    private static final BigDecimal EFFICIENCY_PENALTY = new BigDecimal("0.90");

    /** 良率分段：≥99% 奖 5%，97%~99% 不奖不罚，95%~97% 罚 5%，<95% 罚 10% */
    private static final BigDecimal YIELD_BONUS = new BigDecimal("1.05");
    private static final BigDecimal YIELD_FULL = new BigDecimal("1.00");
    private static final BigDecimal YIELD_MINOR = new BigDecimal("0.95");
    private static final BigDecimal YIELD_PENALTY = new BigDecimal("0.90");

    private static final List<String> LOCKED = List.of("SUBMITTED", "APPROVED");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "DRAFT", "草稿", "SUBMITTED", "已提交", "APPROVED", "已通过", "REJECTED", "已驳回");

    private final JdbcTemplate jdbcTemplate;
    private final OperLogService operLogService;
    private final WorkHourService workHours;
    private final ApprovalPort approval;

    public PerformanceService(JdbcTemplate jdbcTemplate, OperLogService operLogService,
                              WorkHourService workHours, ApprovalPort approval) {
        this.jdbcTemplate = jdbcTemplate;
        this.operLogService = operLogService;
        this.workHours = workHours;
        this.approval = approval;
    }

    public record MinutesRequest(BigDecimal actualMinutes, String remark) {
    }

    public record RejectRequest(String reason) {
    }

    public record ReopenRequest(String reason) {
    }

    // ---------------------------------------------------------------- 生成与重算

    /**
     * 按期间生成（或刷新）绩效单：只动草稿与已驳回，已提交/已通过的不覆盖。
     *
     * @return 生成明细：新增几条、刷新几条、跳过几条（已锁定的）
     */
    @Transactional
    public Map<String, Object> generate(String period) {
        YearMonth month = parseMonth(period);
        Date start = Date.valueOf(month.atDay(1));
        Date end = Date.valueOf(month.plusMonths(1).atDay(1));

        List<Map<String, Object>> aggregates = jdbcTemplate.queryForList("""
                select coalesce(nullif(trim(r.worker), ''), '未指定') as "worker",
                       count(distinct r.work_order_id) as "workOrders",
                       coalesce(sum(r.qty), 0) as "reportQty",
                       coalesce(sum(r.qualified_qty), 0) as "qualifiedQty",
                       coalesce(sum(r.defect_qty), 0) as "defectQty",
                       coalesce(sum(r.qty * case when o.plan_qty > 0
                                                 then o.standard_minutes / o.plan_qty else 0 end), 0)
                           as "standardMinutes",
                       coalesce(sum(r.qualified_qty * coalesce(o.unit_price, 0)), 0) as "pieceWage"
                from work_report r
                join rm_work_order_operation o on o.id = r.operation_id
                where r.reported_at::date >= ? and r.reported_at::date < ?
                group by 1
                order by 1
                """, start, end);

        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, worker, status, actual_minutes from performance_sheet where period = ?",
                month.toString())) {
            existing.put(String.valueOf(row.get("worker")), row);
        }

        // 实际工时的首选来源是工时填报（work_hour 按月汇总）；这个人没填过工时，
        // 才沿用绩效单上手工录入的旧值，并在 minutes_source 里标出来历
        Map<String, BigDecimal> sheetMinutes = workHours.minutesByWorker(month.toString());

        int created = 0;
        int refreshed = 0;
        int skipped = 0;
        List<String> keptWorkers = new ArrayList<>();
        for (Map<String, Object> row : aggregates) {
            String worker = String.valueOf(row.get("worker"));
            keptWorkers.add(worker);
            Map<String, Object> current = existing.get(worker);
            if (current != null && LOCKED.contains(String.valueOf(current.get("status")))) {
                skipped++;
                continue;
            }
            BigDecimal fromHours = sheetMinutes.get(worker);
            BigDecimal manual = current == null ? null : decimalOrNull(current.get("actual_minutes"));
            BigDecimal actualMinutes = fromHours != null ? fromHours : manual;
            String minutesSource = fromHours != null ? "HOUR_SHEET" : (manual != null ? "MANUAL" : null);
            writeSheet(month.toString(), worker, row, actualMinutes, minutesSource);
            if (current == null) {
                created++;
            } else {
                refreshed++;
            }
        }
        // 本月已没有报工记录、且还是草稿的人：跟着删掉，避免留下一条空壳绩效单
        int removed = 0;
        for (Map.Entry<String, Map<String, Object>> entry : existing.entrySet()) {
            if (keptWorkers.contains(entry.getKey()) || LOCKED.contains(String.valueOf(entry.getValue().get("status")))) {
                continue;
            }
            jdbcTemplate.update("delete from performance_sheet where id = ?", entry.getValue().get("id"));
            removed++;
        }

        operLogService.touch("PERFORMANCE", null, month.toString(),
                "生成绩效单：" + month + "，新增 " + created + "、刷新 " + refreshed + "、跳过已锁定 " + skipped);
        operLogService.action("绩效核算");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", month.toString());
        result.put("workers", aggregates.size());
        result.put("created", created);
        result.put("refreshed", refreshed);
        result.put("skipped", skipped);
        result.put("removed", removed);
        return result;
    }

    /** 录入实际工时：只有草稿与已驳回可以改，改完立刻重算效率、系数与工资 */
    @Transactional
    public Map<String, Object> updateMinutes(long id, MinutesRequest request) {
        Map<String, Object> sheet = load(id);
        String status = String.valueOf(sheet.get("status"));
        if (LOCKED.contains(status)) {
            throw new BizException(2021, "已提交或已通过的绩效单不能再改工时");
        }
        BigDecimal actualMinutes = request == null ? null : request.actualMinutes();
        if (actualMinutes != null && actualMinutes.signum() < 0) {
            throw new BizException(1002, "实际工时不能为负数");
        }
        String remark = request == null ? null : trimToNull(request.remark());
        jdbcTemplate.update("""
                update performance_sheet
                set actual_minutes = ?, remark = ?, minutes_source = 'MANUAL',
                    updated_at = now(), version = version + 1
                where id = ?
                """, actualMinutes, remark, id);
        recalc(id);
        return detail(id);
    }

    // ---------------------------------------------------------------- 审核流转

    @Transactional
    public Map<String, Object> submit(long id) {
        Map<String, Object> sheet = load(id);
        String status = String.valueOf(sheet.get("status"));
        if (!"DRAFT".equals(status) && !"REJECTED".equals(status)) {
            throw new BizException(2022, "只有草稿或已驳回的绩效单可以提交");
        }
        jdbcTemplate.update("""
                update performance_sheet
                set status = 'SUBMITTED', submitted_at = now(), reject_reason = null,
                    updated_at = now(), version = version + 1
                where id = ?
                """, id);
        // 配了 PERFORMANCE 审批流就进审批中心；没配流程时保持「已提交」，由主管在绩效页直接审核
        Long instanceId = approval.submit("PERFORMANCE", id,
                String.valueOf(sheet.get("period")) + "-" + sheet.get("worker"),
                "绩效核算 " + sheet.get("period") + " " + sheet.get("worker"),
                Map.of("wage", sheet.get("wage") == null ? BigDecimal.ZERO : sheet.get("wage"),
                        "period", String.valueOf(sheet.get("period"))));
        if (instanceId != null) {
            jdbcTemplate.update("update performance_sheet set approval_instance_id = ? where id = ?",
                    instanceId, id);
            // 条件把节点全跳过时审批会立即通过，这时单据直接落成已通过
            if ("APPROVED".equals(approval.statusOf(instanceId))) {
                jdbcTemplate.update("""
                        update performance_sheet
                        set status = 'APPROVED', approved_by = 'system', approved_at = now(),
                            updated_at = now(), version = version + 1
                        where id = ?
                        """, id);
            }
        }
        log(sheet, "提交绩效单：" + sheet.get("worker") + " " + sheet.get("period")
                + (instanceId == null ? "（未配置审批流，转人工审核）" : "（已提交审批）"));
        return detail(id);
    }

    @Transactional
    public Map<String, Object> approve(long id) {
        Map<String, Object> sheet = load(id);
        assertNotInFlow(sheet);
        if (!"SUBMITTED".equals(String.valueOf(sheet.get("status")))) {
            throw new BizException(2023, "只有已提交的绩效单可以审核通过");
        }
        jdbcTemplate.update("""
                update performance_sheet
                set status = 'APPROVED', approved_by = ?, approved_at = now(),
                    updated_at = now(), version = version + 1
                where id = ?
                """, CurrentUser.name(), id);
        log(sheet, "审核通过绩效单：" + sheet.get("worker") + " " + sheet.get("period")
                + "，绩效工资 " + sheet.get("wage"));
        return detail(id);
    }

    @Transactional
    public Map<String, Object> reject(long id, RejectRequest request) {
        Map<String, Object> sheet = load(id);
        assertNotInFlow(sheet);
        if (!"SUBMITTED".equals(String.valueOf(sheet.get("status")))) {
            throw new BizException(2024, "只有已提交的绩效单可以驳回");
        }
        String reason = request == null ? null : trimToNull(request.reason());
        if (reason == null) {
            throw new BizException(1002, "请填写驳回原因");
        }
        jdbcTemplate.update("""
                update performance_sheet
                set status = 'REJECTED', reject_reason = ?, approved_by = ?,
                    updated_at = now(), version = version + 1
                where id = ?
                """, reason, CurrentUser.name(), id);
        log(sheet, "驳回绩效单：" + sheet.get("worker") + " " + sheet.get("period") + "，原因：" + reason);
        return detail(id);
    }

    /** 已经进了审批流的单据只能在审批中心处理，避免两处都能批导致状态打架 */
    private void assertNotInFlow(Map<String, Object> sheet) {
        if (sheet.get("approval_instance_id") != null) {
            throw new BizException(2027, "该绩效单已进入审批流，请在审批中心处理");
        }
    }

    /**
     * 撤回重算：把已提交 / 已通过的绩效单退回草稿。
     *
     * <p>月底算错了（工时漏填、单子挂错人）必须能退回重算，而不是只能干看着。
     * 单据还在审批流里就先撤回审批实例，审批侧的回调会把状态写回草稿，这里再补一次清理，
     * 保证 {@code approval_instance_id} 不会指着一个作废的实例。</p>
     */
    @Transactional
    public Map<String, Object> reopen(long id, ReopenRequest request) {
        Map<String, Object> sheet = load(id);
        String status = String.valueOf(sheet.get("status"));
        if (!"SUBMITTED".equals(status) && !"APPROVED".equals(status)) {
            throw new BizException(2028, "只有已提交或已通过的绩效单可以撤回重算");
        }
        Object rawInstance = sheet.get("approval_instance_id");
        if (rawInstance != null) {
            long instanceId = ((Number) rawInstance).longValue();
            if ("PENDING".equals(approval.statusOf(instanceId))) {
                approval.cancel(instanceId);
            }
        }
        String reason = request == null ? null : trimToNull(request.reason());
        jdbcTemplate.update("""
                update performance_sheet
                set status = 'DRAFT', submitted_at = null, approved_by = null, approved_at = null,
                    reject_reason = null, approval_instance_id = null,
                    remark = coalesce(?, remark), updated_at = now(), version = version + 1
                where id = ?
                """, reason, id);
        log(sheet, "撤回绩效单：" + sheet.get("worker") + " " + sheet.get("period")
                + (reason == null ? "" : "，原因：" + reason));
        return detail(id);
    }

    // ---------------------------------------------------------------- 查询

    public PageResult list(String period, String status, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (period != null && !period.isBlank()) {
            where.append(" and period = ? ");
            args.add(parseMonth(period).toString());
        }
        if (status != null && !status.isBlank()) {
            where.append(" and status = ? ");
            args.add(status.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and worker ilike ? ");
            args.add("%" + keyword.trim() + "%");
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from performance_sheet" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, period, worker, work_orders as "workOrders", report_qty as "reportQty",
                       qualified_qty as "qualifiedQty", defect_qty as "defectQty",
                       standard_minutes as "standardMinutes", actual_minutes as "actualMinutes",
                       minutes_source as "minutesSource", approval_instance_id as "approvalInstanceId",
                       piece_wage as "pieceWage", efficiency, yield_rate as "yieldRate",
                       score, wage, status, remark, reject_reason as "rejectReason",
                       created_by as "createdBy", approved_by as "approvedBy",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(submitted_at, 'YYYY-MM-DD HH24:MI') as "submittedAt",
                       to_char(approved_at, 'YYYY-MM-DD HH24:MI') as "approvedAt"
                from performance_sheet
                """ + where + " order by wage desc, worker limit ? offset ?", pageArgs.toArray());
        rows.forEach(row -> row.put("statusLabel", STATUS_LABELS.getOrDefault(
                String.valueOf(row.get("status")), String.valueOf(row.get("status")))));
        return new PageResult(rows, total == null ? 0 : total, safePage, safeSize);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList("""
                select id, period, worker, work_orders as "workOrders", report_qty as "reportQty",
                       qualified_qty as "qualifiedQty", defect_qty as "defectQty",
                       standard_minutes as "standardMinutes", actual_minutes as "actualMinutes",
                       minutes_source as "minutesSource", approval_instance_id as "approvalInstanceId",
                       piece_wage as "pieceWage", efficiency, yield_rate as "yieldRate",
                       score, wage, status, remark, reject_reason as "rejectReason",
                       created_by as "createdBy", approved_by as "approvedBy",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(submitted_at, 'YYYY-MM-DD HH24:MI') as "submittedAt",
                       to_char(approved_at, 'YYYY-MM-DD HH24:MI') as "approvedAt"
                from performance_sheet where id = ?
                """, id);
        if (result.isEmpty()) {
            throw new BizException(2025, "绩效单不存在");
        }
        Map<String, Object> row = result.get(0);
        Map<String, Object> detail = new LinkedHashMap<>(row);
        detail.put("statusLabel", STATUS_LABELS.getOrDefault(
                String.valueOf(row.get("status")), String.valueOf(row.get("status"))));
        return detail;
    }

    /** 期间汇总：人数、工时、计件与绩效工资、平均效率与良率 */
    public Map<String, Object> summary(String period) {
        YearMonth month = parseMonth(period);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select count(*) as "workers",
                       coalesce(sum(standard_minutes), 0) as "standardMinutes",
                       coalesce(sum(actual_minutes), 0) as "actualMinutes",
                       coalesce(sum(piece_wage), 0) as "pieceWage",
                       coalesce(sum(wage), 0) as "wage",
                       coalesce(avg(efficiency), 0) as "avgEfficiency",
                       coalesce(avg(yield_rate), 0) as "avgYieldRate",
                       count(*) filter (where actual_minutes is null) as "missingMinutes",
                       count(*) filter (where status = 'APPROVED') as "approved",
                       count(*) filter (where status = 'SUBMITTED') as "submitted",
                       count(*) filter (where status = 'DRAFT') as "draft",
                       count(*) filter (where status = 'REJECTED') as "rejected"
                from performance_sheet where period = ?
                """, month.toString());
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("period", month.toString());
        return result;
    }

    // ---------------------------------------------------------------- 内部

    /** 写入/刷新一张绩效单：先按现有实际工时算派生字段，再 upsert */
    private void writeSheet(String period, String worker, Map<String, Object> aggregate,
                            BigDecimal actualMinutes, String minutesSource) {
        BigDecimal reportQty = decimal(aggregate.get("reportQty"));
        BigDecimal qualifiedQty = decimal(aggregate.get("qualifiedQty"));
        BigDecimal defectQty = decimal(aggregate.get("defectQty"));
        BigDecimal standardMinutes = decimal(aggregate.get("standardMinutes"));
        BigDecimal pieceWage = decimal(aggregate.get("pieceWage"));

        BigDecimal efficiency = efficiency(standardMinutes, actualMinutes);
        BigDecimal yieldRate = rate(qualifiedQty, reportQty);
        BigDecimal score = score(efficiency, yieldRate);
        BigDecimal wage = pieceWage.multiply(score).setScale(2, RoundingMode.HALF_UP);
        Integer workOrders = aggregate.get("workOrders") == null ? 0
                : ((Number) aggregate.get("workOrders")).intValue();

        jdbcTemplate.update("""
                insert into performance_sheet (period, worker, work_orders, report_qty, qualified_qty,
                                               defect_qty, standard_minutes, actual_minutes, piece_wage,
                                               efficiency, yield_rate, score, wage, minutes_source,
                                               status, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                on conflict (period, worker) do update set
                    work_orders = excluded.work_orders,
                    report_qty = excluded.report_qty,
                    qualified_qty = excluded.qualified_qty,
                    defect_qty = excluded.defect_qty,
                    standard_minutes = excluded.standard_minutes,
                    actual_minutes = excluded.actual_minutes,
                    minutes_source = excluded.minutes_source,
                    piece_wage = excluded.piece_wage,
                    efficiency = excluded.efficiency,
                    yield_rate = excluded.yield_rate,
                    score = excluded.score,
                    wage = excluded.wage,
                    updated_at = now(),
                    version = performance_sheet.version + 1
                """, period, worker, workOrders, reportQty, qualifiedQty, defectQty, standardMinutes,
                actualMinutes, pieceWage, efficiency, yieldRate, score, wage, minutesSource,
                CurrentUser.name());
    }

    /** 改了实际工时之后重算派生字段（系统算出来的部分不动） */
    private void recalc(long id) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select report_qty, qualified_qty, standard_minutes, piece_wage, actual_minutes
                from performance_sheet where id = ?
                """, id);
        BigDecimal reportQty = decimal(row.get("report_qty"));
        BigDecimal qualifiedQty = decimal(row.get("qualified_qty"));
        BigDecimal standardMinutes = decimal(row.get("standard_minutes"));
        BigDecimal pieceWage = decimal(row.get("piece_wage"));
        BigDecimal actualMinutes = decimalOrNull(row.get("actual_minutes"));
        BigDecimal efficiency = efficiency(standardMinutes, actualMinutes);
        BigDecimal yieldRate = rate(qualifiedQty, reportQty);
        BigDecimal score = score(efficiency, yieldRate);
        BigDecimal wage = pieceWage.multiply(score).setScale(2, RoundingMode.HALF_UP);
        jdbcTemplate.update("""
                update performance_sheet
                set efficiency = ?, yield_rate = ?, score = ?, wage = ?,
                    updated_at = now(), version = version + 1
                where id = ?
                """, efficiency, yieldRate, score, wage, id);
    }

    /** 效率 = 标准工时 ÷ 实际工时；没录工时时返回 null（不猜） */
    public static BigDecimal efficiency(BigDecimal standardMinutes, BigDecimal actualMinutes) {
        if (standardMinutes == null || actualMinutes == null || actualMinutes.signum() <= 0) {
            return null;
        }
        return standardMinutes.divide(actualMinutes, 4, RoundingMode.HALF_UP);
    }

    /** 效率系数：没录工时按 1.0，不给奖惩 */
    public static BigDecimal efficiencyFactor(BigDecimal efficiency) {
        if (efficiency == null) {
            return EFFICIENCY_FULL;
        }
        if (efficiency.compareTo(BigDecimal.ONE) >= 0) {
            return EFFICIENCY_BONUS;
        }
        if (efficiency.compareTo(new BigDecimal("0.90")) >= 0) {
            return EFFICIENCY_FULL;
        }
        if (efficiency.compareTo(new BigDecimal("0.80")) >= 0) {
            return EFFICIENCY_MINOR;
        }
        return EFFICIENCY_PENALTY;
    }

    /** 良率系数：良率为空（没有报工）按 1.0 */
    public static BigDecimal yieldFactor(BigDecimal yieldRate) {
        if (yieldRate == null) {
            return YIELD_FULL;
        }
        if (yieldRate.compareTo(new BigDecimal("0.99")) >= 0) {
            return YIELD_BONUS;
        }
        if (yieldRate.compareTo(new BigDecimal("0.97")) >= 0) {
            return YIELD_FULL;
        }
        if (yieldRate.compareTo(new BigDecimal("0.95")) >= 0) {
            return YIELD_MINOR;
        }
        return YIELD_PENALTY;
    }

    /** 绩效系数 = 效率系数 × 良率系数，四舍五入到 4 位 */
    public static BigDecimal score(BigDecimal efficiency, BigDecimal yieldRate) {
        return efficiencyFactor(efficiency).multiply(yieldFactor(yieldRate))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            return null;
        }
        return part.divide(total, 4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, period, worker, status, wage, approval_instance_id
                from performance_sheet where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2025, "绩效单不存在");
        }
        return rows.get(0);
    }

    private void log(Map<String, Object> sheet, String summary) {
        operLogService.touch("PERFORMANCE", ((Number) sheet.get("id")).longValue(),
                String.valueOf(sheet.get("period")), summary);
        operLogService.action("绩效核算");
    }

    private static YearMonth parseMonth(String period) {
        if (period == null || period.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException error) {
            throw new BizException(1002, "期间格式应为 YYYY-MM");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal decimalOrNull(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }
}
