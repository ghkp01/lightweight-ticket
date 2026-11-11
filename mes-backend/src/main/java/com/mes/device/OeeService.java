package com.mes.device;

import com.mes.common.BizException;
import com.mes.device.api.OeePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备综合效率 OEE（按工作中心统计）。
 *
 * 口径（受现有数据限制，性能项用标准工时达成率近似）：
 * - 计划时间 = 工作中心日产能 × 区间自然日数；
 * - 停机时间 = 该中心设备在区间内的故障停机分钟（未关闭的故障算到当前时间）；
 * - 可用率 A = (计划时间 − 停机时间) ÷ 计划时间；
 * - 性能 P = 标准工时达成量 ÷ 实际开动时间，标准工时 = 报工量 × 单件标准工时；
 * - 质量 Q = 合格数量 ÷ 报工数量；
 * - OEE = A × P × Q。
 *
 * 说明：系统没有采集设备实时开动信号，P 用「标准工时 ÷ 计划开动时间」近似，
 * 用来发现「停得多、做得慢、返工多」三类损失，不做秒级节拍分析。
 */
@Service
public class OeeService implements OeePort {

    private final JdbcTemplate jdbcTemplate;

    public OeeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record OeeQuery(Long workCenterId, String from, String to) {
    }

    public Map<String, Object> calc(OeeQuery query) {
        Date start = parseDate(query == null ? null : query.from(),
                Date.valueOf(LocalDate.now().minusDays(6)));
        Date end = parseDate(query == null ? null : query.to(), Date.valueOf(LocalDate.now()));
        if (start.after(end)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        long days = Math.max(1, (end.toLocalDate().toEpochDay() - start.toLocalDate().toEpochDay()) + 1);
        Long workCenterId = query == null ? null : query.workCenterId();

        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (workCenterId != null) {
            where.append(" and c.id = ? ");
            args.add(workCenterId);
        }
        List<Map<String, Object>> centers = jdbcTemplate.queryForList("""
                select c.id, c.code, c.name, c.type, c.daily_capacity_minutes as "dailyCapacity"
                from rm_work_center c
                """ + where + " order by c.code", args.toArray());

        Map<Long, BigDecimal> downtime = downtimeByCenter(start, end);
        Map<Long, BigDecimal> reported = reportedByCenter(start, end);
        Map<Long, BigDecimal> qualified = qualifiedByCenter(start, end);
        Map<Long, BigDecimal> standardMinutes = standardMinutesByCenter(start, end);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalPlanned = BigDecimal.ZERO;
        BigDecimal totalDowntime = BigDecimal.ZERO;
        BigDecimal totalStandard = BigDecimal.ZERO;
        BigDecimal totalReported = BigDecimal.ZERO;
        BigDecimal totalQualified = BigDecimal.ZERO;

        for (Map<String, Object> center : centers) {
            long id = ((Number) center.get("id")).longValue();
            BigDecimal daily = decimal(center.get("dailyCapacity"));
            BigDecimal planned = daily.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal down = downtime.getOrDefault(id, BigDecimal.ZERO);
            if (down.compareTo(planned) > 0) {
                down = planned;
            }
            BigDecimal runMinutes = planned.subtract(down).max(BigDecimal.ZERO);
            BigDecimal std = standardMinutes.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal qty = reported.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal ok = qualified.getOrDefault(id, BigDecimal.ZERO);

            BigDecimal availability = percent(runMinutes, planned);
            BigDecimal performance = percent(std, runMinutes);
            BigDecimal quality = percent(ok, qty);
            BigDecimal oee = availability.multiply(performance).multiply(quality)
                    .divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workCenterId", id);
            row.put("code", center.get("code"));
            row.put("name", center.get("name"));
            row.put("type", center.get("type"));
            row.put("plannedMinutes", planned);
            row.put("downtimeMinutes", down);
            row.put("runMinutes", runMinutes);
            row.put("standardMinutes", std.setScale(2, RoundingMode.HALF_UP));
            row.put("reportQty", qty);
            row.put("qualifiedQty", ok);
            row.put("availability", availability);
            row.put("performance", performance);
            row.put("quality", quality);
            row.put("oee", oee);
            row.put("hasData", qty.signum() > 0 || down.signum() > 0);
            rows.add(row);

            totalPlanned = totalPlanned.add(planned);
            totalDowntime = totalDowntime.add(down);
            totalStandard = totalStandard.add(std);
            totalReported = totalReported.add(qty);
            totalQualified = totalQualified.add(ok);
        }
        rows.sort((a, b) -> decimal(b.get("oee")).compareTo(decimal(a.get("oee"))));

        BigDecimal runTotal = totalPlanned.subtract(totalDowntime).max(BigDecimal.ZERO);
        BigDecimal overallAvailability = percent(runTotal, totalPlanned);
        BigDecimal overallPerformance = percent(totalStandard, runTotal);
        BigDecimal overallQuality = percent(totalQualified, totalReported);
        BigDecimal overallOee = overallAvailability.multiply(overallPerformance).multiply(overallQuality)
                .divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("days", days);
        summary.put("centers", centers.size());
        summary.put("plannedMinutes", totalPlanned);
        summary.put("downtimeMinutes", totalDowntime);
        summary.put("reportQty", totalReported);
        summary.put("qualifiedQty", totalQualified);
        summary.put("availability", overallAvailability);
        summary.put("performance", overallPerformance);
        summary.put("quality", overallQuality);
        summary.put("oee", overallOee);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", String.valueOf(start));
        result.put("to", String.valueOf(end));
        result.put("summary", summary);
        result.put("rows", rows);
        result.put("faults", faultList(start, end, workCenterId));
        return result;
    }

    /**
     * 大屏用的入口：把 {@link #calc} 的结果折成契约类型，调用方不用认识 Map 的键名。
     * 天数夹在 1~31，防止有人传 365 把库拖死。
     */
    @Override
    public OeeBoard recent(int days) {
        int span = Math.max(1, Math.min(days, 31));
        LocalDate today = LocalDate.now();
        Map<String, Object> result = calc(new OeeQuery(null,
                today.minusDays(span - 1L).toString(), today.toString()));
        Map<String, Object> summary = asMap(result.get("summary"));
        List<CenterOee> centers = new ArrayList<>();
        for (Map<String, Object> row : asRows(result.get("rows"))) {
            centers.add(new CenterOee(((Number) row.get("workCenterId")).longValue(),
                    text(row.get("code")), text(row.get("name")), text(row.get("type")),
                    decimal(row.get("plannedMinutes")), decimal(row.get("downtimeMinutes")),
                    decimal(row.get("availability")), decimal(row.get("performance")),
                    decimal(row.get("quality")), decimal(row.get("oee")),
                    decimal(row.get("reportQty"))));
        }
        return new OeeBoard(centers, decimal(summary.get("availability")),
                decimal(summary.get("performance")), decimal(summary.get("quality")),
                decimal(summary.get("oee")), decimal(summary.get("reportQty")), span);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<Long, BigDecimal> downtimeByCenter(Date start, Date end) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select d.work_center_id as center_id,
                       sum(round(extract(epoch from
                           (coalesce(f.recovered_at, now()) - f.occurred_at)) / 60)) as minutes
                from device_fault f
                join work_center_device d on d.id = f.device_id
                where f.occurred_at::date between ? and ?
                group by d.work_center_id
                """, rs -> {
            map.put(rs.getLong("center_id"), rs.getBigDecimal("minutes"));
        }, start, end);
        // 采集网关或人工补录的停机分钟，与故障停机合并计入可用率
        jdbcTemplate.query("""
                select work_center_id as center_id, sum(downtime_minutes) as minutes
                from device_telemetry
                where work_center_id is not null and recorded_at::date between ? and ?
                group by work_center_id
                """, rs -> {
            long centerId = rs.getLong("center_id");
            BigDecimal minutes = rs.getBigDecimal("minutes");
            if (minutes != null) {
                map.merge(centerId, minutes, BigDecimal::add);
            }
        }, start, end);
        return map;
    }

    private Map<Long, BigDecimal> reportedByCenter(Date start, Date end) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select p.work_center_id as center_id, sum(r.qty) as qty
                from work_report r
                join rm_work_order_operation o on o.id = r.operation_id
                join rm_process p on p.id = o.process_id
                where r.reported_at::date between ? and ? and p.work_center_id is not null
                group by p.work_center_id
                """, rs -> {
            map.put(rs.getLong("center_id"), rs.getBigDecimal("qty"));
        }, start, end);
        return map;
    }

