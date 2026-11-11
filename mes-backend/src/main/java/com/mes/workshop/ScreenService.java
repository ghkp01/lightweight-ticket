package com.mes.workshop;

import com.mes.device.api.OeePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车间大屏的数据源：生产 / 质量 / 异常三屏。
 *
 * <p>三块数据都是**跨域只读聚合**，所以一律读 {@code rm_*} 读模型视图
 * （见 {@code V64__read_model_views.sql}），不直接 join 别人的业务表；
 * 大屏本身没有任何写操作。</p>
 *
 * <p>接口刻意挂在 {@code /api/public/screen/**}：车间电视不该为了刷新一块看板去维持登录态，
 * 也不该因为某个人的令牌过期就黑屏。代价是这几屏对同一内网可见 —— 里面只有产量、合格率、
 * 安灯这类生产现场信息，没有成本、客户价、工资之类的经营数据，这个取舍是刻意的。</p>
 */
@Service
public class ScreenService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 大屏列表条数：一屏能看清的密度，再多就成翻页了 */
    private static final int ROWS = 12;

    private final JdbcTemplate jdbcTemplate;
    private final OeePort oeePort;

    public ScreenService(JdbcTemplate jdbcTemplate, OeePort oeePort) {
        this.jdbcTemplate = jdbcTemplate;
        this.oeePort = oeePort;
    }

    /** 生产屏：在制与产量 KPI + 工作中心负荷 + 在制工单进度 */
    public Map<String, Object> production() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "生产实时看板");
        result.put("updatedAt", LocalDateTime.now().format(STAMP));
        Map<String, Object> kpi = jdbcTemplate.queryForMap("""
                select
                  (select count(*) from rm_work_order where status = 'IN_PROGRESS') as "inProgress",
                  (select count(*) from rm_work_order where status = 'SCHEDULED') as "scheduled",
                  (select count(*) from rm_work_order w
                    join rm_sales_order so on so.id = w.sales_order_id
                    where so.delivery_date = current_date
                      and w.status not in ('FINISHED', 'CLOSED', 'CANCELLED')) as "dueToday",
                  (select coalesce(sum(qty), 0) from work_report
                    where reported_at::date = current_date) as "todayOutput",
                  (select coalesce(sum(qualified_qty), 0) from work_report
                    where reported_at::date = current_date) as "todayQualified",
                  (select coalesce(sum(plan_qty), 0) from rm_work_order
                    where schedule_date = current_date) as "todayPlanQty",
                  (select count(*) from rm_work_order w
                    where w.status in ('RELEASED', 'IN_PROGRESS')
                      and exists (select 1 from rm_work_order_bom b
                                   where b.work_order_id = w.id
                                     and b.plan_qty > b.issued_qty
                                     and b.source <> 'SUBSTITUTE')) as "shortageOrders",
                  -- 外协中也算现场状态：几道工序在外面做、几道已经过了约定期还没回来
                  (select count(*) from rm_work_order_operation
                    where outsource_status = 'SENT') as "outsourcing",
                  (select count(*) from rm_work_order_operation
                    where outsource_status = 'SENT'
                      and outsource_expected_at is not null
                      and outsource_expected_at < current_date) as "outsourcingOverdue"
                """);
        Map<String, Object> kpiOut = new LinkedHashMap<>(kpi);
        BigDecimal planQty = decimal(kpi.get("todayPlanQty"));
        BigDecimal output = decimal(kpi.get("todayOutput"));
        // 达成率口径：今日排产工单的计划量当作当日目标，报工量当作实际产出
        kpiOut.put("attainment", planQty.signum() > 0
                ? output.divide(planQty, 4, RoundingMode.HALF_UP) : null);
        BigDecimal qualified = decimal(kpi.get("todayQualified"));
        kpiOut.put("yieldRate", output.signum() > 0
                ? qualified.divide(output, 4, RoundingMode.HALF_UP) : null);
        result.put("kpi", kpiOut);
        result.put("workCenters", workCenterLoad());
        result.put("orders", activeOrders());
        result.put("outsourcingOrders", outsourcingOrders());
        return result;
    }

    /**
     * 外协在途：已经发出去、还没收回来的工序。车间最怕的是「件在外面、没人知道什么时候回来」，
     * 所以这张单列出来，按约定回厂日期排序，超期翻红。
     */
    private List<Map<String, Object>> outsourcingOrders() {
        return jdbcTemplate.queryForList("""
                select o.order_no as "orderNo", w.work_no as "workNo", p.name as "productName",
                       o.operation_seq as "operationSeq", o.qty as "qty",
                       o.received_qty as "receivedQty", s.name as "supplierName",
                       to_char(o.expected_at, 'MM-DD') as "expectedAt",
                       (o.expected_at is not null and o.expected_at < current_date) as "overdue",
                       (current_date - o.expected_at::date) as "overdueDays"
                from rm_outsource_order o
                left join rm_work_order w on w.id = o.work_order_id
                left join rm_product p on p.id = w.product_id
                left join rm_supplier s on s.id = o.supplier_id
                where o.status in ('SENT', 'PARTIAL')
                order by (o.expected_at is not null and o.expected_at < current_date) desc,
                         o.expected_at nulls last, o.id
                limit 8
                """);
    }

    /** 质量屏：检验合格率 + 今日不良 TOP + 最近检验 */
    public Map<String, Object> quality() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "质量实时看板");
        result.put("updatedAt", LocalDateTime.now().format(STAMP));
        Map<String, Object> kpi = jdbcTemplate.queryForMap("""
                select
                  (select count(*) from rm_inspection where inspected_at::date = current_date) as "todayInspections",
                  (select coalesce(sum(inspect_qty), 0) from rm_inspection
                    where inspected_at::date = current_date) as "todayInspectQty",
                  (select coalesce(sum(defect_qty), 0) from rm_inspection
                    where inspected_at::date = current_date) as "todayDefectQty",
                  (select count(*) from defect_record where status = 'PENDING') as "openDefects",
                  (select count(*) from rm_work_order where status = 'REWORK') as "reworkOrders",
                  (select count(*) from rm_work_order where status = 'FINISHED'
                     and updated_at::date = current_date) as "finishedToday"
                """);
        Map<String, Object> kpiOut = new LinkedHashMap<>(kpi);
        BigDecimal inspectQty = decimal(kpi.get("todayInspectQty"));
        BigDecimal defectQty = decimal(kpi.get("todayDefectQty"));
        kpiOut.put("passRate", inspectQty.signum() > 0
                ? inspectQty.subtract(defectQty).divide(inspectQty, 4, RoundingMode.HALF_UP) : null);
        result.put("kpi", kpiOut);
        result.put("defectTop", jdbcTemplate.queryForList("""
                select coalesce(nullif(trim(reason), ''), '未填原因') as "reason",
                       sum(qty) as "qty"
                from defect_record
                where created_at::date = current_date
                group by 1
                order by 2 desc
                limit 5
                """));
        result.put("inspections", jdbcTemplate.queryForList("""
                select i.inspection_no as "inspectionNo", i.type, i.result, i.inspector,
                       i.inspect_qty as "inspectQty", i.defect_qty as "defectQty",
                       to_char(i.inspected_at, 'HH24:MI') as "inspectedAt",
                       p.name as "productName"
                from rm_inspection i
                left join rm_product p on p.id = i.product_id
                where i.inspected_at is not null
                order by i.inspected_at desc
                limit ?
                """, ROWS));
        return result;
    }

    /** 异常屏：安灯数量与响应时长 + 未关闭清单 */
    public Map<String, Object> andon() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "异常安灯看板");
        result.put("updatedAt", LocalDateTime.now().format(STAMP));
        result.put("kpi", jdbcTemplate.queryForMap("""
                select
                  (select count(*) from rm_andon_call where status = 'OPEN') as "open",
                  (select count(*) from rm_andon_call where status = 'ACKED') as "acked",
                  (select count(*) from rm_andon_call where resolved_at::date = current_date) as "resolvedToday",
                  (select count(*) from rm_andon_call where created_at::date = current_date) as "todayTotal",
                  (select coalesce(round(avg(extract(epoch from (acked_at - created_at)) / 60)), 0)
                     from rm_andon_call where acked_at::date = current_date) as "avgAckMinutes"
                """));
        result.put("calls", jdbcTemplate.queryForList("""
                select a.call_no as "callNo", a.type, a.work_order_no as "workOrderNo",
                       a.operation_name as "operationName", a.description, a.status, a.caller,
                       to_char(a.created_at, 'HH24:MI') as "createdAt",
                       floor(extract(epoch from (coalesce(a.resolved_at, now()) - a.created_at)) / 60)::int
                           as "elapsedMinutes"
                from rm_andon_call a
                where a.status <> 'RESOLVED' or a.resolved_at::date = current_date
                order by case a.status when 'OPEN' then 0 when 'ACKED' then 1 else 2 end,
                         a.created_at desc
                limit ?
                """, ROWS));
        return result;
    }

    /**
     * 设备屏：今日 OEE（可用率 × 性能 × 质量）。
     *
     * <p>算法在设备模块（{@link OeePort}），这里只负责排布一屏要展示的字段：
     * 整体 OEE + 三个分项 + 分中心明细。百分比口径是 0~100 的数（不是 0~1），
     * 前端用「原样加 %」的格式化，别再乘 100。</p>
     */
    public Map<String, Object> oee() {
        OeePort.OeeBoard board = oeePort.recent(1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "设备效率看板");
        result.put("updatedAt", LocalDateTime.now().format(STAMP));
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("oee", board.oee());
        kpi.put("availability", board.availability());
        kpi.put("performance", board.performance());
        kpi.put("quality", board.quality());
        kpi.put("reportQty", board.reportQty());
        kpi.put("centers", board.centers().size());
        kpi.put("days", board.days());
        result.put("kpi", kpi);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OeePort.CenterOee center : board.centers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", center.code());
            row.put("name", center.name());
            row.put("type", center.type());
            row.put("oee", center.oee());
            row.put("availability", center.availability());
            row.put("performance", center.performance());
            row.put("quality", center.quality());
            row.put("downtimeMinutes", center.downtimeMinutes());
            row.put("plannedMinutes", center.plannedMinutes());
            row.put("reportQty", center.reportQty());
            rows.add(row);
        }
        result.put("centers", rows);
        return result;
    }

    /** 工作中心今日负荷：排产工序的标准工时 ÷ 日产能 */
    private List<Map<String, Object>> workCenterLoad() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select c.id, c.name, c.type,
                       c.daily_capacity_minutes as "dailyCapacityMinutes",
                       coalesce((select sum(o.standard_minutes)
                                 from rm_work_order_operation o
                                 join rm_process p on p.id = o.process_id
                                 join rm_work_order w on w.id = o.work_order_id
                                 where p.work_center_id = c.id
                                   and w.schedule_date = current_date), 0) as "plannedMinutes"
                from rm_work_center c
                where c.enabled
                order by c.id
                limit 8
                """);
        for (Map<String, Object> row : rows) {
            BigDecimal capacity = decimal(row.get("dailyCapacityMinutes"));
            BigDecimal planned = decimal(row.get("plannedMinutes"));
            row.put("loadRate", capacity.signum() > 0
                    ? planned.divide(capacity, 4, RoundingMode.HALF_UP) : null);
        }
        return rows;
    }

    /**
     * 在制工单进度：返修排最前，其次在制，再其次已下达/已排产。
     *
     * <p>进度按**工序**算（已完成工序数 ÷ 工序总数），不用 {@code done_qty ÷ plan_qty}：
     * 工单的 {@code done_qty} 只在整张工单完工时才写，在制期间恒为 0，
     * 拿它算进度会让整块看板永远显示 0%（这个坑踩过一次）。</p>
     */
    private List<Map<String, Object>> activeOrders() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select w.work_no as "workNo", w.plan_qty as "planQty",
                       w.status, to_char(w.schedule_date, 'MM-DD') as "scheduleDate",
                       p.code as "productCode", p.name as "productName",
                       (select count(*) from rm_work_order_operation o
                         where o.work_order_id = w.id) as "opTotal",
                       (select count(*) from rm_work_order_operation o
                         where o.work_order_id = w.id and o.done_qty >= o.plan_qty) as "opDone",
                       (select o.name from rm_work_order_operation o
                         where o.work_order_id = w.id and o.done_qty < o.plan_qty
                         order by o.seq limit 1) as "currentOperation",
                       (select count(*) from rm_work_order_operation o
                         where o.work_order_id = w.id and o.outsource_status = 'SENT')
                           as "outsourcingOps"
                from rm_work_order w
                left join rm_product p on p.id = w.product_id
                where w.status in ('IN_PROGRESS', 'REWORK', 'RELEASED', 'SCHEDULED')
                order by case w.status
                           when 'REWORK' then 0 when 'IN_PROGRESS' then 1
                           when 'RELEASED' then 2 else 3 end,
                         w.schedule_date nulls last, w.id
                limit 14
                """);
        for (Map<String, Object> row : rows) {
            BigDecimal total = decimal(row.get("opTotal"));
            BigDecimal done = decimal(row.get("opDone"));
            row.put("progress", total.signum() > 0
                    ? done.divide(total, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        }
        return rows;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
