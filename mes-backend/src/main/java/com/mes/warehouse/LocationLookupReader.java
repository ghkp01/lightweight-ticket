package com.mes.warehouse;

import com.mes.warehouse.api.WarehouseLocationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库位只读视图的薄实现：只碰 {@code warehouse_location}，不带任何依赖。
 *
 * <p>给库存模块补批次上的库位编码/名称用。刻意与 {@link LocationService} 分开，
 * 避免「仓储 → 库存 → 仓储」的构造环。</p>
 */
@Component
public class LocationLookupReader implements WarehouseLocationPort {

    private final JdbcTemplate jdbcTemplate;

    public LocationLookupReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Long, LocationRef> locations(Collection<Long> locationIds) {
        Map<Long, LocationRef> map = new LinkedHashMap<>();
        List<Long> ids = locationIds == null ? List.of()
                : locationIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, warehouse_id, code, name from warehouse_location where id in (" + marks + ")",
                ids.toArray())) {
            long id = ((Number) row.get("id")).longValue();
            map.put(id, new LocationRef(id, ((Number) row.get("warehouse_id")).longValue(),
                    row.get("code") == null ? null : String.valueOf(row.get("code")),
                    row.get("name") == null ? null : String.valueOf(row.get("name"))));
        }
        return map;
    }
}