    private Map<Long, BigDecimal> qualifiedByCenter(Date start, Date end) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select p.work_center_id as center_id, sum(r.qualified_qty) as qty
                from work_report r
                join rm_work_order_operation o on o.id = r.operation_id
                join rm_process p on p.id = o.process_id
                where r.reported_at::date between ? and ? and p.work_center_id is not null
                group by p.work_center_id
                """, rs -> {
            map.put(rs.getLong("center_id"), rs.getBigDecimal("qty"));
        }, start, end);
        return map;
    }

    /** 标准工时 = 报工量 × 单件标准工时（工序标准总工时 ÷ 计划数量） */
    private Map<Long, BigDecimal> standardMinutesByCenter(Date start, Date end) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select p.work_center_id as center_id,
                       sum(r.qty * case when o.plan_qty > 0 then o.standard_minutes / o.plan_qty else 0 end) as minutes
                from work_report r
                join rm_work_order_operation o on o.id = r.operation_id
                join rm_process p on p.id = o.process_id
                where r.reported_at::date between ? and ? and p.work_center_id is not null
                group by p.work_center_id
                """, rs -> {
            map.put(rs.getLong("center_id"), rs.getBigDecimal("minutes"));
        }, start, end);
        return map;
    }

    private List<Map<String, Object>> faultList(Date start, Date end, Long workCenterId) {
        StringBuilder where = new StringBuilder(" where f.occurred_at::date between ? and ? ");
        List<Object> args = new ArrayList<>();
        args.add(start);
        args.add(end);
        if (workCenterId != null) {
            where.append(" and d.work_center_id = ? ");
            args.add(workCenterId);
        }
        return jdbcTemplate.queryForList("""
                select f.fault_no as "faultNo", d.code as "deviceCode", d.name as "deviceName",
                       c.name as "workCenterName", f.symptom, f.status,
                       round(extract(epoch from (coalesce(f.recovered_at, now()) - f.occurred_at)) / 60) as "minutes",
                       to_char(f.occurred_at, 'YYYY-MM-DD HH24:MI') as "occurredAt"
                from device_fault f
                join work_center_device d on d.id = f.device_id
                join rm_work_center c on c.id = d.work_center_id
                """ + where + " order by f.occurred_at desc limit 100", args.toArray());
    }

    private BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0 || part == null) {
            return BigDecimal.ZERO;
        }
        return part.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private Date parseDate(String value, Date fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Date.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(1002, "日期格式应为 yyyy-MM-dd：" + value);
        }
    }
}
