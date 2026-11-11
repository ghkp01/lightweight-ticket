package com.mes.report;

import com.mes.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产报表：产量、计件工资与订单交期预警。
 */
@Service
public class ReportService {

    private final JdbcTemplate jdbcTemplate;

    public ReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> production(String from, String to) {
        LocalDate start = parseDate(from, LocalDate.now().minusDays(6), "开始日期");
        LocalDate end = parseDate(to, LocalDate.now(), "结束日期");
        if (start.isAfter(end)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        Date startDate = Date.valueOf(start);
        Date endDate = Date.valueOf(end);

        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                select coalesce(sum(r.qty), 0) as "totalQty",
                       coalesce(sum(r.qualified_qty), 0) as "qualifiedQty",
                       coalesce(sum(r.defect_qty), 0) as "defectQty",
                       count(*) as "reports"
                from work_report r
                where r.reported_at::date between ? and ?
                """, startDate, endDate);

        BigDecimal totalQty = decimal(totals.get("totalQty"));
        BigDecimal qualifiedQty = decimal(totals.get("qualifiedQty"));
        BigDecimal defectQty = decimal(totals.get("defectQty"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalQty", totalQty);
        summary.put("qualifiedQty", qualifiedQty);
        summary.put("defectQty", defectQty);
        summary.put("defectRate", rate(defectQty, totalQty));
        summary.put("reports", totals.get("reports"));

        List<Map<String, Object>> daily = jdbcTemplate.queryForList("""
                select to_char(r.reported_at::date, 'YYYY-MM-DD') as "date",
                       sum(r.qty) as "qty",
                       sum(r.qualified_qty) as "qualifiedQty",
                       sum(r.defect_qty) as "defectQty"
                from work_report r
                where r.reported_at::date between ? and ?
                group by r.reported_at::date
                order by r.reported_at::date
                """, startDate, endDate);

        List<Map<String, Object>> byOperation = jdbcTemplate.queryForList("""
                select o.name as "name",
                       sum(r.qty) as "qty",
                       sum(r.qualified_qty) as "qualifiedQty",
                       sum(r.defect_qty) as "defectQty"
                from work_report r
                join rm_work_order_operation o on o.id = r.operation_id
                where r.reported_at::date between ? and ?
                group by o.name
                order by min(o.seq), o.name
                """, startDate, endDate);

        List<Map<String, Object>> byWorker = jdbcTemplate.queryForList("""
                select coalesce(nullif(trim(r.worker), ''), '未指定') as "worker",
                       sum(r.qty) as "qty",
                       sum(r.qualified_qty) as "qualifiedQty",
                       sum(r.defect_qty) as "defectQty"
                from work_report r
                where r.reported_at::date between ? and ?
                group by coalesce(nullif(trim(r.worker), ''), '未指定')
                order by sum(r.qty) desc, 1
                """, startDate, endDate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("daily", daily);
        result.put("byOperation", byOperation);
        result.put("byWorker", byWorker);
        return result;
    }

    public Map<String, Object> payroll(String month) {
        YearMonth period = parseMonth(month);
        Date start = Date.valueOf(period.atDay(1));
        Date end = Date.valueOf(period.plusMonths(1).atDay(1));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select coalesce(nullif(trim(r.worker), ''), '未指定') as "worker",
                       count(distinct r.work_order_id) as "workOrders",
                       coalesce(sum(r.qualified_qty), 0) as "qualifiedQty",
                       coalesce(round(sum(r.qualified_qty * coalesce(o.unit_price, 0)), 2), 0) as "wage"
                from work_report r
                join rm_work_order_operation o on o.id = r.operation_id
                where r.reported_at::date >= ? and r.reported_at::date < ?
                group by coalesce(nullif(trim(r.worker), ''), '未指定')
                order by 4 desc, 1
                """, start, end);

        BigDecimal totalWage = BigDecimal.ZERO;
        BigDecimal qualifiedQty = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            totalWage = totalWage.add(decimal(row.get("wage")));
            qualifiedQty = qualifiedQty.add(decimal(row.get("qualifiedQty")));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalWage", totalWage.setScale(2, RoundingMode.HALF_UP));
        summary.put("workers", rows.size());
        summary.put("qualifiedQty", qualifiedQty);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("rows", rows);
        return result;
    }

    public List<Map<String, Object>> delivery() {
        return jdbcTemplate.queryForList("""
                select so.order_no as "orderNo",
                       c.name as "customerName",
                       to_char(so.delivery_date, 'YYYY-MM-DD') as "deliveryDate",
                       so.status,
                       so.total_qty as "totalQty",
                       coalesce((select sum(i.shipped_qty) from rm_sales_order_item i
                                 where i.order_id = so.id), 0) as "shippedQty",
                       case when so.delivery_date is not null and so.delivery_date < current_date
                            then true else false end as "late",
                       case when so.delivery_date is not null and so.delivery_date < current_date
                            then (current_date - so.delivery_date) else 0 end as "lateDays"
                from rm_sales_order so
                join rm_customer c on c.id = so.customer_id
                where so.status not in ('COMPLETED', 'CANCELLED')
                order by case when so.delivery_date is not null and so.delivery_date < current_date
                              then 0 else 1 end,
                         so.delivery_date asc nulls last, so.id
                """);
    }

    private BigDecimal rate(BigDecimal defectQty, BigDecimal totalQty) {
        if (totalQty == null || totalQty.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return defectQty.multiply(BigDecimal.valueOf(100))
                .divide(totalQty, 2, RoundingMode.HALF_UP);
    }

    private LocalDate parseDate(String value, LocalDate fallback, String label) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BizException(1002, label + "格式应为 yyyy-MM-dd");
        }
    }

    private YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BizException(1002, "月份格式应为 yyyy-MM");
        }
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
