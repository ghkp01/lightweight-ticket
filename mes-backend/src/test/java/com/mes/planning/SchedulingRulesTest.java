package com.mes.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有限产能排产测试：工序级占用工作中心日产能，单工序不跨天，工序顺序衔接。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchedulingRulesTest {

    @Autowired
    private SchedulingService schedulingService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("有限产能排产不超日产能且工序顺序衔接")
    void apsRespectsDailyCapacity() {
        List<Long> workIds = new ArrayList<>();
        for (int qty : new int[]{100, 80, 60}) {
            Long id = jdbc.queryForObject("""
                    insert into work_order (work_no, product_id, plan_qty, status, priority)
                    values (?, 1, ?, 'DRAFT', 'NORMAL') returning id
                    """, Long.class, "WOAPS" + System.nanoTime() + qty, qty);
            jdbc.update("""
                    insert into work_order_operation (work_order_id, seq, name, plan_qty,
                                                      standard_minutes, unit_price, process_id)
                    select ?, seq, p.name, ?, r.minutes_per_piece * ?, r.unit_price, p.id
                    from product_routing r join process p on p.id = r.process_id
                    where r.product_id = 1
                    """, id, qty, qty);
            workIds.add(id);
        }

        LocalDate start = LocalDate.now();
        Map<String, Object> result = schedulingService.apsSchedule(new SchedulingService.ApsRequest(start, 7));

        int scheduled = ((Number) result.get("scheduledCount")).intValue();
        assertTrue(scheduled >= 3, "至少排入 3 张工单，实际 " + scheduled);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> loads = (List<Map<String, Object>>) result.get("dailyLoad");
        assertFalse(loads.isEmpty(), "应返回每日负荷");
        for (Map<String, Object> row : loads) {
            double minutes = ((Number) row.get("minutes")).doubleValue();
            double capacity = ((Number) row.get("capacity")).doubleValue();
            assertTrue(minutes <= capacity + 0.001,
                    row.get("date") + " " + row.get("processName") + " 负荷 " + minutes + " 超过产能 " + capacity);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignments = (List<Map<String, Object>>) result.get("assignments");
        Map<String, List<Map<String, Object>>> byWork = new LinkedHashMap<>();
        for (Map<String, Object> row : assignments) {
            byWork.computeIfAbsent(String.valueOf(row.get("workNo")), key -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> rows : byWork.values()) {
            rows.sort((a, b) -> Integer.compare(((Number) a.get("seq")).intValue(),
                    ((Number) b.get("seq")).intValue()));
            for (int i = 1; i < rows.size(); i++) {
                String previousEnd = String.valueOf(rows.get(i - 1).get("endAt"));
                String currentStart = String.valueOf(rows.get(i).get("startAt"));
                assertTrue(currentStart.compareTo(previousEnd) >= 0,
                        "后道开始时间不应早于前道结束：" + currentStart + " < " + previousEnd);
            }
        }

        for (Long id : workIds) {
            String status = jdbc.queryForObject("select status from work_order where id = ?", String.class, id);
            assertEquals("SCHEDULED", status);
            BigDecimal totalMinutes = jdbc.queryForObject("""
                    select coalesce(sum(standard_minutes), 0) from work_order_operation where work_order_id = ?
                    """, BigDecimal.class, id);
            assertTrue(totalMinutes.signum() > 0);
        }
    }
}
