package com.mes.cost;

import com.mes.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绩效核算：生成 SQL 能跑通、录工时后效率与工资重算、状态机与「提交后不许再改」。
 *
 * <p>整类在事务里跑，结束回滚，不影响其他用例的数据。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PerformanceServiceTest {

    @Autowired
    private PerformanceService service;

    @Autowired
    private WorkHourService workHours;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String PERIOD = YearMonth.now().toString();

    @Test
    @DisplayName("按期生成：报工汇总能落成绩效单，重复生成不重复建行")
    void generateIsIdempotent() {
        Map<String, Object> first = service.generate(PERIOD);
        assertNotNull(first.get("period"));
        int workers = ((Number) first.get("workers")).intValue();

        Map<String, Object> second = service.generate(PERIOD);
        int again = ((Number) second.get("workers")).intValue();
        assertEquals(workers, again, "同期间两次生成的参与人数应一致");
        // 第二次应该只刷新、不新增（除非第一次全部被锁定）
        assertEquals(0, ((Number) second.get("created")).intValue());

        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from performance_sheet where period = ?", Long.class, PERIOD);
        assertEquals(workers, rows == null ? 0 : rows.intValue(),
                "绩效单行数应等于参与人数，说明 upsert 命中唯一键");
    }

    @Test
    @DisplayName("录工时后效率、系数、绩效工资一起重算；提交后不允许再改")
    void minutesRecalculateAndStatusLocks() {
        service.generate(PERIOD);
        List<Map<String, Object>> sheets = jdbcTemplate.queryForList("""
                select id, piece_wage, report_qty from performance_sheet
                where period = ? and status = 'DRAFT' order by piece_wage desc limit 1
                """, PERIOD);
        if (sheets.isEmpty()) {
            // 测试库里这一期间没有任何报工，就没什么可校验的，交给 e2e 覆盖
            return;
        }
        long id = ((Number) sheets.get(0).get("id")).longValue();
        BigDecimal pieceWage = new BigDecimal(String.valueOf(sheets.get(0).get("piece_wage")));

        // 标准工时的 2 倍作为实际工时：效率 50%，效率系数 0.90
        BigDecimal standard = jdbcTemplate.queryForObject(
                "select standard_minutes from performance_sheet where id = ?", BigDecimal.class, id);
        BigDecimal actual = standard.multiply(new BigDecimal("2"));

        Map<String, Object> updated = service.updateMinutes(id,
                new PerformanceService.MinutesRequest(actual, "单测录入"));
        assertEquals(0, new BigDecimal(String.valueOf(updated.get("efficiency")))
                .compareTo(new BigDecimal("0.5000")));
        // 系数是「效率系数 × 良率系数」，效率 50% 固定 0.90，良率系数按这条记录的实际良率取
        BigDecimal yieldRate = new BigDecimal(String.valueOf(updated.get("yieldRate")));
        BigDecimal expectedScore = new BigDecimal("0.9000")
                .multiply(PerformanceService.yieldFactor(yieldRate))
                .setScale(4, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expectedScore.compareTo(new BigDecimal(String.valueOf(updated.get("score")))));

        BigDecimal wage = new BigDecimal(String.valueOf(updated.get("wage")));
        assertEquals(pieceWage.multiply(expectedScore).setScale(2, java.math.RoundingMode.HALF_UP), wage);

        service.submit(id);
        assertEquals("SUBMITTED", String.valueOf(service.detail(id).get("status")));
        assertThrows(BizException.class, () -> service.updateMinutes(id,
                new PerformanceService.MinutesRequest(standard, "提交后再改")));

        service.approve(id);
        assertEquals("APPROVED", String.valueOf(service.detail(id).get("status")));
        assertThrows(BizException.class, () -> service.submit(id));
    }

    @Test
    @DisplayName("撤回重算：已提交的单退回草稿且工时不丢；草稿不能再撤回")
    void reopenReturnsToDraft() {
        // 这条用例验证「没配审批流」那条路径，否则审批实例会把状态再写回来干扰断言
        jdbcTemplate.update("update sys_approval_flow set enabled = false where biz_type = 'PERFORMANCE'");
        service.generate(PERIOD);
        List<Map<String, Object>> sheets = jdbcTemplate.queryForList("""
                select id, actual_minutes from performance_sheet
                where period = ? and status = 'DRAFT' order by piece_wage desc limit 1
                """, PERIOD);
        if (sheets.isEmpty()) {
            return;
        }
        long id = ((Number) sheets.get(0).get("id")).longValue();
        Object minutesBefore = sheets.get(0).get("actual_minutes");

        service.submit(id);
        assertEquals("SUBMITTED", String.valueOf(service.detail(id).get("status")));

        Map<String, Object> reopened = service.reopen(id,
                new PerformanceService.ReopenRequest("工时漏填，退回重算"));
        assertEquals("DRAFT", String.valueOf(reopened.get("status")));
        assertEquals(null, reopened.get("approvalInstanceId"));
        Object minutesAfter = reopened.get("actualMinutes");
        if (minutesBefore == null) {
            assertEquals(null, minutesAfter);
        } else {
            assertEquals(0, new BigDecimal(String.valueOf(minutesBefore))
                    .compareTo(new BigDecimal(String.valueOf(minutesAfter))), "撤回不该动已算好的工时");
        }

        assertThrows(BizException.class, () -> service.reopen(id,
                new PerformanceService.ReopenRequest("草稿再撤一次")));
    }

    @Test
    @DisplayName("工时表优先：填了两天工时后，绩效的实际工时取合计值并标明来源")
    void workHourSheetDrivesActualMinutes() {
        // 找一个本期有报工的人，没有就跳过（数据依赖，交给 e2e 覆盖）
        List<Map<String, Object>> workers = jdbcTemplate.queryForList("""
                select coalesce(nullif(trim(worker), ''), '未指定') as "worker"
                from work_report
                where reported_at::date >= date_trunc('month', current_date)::date
                group by 1 limit 1
                """);
        if (workers.isEmpty()) {
            return;
        }
        String worker = String.valueOf(workers.get(0).get("worker"));
        String month = YearMonth.now().toString();
        String day1 = YearMonth.now().atDay(1).toString();
        String day2 = YearMonth.now().atDay(2).toString();
        workHours.create(new WorkHourService.HourRequest(day1, worker, new BigDecimal("300"), "上午"));
        workHours.create(new WorkHourService.HourRequest(day2, worker, new BigDecimal("180"), "下午"));

        Map<String, BigDecimal> minutes = workHours.minutesByWorker(month);
        assertEquals(0, new BigDecimal("480").compareTo(minutes.get(worker)));

        service.generate(PERIOD);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select actual_minutes as "actualMinutes", minutes_source as "minutesSource",
                       efficiency, standard_minutes as "standardMinutes"
                from performance_sheet where period = ? and worker = ?
                """, PERIOD, worker);
        assertEquals(0, new BigDecimal("480").compareTo(
                new BigDecimal(String.valueOf(row.get("actualMinutes")))));
        assertEquals("HOUR_SHEET", String.valueOf(row.get("minutesSource")));
        BigDecimal standard = new BigDecimal(String.valueOf(row.get("standardMinutes")));
        assertEquals(0, standard.divide(new BigDecimal("480"), 4, java.math.RoundingMode.HALF_UP)
                .compareTo(new BigDecimal(String.valueOf(row.get("efficiency")))));
    }

    @Test
    @DisplayName("工时填报校验：0 分钟、超过一天、非法日期都被拒")
    void invalidHourRejected() {
        String today = YearMonth.now().atDay(1).toString();
        assertThrows(BizException.class, () -> workHours.create(
                new WorkHourService.HourRequest(today, "张三", BigDecimal.ZERO, null)));
        assertThrows(BizException.class, () -> workHours.create(
                new WorkHourService.HourRequest(today, "张三", new BigDecimal("1441"), null)));
        assertThrows(BizException.class, () -> workHours.create(
                new WorkHourService.HourRequest("2026/01/01", "张三", new BigDecimal("60"), null)));
        assertThrows(BizException.class, () -> workHours.create(
                new WorkHourService.HourRequest(today, " ", new BigDecimal("60"), null)));
    }

    @Test
    @DisplayName("汇总：人数与工资合计能和明细对上")
    void summaryMatchesRows() {
        service.generate(PERIOD);
        Map<String, Object> summary = service.summary(PERIOD);
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from performance_sheet where period = ?", Long.class, PERIOD);
        assertEquals(count == null ? 0 : count.intValue(),
                ((Number) summary.get("workers")).intValue());
        assertTrue(((Number) summary.get("wage")).doubleValue() >= 0);
    }
}
