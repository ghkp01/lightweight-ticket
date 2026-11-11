package com.mes.workshop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkshopService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public WorkshopService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> board(String keyword, Long orgId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kpi", kpi());
        result.put("operations", operations(orgId));
        result.put("locations", locations(keyword, orgId));
        result.put("orgName", orgName(orgId));
        result.put("updatedAt", LocalDateTime.now().format(TIME_FORMAT));
        return result;
    }

    private String orgName(Long orgId) {
        if (orgId == null) {
            return "全厂";
        }
        List<String> names = jdbcTemplate.queryForList(
                "select name from sys_org where id = ?", String.class, orgId);
        return names.isEmpty() ? "全厂" : names.get(0);
    }

    private Map<String, Object> kpi() {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("inProgress", count("select count(*) from rm_work_order where status = 'IN_PROGRESS'"));
        kpi.put("scheduled", count("select count(*) from rm_work_order where status = 'SCHEDULED'"));
        kpi.put("dueToday", count("""
                select count(*) from rm_work_order w
                join rm_sales_order so on so.id = w.sales_order_id
                where so.delivery_date = current_date
                  and w.status not in ('FINISHED', 'CLOSED', 'CANCELLED')
                """));
        kpi.put("wipQty", amount("""
                select coalesce(sum(greatest(plan_qty - done_qty, 0)), 0)
                from rm_work_order where status = 'IN_PROGRESS'
                """));
        kpi.put("openAndon", count("select count(*) from rm_andon_call where status = 'OPEN'"));
        // 缺料预警：在制/待产的工单里，还有未领齐的行数大于 0 的单子有多少张
        kpi.put("shortageOrders", count("""
                select count(*) from rm_work_order w
                where w.status in ('SCHEDULED', 'RELEASED', 'IN_PROGRESS')
                  and exists (select 1 from rm_work_order_bom b
                              where b.work_order_id = w.id and b.plan_qty > b.issued_qty
                                and b.source <> 'SUBSTITUTE')
                """));
        kpi.put("todayOutput", amount("""
                select coalesce(sum(qualified_qty), 0) from work_report
                where reported_at::date = current_date
                """));
        return kpi;
    }

    private List<Map<String, Object>> operations(Long orgId) {
        StringBuilder sql = new StringBuilder("""
                select o.name as "name",
                       count(*) filter (where o.status = 'PENDING') as "pending",
                       count(*) filter (where o.status = 'IN_PROGRESS') as "inProgress",
                       count(*) filter (where o.status = 'DONE') as "done"
                from rm_work_order_operation o
                join rm_work_order w on w.id = o.work_order_id
                left join rm_process p on p.id = o.process_id
                left join rm_work_center wc on wc.id = p.work_center_id
                left join sys_org wo on wo.id = wc.org_id
                where w.status in ('SCHEDULED', 'RELEASED', 'IN_PROGRESS', 'REWORK')
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (orgId != null) {
            sql.append(" and wo.path like (select path || '%' from sys_org where id = ?) ");
            args.add(orgId);
        }
        sql.append(" group by o.name order by min(o.seq), o.name ");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> locations(String keyword, Long orgId) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder sql = new StringBuilder("""
                select w.id, w.work_no as "workNo", p.code as "productCode", p.name as "productName",
                       w.plan_qty as "planQty", w.done_qty as "doneQty", w.priority,
                       w.schedule_date as "scheduleDate", w.status,
                       op.name as "currentOperation", op.seq as "currentSeq",
                       op.assigned_worker as "assignedWorker", rp.last_report_at as "lastReportAt",
                       op.work_center as "workCenterName",
                       coalesce((select sum(b.plan_qty) from rm_work_order_bom b
                                 where b.work_order_id = w.id and b.source <> 'SUBSTITUTE'), 0)
                           as "materialNeedQty",
                       coalesce((select sum(b.plan_qty - b.issued_qty) from rm_work_order_bom b
                                 where b.work_order_id = w.id and b.plan_qty > b.issued_qty
                                   and b.source <> 'SUBSTITUTE'), 0)
                           as "materialPendingQty",
                       (select count(*) from rm_work_order_bom b
                         where b.work_order_id = w.id and b.plan_qty > b.issued_qty
                           and b.source <> 'SUBSTITUTE') as "shortageLines"
                from rm_work_order w
                join rm_product p on p.id = w.product_id
                left join lateral (
                    select o.name, o.seq, o.assigned_worker, wc.name as work_center
                    from rm_work_order_operation o
                    left join rm_process pr on pr.id = o.process_id
                    left join rm_work_center wc on wc.id = pr.work_center_id
                    where o.work_order_id = w.id
                    order by case when o.done_qty < o.plan_qty then 0 else 1 end, o.seq
                    limit 1
                ) op on true
                left join lateral (
                    select to_char(max(r.reported_at), 'YYYY-MM-DD HH24:MI') as last_report_at
                    from work_report r
                    where r.work_order_id = w.id
                ) rp on true
                where w.status in ('SCHEDULED', 'RELEASED', 'IN_PROGRESS', 'REWORK')
                  and (w.work_no ilike ? or p.name ilike ?)
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(like);
        args.add(like);
        if (orgId != null) {
            sql.append("""
                      and exists (
                          select 1 from rm_work_order_operation o2
                          join rm_process p2 on p2.id = o2.process_id
                          join rm_work_center wc2 on wc2.id = p2.work_center_id
                          join sys_org wo2 on wo2.id = wc2.org_id
                          where o2.work_order_id = w.id
                            and wo2.path like (select path || '%' from sys_org where id = ?)
                      )
                    """);
            args.add(orgId);
        }
        sql.append("""
                order by case w.priority when 'RUSH' then 0 when 'URGENT' then 1 else 2 end,
                         w.schedule_date asc nulls last, w.schedule_seq asc nulls last, w.id
                """);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private BigDecimal amount(String sql) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }
}
