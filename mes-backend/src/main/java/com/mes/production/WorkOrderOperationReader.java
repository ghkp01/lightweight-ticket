package com.mes.production;

import com.mes.production.api.WorkOrderOperationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单工序引用统计的薄实现：只碰 {@code work_order_operation}，不带任何依赖。
 *
 * <p>刻意不塞进 {@link ProductionService}：那个类依赖工序（工艺路线展开）与组织（产能），
 * 而工序/组织反过来要问「有没有被工单用过」，直接互相注入会成构造环。</p>
 */
@Component
public class WorkOrderOperationReader implements WorkOrderOperationPort {

    private final JdbcTemplate jdbcTemplate;

    public WorkOrderOperationReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Long, Integer> operationCountByProcess() {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select process_id as "processId", count(*) as "operationCount"
                from work_order_operation
                where process_id is not null
                group by process_id
                """)) {
            map.put(((Number) row.get("processId")).longValue(),
                    ((Number) row.get("operationCount")).intValue());
        }
        return map;
    }

    @Override
    public boolean anyOperationForProcesses(Collection<Long> processIds) {
        List<Long> ids = processIds == null ? List.of()
                : processIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return false;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        List<Long> found = jdbcTemplate.queryForList(
                "select id from work_order_operation where process_id in (" + marks + ") limit 1",
                Long.class, ids.toArray());
        return !found.isEmpty();
    }

    @Override
    public void renameOperationsOfProcess(long processId, String name) {
        jdbcTemplate.update("update work_order_operation set name = ? where process_id = ?", name, processId);
    }
}
