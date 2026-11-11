package com.mes.process;

import com.mes.process.api.ProcessUsagePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工序被引用情况的薄实现：只碰 {@code process}，不带任何依赖。
 *
 * <p>刻意与 {@link ProcessService} 分开：那个类要读工作中心（契约），
 * 工作中心又要问「有没有工序挂在我这儿」，直接互相注入会成构造环。</p>
 */
@Component
public class ProcessUsageReader implements ProcessUsagePort {

    private final JdbcTemplate jdbcTemplate;

    public ProcessUsageReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Long, ProcessUsage> usageByWorkCenter() {
        Map<Long, ProcessUsage> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select work_center_id as "workCenterId", count(*) as "processCount",
                       string_agg(name, '、' order by id) as "processNames"
                from process
                where work_center_id is not null
                group by work_center_id
                """)) {
            map.put(((Number) row.get("workCenterId")).longValue(),
                    new ProcessUsage(((Number) row.get("processCount")).intValue(),
                            row.get("processNames") == null ? null : String.valueOf(row.get("processNames"))));
        }
        return map;
    }

    @Override
    public Set<Long> processIdsOf(long workCenterId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "select id from process where work_center_id = ? order by id", Long.class, workCenterId));
    }

    @Override
    public boolean anyForWorkCenter(long workCenterId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from process where work_center_id = ? limit 1", Long.class, workCenterId);
        return !ids.isEmpty();
    }
}
