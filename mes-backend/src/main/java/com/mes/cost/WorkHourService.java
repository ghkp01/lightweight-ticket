package com.mes.cost;

import com.mes.common.BizException;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 工时填报：给绩效算效率提供「实际工时」的来源。
 *
 * <p>为什么按天填而不是一个月填一个总数：出了争议要能回溯到某一天，而且一天里可能
 * 有上午 / 下午 / 加班几段。绩效生成时按月把明细加总，明细留在 {@code work_hour} 里。</p>
 *
 * <p>一天最多 1440 分钟（物理上限），单条也不允许 0 —— 要「没上班」就不填这一条。</p>
 */
@Service
public class WorkHourService {

    private final JdbcTemplate jdbcTemplate;

    public WorkHourService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 一条工时：日期 + 人员 + 分钟数 */
    public record HourRequest(String workDate, String worker, BigDecimal minutes, String remark) {
    }

    /**
     * 批量填报：班组长给一个班组按「同一天、同样时长」各记一条。
     *
     * <p>一个人一条是常态，但每天开班前给十个人各记 480 分钟，逐条填就是十次录入。
     * 这里一次写多条，任何一条不合法整批回滚，不会留下半批数据。</p>
     */
    public record BatchHourRequest(String workDate, BigDecimal minutes, List<String> workers,
                                   String remark) {
    }

    /** 某期间的工时明细（可按人过滤） */
    public Map<String, Object> list(String month, String worker) {
        YearMonth period = parseMonth(month);
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(period.atDay(1)));
        args.add(Date.valueOf(period.plusMonths(1).atDay(1)));
        StringBuilder where = new StringBuilder(" where work_date >= ? and work_date < ? ");
        if (worker != null && !worker.isBlank()) {
            where.append(" and worker ilike ? ");
            args.add("%" + worker.trim() + "%");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, to_char(work_date, 'YYYY-MM-DD') as "workDate", worker, minutes, source,
                       remark, created_by as "createdBy",
                       to_char(created_at, 'MM-DD HH24:MI') as "createdAt"
                from work_hour
                """ + where + " order by work_date desc, worker, id desc", args.toArray());
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            total = total.add(new BigDecimal(String.valueOf(row.get("minutes"))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period.toString());
        result.put("rows", rows);
        result.put("totalMinutes", total.setScale(2, RoundingMode.HALF_UP));
        return result;
    }

    /**
     * 按人汇总某期间的工时。名单取「本期有绩效单的人 ∪ 本期有工时的人」，
     * 这样能看到「谁还没填工时」，而不是只列已填的。
     */
    public Map<String, Object> summary(String month) {
        YearMonth period = parseMonth(month);
        Date start = Date.valueOf(period.atDay(1));
        Date end = Date.valueOf(period.plusMonths(1).atDay(1));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select w.worker, coalesce(h.minutes, 0) as "minutes", coalesce(h.days, 0) as "days"
                from (select worker from performance_sheet where period = ?
                      union
                      select worker from work_hour where work_date >= ? and work_date < ?) w
                left join (select worker, sum(minutes) as minutes, count(distinct work_date) as days
                           from work_hour where work_date >= ? and work_date < ?
                           group by worker) h on h.worker = w.worker
                order by 2 desc, w.worker
                """, period.toString(), start, end, start, end);
        BigDecimal total = BigDecimal.ZERO;
        int filled = 0;
        for (Map<String, Object> row : rows) {
            BigDecimal minutes = new BigDecimal(String.valueOf(row.get("minutes")));
            total = total.add(minutes);
            if (minutes.signum() > 0) {
                filled++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period.toString());
        result.put("workers", rows.size());
        result.put("filled", filled);
        result.put("missing", rows.size() - filled);
        result.put("totalMinutes", total.setScale(2, RoundingMode.HALF_UP));
        result.put("rows", rows);
        return result;
    }

