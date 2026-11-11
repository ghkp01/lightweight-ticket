package com.mes.quality;

import com.mes.quality.api.InspectionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检验单的只读入口：目前只有供应商绩效会按时间范围拉来料检验。
 *
 * <p>和 {@link DefectWriter} 一样单独抽一个薄实现，避免把「给别的模块读数」这种活
 * 塞进 {@link QualityService}（那个类已经背着检验建单、判定的全部规则）。</p>
 */
@Component
public class InspectionReader implements InspectionPort {

    private final JdbcTemplate jdbcTemplate;

    public InspectionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<IncomingInspection> incoming(LocalDate from, LocalDate to) {
        List<IncomingInspection> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, inspection_no, inbound_order_id, product_id, inspect_qty, defect_qty, result,
                       inspected_at::date as "inspectedDate",
                       to_char(inspected_at, 'YYYY-MM-DD HH24:MI') as "inspectedAt"
                from inspection
                where type = 'INCOMING' and inspected_at::date between ? and ?
                order by inspected_at desc
                """, from, to)) {
            rows.add(new IncomingInspection(
                    number(row.get("id")),
                    row.get("inspection_no") == null ? null : String.valueOf(row.get("inspection_no")),
                    row.get("inbound_order_id") == null ? null : number(row.get("inbound_order_id")),
                    number(row.get("product_id")),
                    decimal(row.get("inspect_qty")),
                    decimal(row.get("defect_qty")),
                    row.get("result") == null ? null : String.valueOf(row.get("result")),
                    row.get("inspectedDate") == null ? null : ((Date) row.get("inspectedDate")).toLocalDate(),
                    row.get("inspectedAt") == null ? null : String.valueOf(row.get("inspectedAt"))));
        }
        return rows;
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