    /** 绩效生成用：期间内每人合计分钟数；没有工时的人不在 map 里 */
    public Map<String, BigDecimal> minutesByWorker(String month) {
        YearMonth period = parseMonth(month);
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select worker, sum(minutes) as "minutes"
                from work_hour where work_date >= ? and work_date < ?
                group by worker
                """, Date.valueOf(period.atDay(1)), Date.valueOf(period.plusMonths(1).atDay(1)))) {
            map.put(String.valueOf(row.get("worker")),
                    new BigDecimal(String.valueOf(row.get("minutes"))));
        }
        return map;
    }

    @Transactional
    public long create(HourRequest request) {
        Validated valid = validate(request);
        return jdbcTemplate.queryForObject("""
                insert into work_hour (work_date, worker, minutes, source, remark, created_by)
                values (?, ?, ?, 'MANUAL', ?, ?) returning id
                """, Long.class, valid.date(), valid.worker(), valid.minutes(), valid.remark(),
                CurrentUser.name());
    }

    /** 批量填报：去重后逐条写入，任何一条不合法整批回滚 */
    @Transactional
    public int createBatch(BatchHourRequest request) {
        if (request == null || request.workers() == null || request.workers().isEmpty()) {
            throw new BizException(1002, "请选择要填报的员工");
        }
        LinkedHashSet<String> workers = new LinkedHashSet<>();
        for (String worker : request.workers()) {
            String trimmed = worker == null ? "" : worker.trim();
            if (!trimmed.isEmpty()) {
                workers.add(trimmed);
            }
        }
        if (workers.isEmpty()) {
            throw new BizException(1002, "请选择要填报的员工");
        }
        if (workers.size() > 200) {
            throw new BizException(1002, "一次最多给 200 人填报工时，请分批提交");
        }
        for (String worker : workers) {
            create(new HourRequest(request.workDate(), worker, request.minutes(), request.remark()));
        }
        return workers.size();
    }

    @Transactional
    public void update(long id, HourRequest request) {
        load(id);
        Validated valid = validate(request);
        jdbcTemplate.update("""
                update work_hour set work_date = ?, worker = ?, minutes = ?, remark = ?,
                       updated_at = now(), version = version + 1
                where id = ?
                """, valid.date(), valid.worker(), valid.minutes(), valid.remark(), id);
    }

    @Transactional
    public void delete(long id) {
        load(id);
        jdbcTemplate.update("delete from work_hour where id = ?", id);
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, worker from work_hour where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(2026, "工时记录不存在");
        }
        return rows.get(0);
    }

    private record Validated(Date date, String worker, BigDecimal minutes, String remark) {
    }

    private Validated validate(HourRequest request) {
        if (request == null) {
            throw new BizException(1002, "请填写工时");
        }
        if (request.workDate() == null || request.workDate().isBlank()) {
            throw new BizException(1002, "请选择工时日期");
        }
        Date date;
        try {
            date = Date.valueOf(LocalDate.parse(request.workDate().trim()));
        } catch (DateTimeParseException error) {
            throw new BizException(1002, "工时日期格式应为 YYYY-MM-DD");
        }
        String worker = request.worker() == null ? "" : request.worker().trim();
        if (worker.isEmpty()) {
            throw new BizException(1002, "请填写员工");
        }
        if (worker.length() > 64) {
            throw new BizException(1002, "员工名不能超过 64 个字符");
        }
        BigDecimal minutes = request.minutes();
        if (minutes == null || minutes.signum() <= 0) {
            throw new BizException(1002, "工时分钟数必须大于 0");
        }
        if (minutes.compareTo(new BigDecimal("1440")) > 0) {
            throw new BizException(1002, "单条工时不能超过 1440 分钟（一天 24 小时）");
        }
        String remark = request.remark() == null ? null : request.remark().trim();
        if (remark != null && remark.isEmpty()) {
            remark = null;
        }
        if (remark != null && remark.length() > 255) {
            throw new BizException(1002, "备注不能超过 255 个字符");
        }
        return new Validated(date, worker, minutes, remark);
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
}
