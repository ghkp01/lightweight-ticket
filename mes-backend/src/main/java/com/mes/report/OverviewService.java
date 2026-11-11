package com.mes.report;

import com.mes.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 菜单分组工作台：每个业务分组一屏指标 + 预警明细。
 *
 * <p>统一口径：</p>
 * <ul>
 *   <li>数量类指标按「月份」统计（created_at / order_date / inspected_at 落在该月）；</li>
 *   <li>预警类指标按「预警天数」看未来 N 天到期或已经逾期；</li>
 *   <li>所有查询只读 rm_* 读模型视图，不直接碰其它模块的业务表。</li>
 * </ul>
 */
@Service
public class OverviewService {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final Map<String, String> TITLES = new LinkedHashMap<>();

    static {
        TITLES.put("sales", "销售与交付工作台");
        TITLES.put("planning", "计划与排产工作台");
        TITLES.put("production", "生产执行工作台");
        TITLES.put("purchase", "采购与应付工作台");
        TITLES.put("warehouse", "仓储管理工作台");
        TITLES.put("quality", "质量与设备工作台");
        TITLES.put("report", "报表分析工作台");
        TITLES.put("base", "基础数据工作台");
        TITLES.put("system", "系统管理工作台");
    }

    private final JdbcTemplate jdbcTemplate;
    /** 分组工作台聚合结果短时缓存：同一分组+月份+预警天数只查一次；测试环境配 0 关闭 */
    private final long cacheMs;
    private final Map<String, CachedOverview> cache = new ConcurrentHashMap<>();

    private record CachedOverview(long expiresAt, Map<String, Object> data) {
    }

    public OverviewService(JdbcTemplate jdbcTemplate,
                           @Value("${mes.overview.cache-ms:30000}") long cacheMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheMs = cacheMs;
    }

    public Map<String, Object> overview(String category, String month, int warningDays) {
        if (cacheMs <= 0) {
            return computeOverview(category, month, warningDays);
        }
        String key = String.valueOf(category) + "|" + String.valueOf(month) + "|" + warningDays;
        long now = System.currentTimeMillis();
        CachedOverview cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.data();
        }
        synchronized (cache) {
            cached = cache.get(key);
            if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
                return cached.data();
            }
            Map<String, Object> data = computeOverview(category, month, warningDays);
            if (cache.size() > 64) {
                cache.clear();
            }
            cache.put(key, new CachedOverview(System.currentTimeMillis() + cacheMs, data));
            return data;
        }
    }

    private Map<String, Object> computeOverview(String category, String month, int warningDays) {
        String key = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        String title = TITLES.get(key);
        if (title == null) {
            throw new BizException(1002, "未知的工作台分类：" + category);
        }
        int days = Math.max(1, Math.min(warningDays, 365));
        YearMonth yearMonth = parseMonth(month);
        LocalDate from = yearMonth.atDay(1);
        LocalDate to = yearMonth.atEndOfMonth();
        LocalDate today = LocalDate.now();
        LocalDate until = today.plusDays(days);

        List<Map<String, Object>> kpis = new ArrayList<>();
        List<Map<String, Object>> alerts = new ArrayList<>();
        switch (key) {
            case "sales" -> sales(kpis, alerts, from, to, today, until, days);
            case "planning" -> planning(kpis, alerts, from, to, today, until, days);
            case "production" -> production(kpis, alerts, from, to, today, days);
            case "purchase" -> purchase(kpis, alerts, from, to, today, until, days);
            case "warehouse" -> warehouse(kpis, alerts);
            case "quality" -> quality(kpis, alerts, from, to, today, days);
            case "report" -> report(kpis, alerts, from, to);
            case "base" -> base(kpis, alerts);
            case "system" -> system(kpis, alerts);
            default -> throw new BizException(1002, "未知的工作台分类：" + category);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("category", key);
        result.put("title", title);
        result.put("month", yearMonth.format(MONTH_FORMAT));
        result.put("warningDays", days);
        result.put("today", today.toString());
        result.put("kpis", kpis);
        result.put("alerts", alerts);
        return result;
    }

    // ------------------------------------------------------------------ 销售与交付

    private void sales(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts,
                       LocalDate from, LocalDate to, LocalDate today, LocalDate until, int days) {
        BigDecimal monthSales = amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where created_at::date between ? and ? and status <> 'CANCELLED'
                  and currency = 'CNY'
                """, from, to);
        long monthOrders = count("""
                select count(*) from rm_sales_order
                where created_at::date between ? and ? and status <> 'CANCELLED'
                """, from, to);
        long active = count("select count(*) from rm_sales_order where status not in ('COMPLETED', 'CANCELLED')");
        long pendingShip = count("select count(*) from rm_sales_order where status = 'READY_TO_SHIP'");
        long dueSoon = count("""
                select count(*) from rm_sales_order
                where status not in ('COMPLETED', 'CANCELLED') and delivery_date between ? and ?
                """, today, until);
        long overdue = count("""
                select count(*) from rm_sales_order
                where status not in ('COMPLETED', 'CANCELLED') and delivery_date < ?
                """, today);
        kpis.add(kpi("monthSales", "本月销售额（人民币）", monthSales, "元", "", "按订单创建时间，仅统计 CNY 订单"));
        kpis.add(kpi("monthOrders", "本月订单数", monthOrders, "单", "", "不含已取消"));
        kpis.add(kpi("activeOrders", "执行中订单", active, "单", "", "未完成且未取消"));
        kpis.add(kpi("pendingShip", "待发货", pendingShip, "单", pendingShip > 0 ? "warn" : "ok", ""));
        kpis.add(kpi("dueSoon", days + " 天内到期", dueSoon, "单", dueSoon > 0 ? "warn" : "ok", "按订单交期"));
        kpis.add(kpi("overdue", "已逾期", overdue, "单", overdue > 0 ? "alert" : "ok", "交期早于今天"));

        List<Map<String, Object>> overdueRows = rows("""
                select so.order_no as "no", c.name as "name",
                       to_char(so.delivery_date, 'YYYY-MM-DD') as "date",
                       ('¥' || coalesce(so.total_amount, 0)::text) as "note", so.status as "status"
                from rm_sales_order so left join rm_customer c on c.id = so.customer_id
                where so.status not in ('COMPLETED', 'CANCELLED') and so.delivery_date < ?
                order by so.delivery_date limit 20
                """, today);
        addAlert(alerts, "salesOverdue", "逾期未交付订单", "danger",
                "交期已过但还没有完成交付，优先安排生产或与客户改期", overdueRows);

        List<Map<String, Object>> dueSoonRows = rows("""
                select so.order_no as "no", c.name as "name",
                       to_char(so.delivery_date, 'YYYY-MM-DD') as "date",
                       ('¥' || coalesce(so.total_amount, 0)::text) as "note", so.status as "status"
                from rm_sales_order so left join rm_customer c on c.id = so.customer_id
                where so.status not in ('COMPLETED', 'CANCELLED') and so.delivery_date between ? and ?
                order by so.delivery_date limit 20
                """, today, until);
        addAlert(alerts, "salesDueSoon", days + " 天内到期订单", "warning",
                "交期临近，确认物料与排产进度", dueSoonRows);
    }

    // ------------------------------------------------------------------ 计划与排产

    private void planning(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts,
                          LocalDate from, LocalDate to, LocalDate today, LocalDate until, int days) {
        long monthScheduled = count("""
                select count(*) from rm_work_order where schedule_date between ? and ?
                """, from, to);
        long todayScheduled = count("select count(*) from rm_work_order where schedule_date = ?", today);
        long unscheduled = count("select count(*) from rm_work_order where status = 'DRAFT' and schedule_date is null");
        long released = count("select count(*) from rm_work_order where status = 'RELEASED'");
        long late = count("""
                select count(*) from rm_work_order w
                join rm_sales_order so on so.id = w.sales_order_id
                where w.status not in ('FINISHED', 'CLOSED', 'CANCELLED')
                  and so.delivery_date is not null and so.delivery_date < ?
                """, today);
        kpis.add(kpi("monthScheduled", "本月排产工单", monthScheduled, "张", "", "按排产日期"));
        kpis.add(kpi("todayScheduled", "今日排产", todayScheduled, "张", "", "今日计划的工单"));
        kpis.add(kpi("unscheduled", "未排产草稿", unscheduled, "张", unscheduled > 0 ? "warn" : "ok", "草稿且没有排产日期"));
        kpis.add(kpi("released", "已下达待开工", released, "张", "", "已下达但未开工"));
        kpis.add(kpi("late", "逾期在制", late, "张", late > 0 ? "alert" : "ok", "已过订单交期"));

        List<Map<String, Object>> unscheduledRows = rows("""
                select w.work_no as "no", p.name as "name",
                       to_char(w.created_at, 'YYYY-MM-DD') as "date",
                       ('计划 ' || coalesce(w.plan_qty, 0)::text) as "note", w.status as "status"
                from rm_work_order w left join rm_product p on p.id = w.product_id
                where w.status = 'DRAFT' and w.schedule_date is null
                order by w.created_at limit 20
                """);
        addAlert(alerts, "planningUnscheduled", "未排产草稿工单", "warning",
                "草稿工单还没有排产日期，不排下去会拖到交期", unscheduledRows);

        List<Map<String, Object>> lateRows = rows("""
                select w.work_no as "no", p.name as "name",
                       to_char(so.delivery_date, 'YYYY-MM-DD') as "date",
                       ('计划 ' || coalesce(w.plan_qty, 0)::text) as "note", w.status as "status"
                from rm_work_order w
                join rm_sales_order so on so.id = w.sales_order_id
                left join rm_product p on p.id = w.product_id
                where w.status not in ('FINISHED', 'CLOSED', 'CANCELLED')
                  and so.delivery_date is not null and so.delivery_date < ?
                order by so.delivery_date limit 20
                """, today);
        addAlert(alerts, "planningLate", "逾期在制工单", "danger",
                "订单交期已过，仍在生产，需要重新排优先级", lateRows);
    }

    // ------------------------------------------------------------------ 生产执行

    private void production(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts,
                            LocalDate from, LocalDate to, LocalDate today, int days) {
        long inProgress = count("select count(*) from rm_work_order where status = 'IN_PROGRESS'");
        long released = count("select count(*) from rm_work_order where status = 'RELEASED'");
        BigDecimal monthOutput = amount("""
                select coalesce(sum(done_qty), 0) from rm_work_order where schedule_date between ? and ?
                """, from, to);
        long shortage = count("""
                select count(distinct b.work_order_id) from rm_work_order_bom b
                join rm_work_order w on w.id = b.work_order_id
                where w.status in ('RELEASED', 'IN_PROGRESS') and b.source <> 'SUBSTITUTE'
                  and b.plan_qty > b.issued_qty
                """);
        long overdue = count("""
                select count(*) from rm_work_order w
                join rm_sales_order so on so.id = w.sales_order_id
                where w.status not in ('FINISHED', 'CLOSED', 'CANCELLED')
                  and so.delivery_date is not null and so.delivery_date < ?
                """, today);
        long outsourcing = count("select count(*) from rm_work_order_operation where outsource_status = 'SENT'");
        long outsourceOverdue = count("""
                select count(*) from rm_work_order_operation
                where outsource_status = 'SENT' and outsource_expected_at is not null
                  and outsource_expected_at < ?
                """, today);
        kpis.add(kpi("inProgress", "生产中", inProgress, "张", "", "已开工未完工"));
        kpis.add(kpi("released", "已下达待开工", released, "张", "", ""));
        kpis.add(kpi("monthOutput", "本月产出", monthOutput, "件", "", "按排产日期汇总完成量"));
        kpis.add(kpi("shortage", "缺料工单", shortage, "张", shortage > 0 ? "warn" : "ok", "计划量大于已领量"));
        kpis.add(kpi("outsourcing", "外协在途", outsourcing, "道", outsourceOverdue > 0 ? "warn" : "", ""));
        kpis.add(kpi("overdue", "逾期在制", overdue, "张", overdue > 0 ? "alert" : "ok", ""));

        List<Map<String, Object>> shortageRows = rows("""
                select w.work_no as "no", p.name as "name",
                       to_char(w.schedule_date, 'YYYY-MM-DD') as "date",
                       ('缺 ' || sum(b.plan_qty - b.issued_qty)::text) as "note", w.status as "status"
                from rm_work_order w
                join rm_work_order_bom b on b.work_order_id = w.id
                left join rm_product p on p.id = w.product_id
                where w.status in ('RELEASED', 'IN_PROGRESS') and b.source <> 'SUBSTITUTE'
                  and b.plan_qty > b.issued_qty
                group by w.id, w.work_no, p.name, w.schedule_date, w.status
                order by w.schedule_date limit 20
                """);
        addAlert(alerts, "productionShortage", "缺料工单", "warning",
                "物料没领齐，开工后会被卡住，先催仓库", shortageRows);

        List<Map<String, Object>> outsourceRows = rows("""
                select w.work_no as "no", p.name as "name",
                       to_char(o.outsource_expected_at, 'YYYY-MM-DD') as "date",
                       (coalesce(o.name, '') || ' 第' || o.seq || ' 道') as "note", o.outsource_status as "status"
                from rm_work_order_operation o
                join rm_work_order w on w.id = o.work_order_id
                left join rm_product p on p.id = w.product_id
                where o.outsource_status = 'SENT' and o.outsource_expected_at is not null
                  and o.outsource_expected_at < ?
                order by o.outsource_expected_at limit 20
                """, today);
        addAlert(alerts, "productionOutsource", "外协超期未回", "danger",
                "已过约定回厂日期，联系外协厂确认进度", outsourceRows);

        List<Map<String, Object>> overdueRows = rows("""
                select w.work_no as "no", p.name as "name",
                       to_char(so.delivery_date, 'YYYY-MM-DD') as "date",
                       ('计划 ' || coalesce(w.plan_qty, 0)::text) as "note", w.status as "status"
                from rm_work_order w
                join rm_sales_order so on so.id = w.sales_order_id
                left join rm_product p on p.id = w.product_id
                where w.status not in ('FINISHED', 'CLOSED', 'CANCELLED')
                  and so.delivery_date is not null and so.delivery_date < ?
                order by so.delivery_date limit 20
                """, today);
        addAlert(alerts, "productionOverdue", "逾期在制工单", "danger",
                "订单交期已过仍在生产，需要重排或与销售协同", overdueRows);
    }

    // ------------------------------------------------------------------ 采购与应付

    private void purchase(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts,
                          LocalDate from, LocalDate to, LocalDate today, LocalDate until, int days) {
        BigDecimal monthAmount = amount("""
                select coalesce(sum(total_amount), 0) from rm_purchase_order
                where order_date between ? and ? and status <> 'CANCELLED'
                """, from, to);
        long inTransit = count("select count(*) from rm_purchase_order where status in ('CONFIRMED', 'PARTIAL')");
        long dueSoon = count("""
                select count(*) from rm_purchase_order
                where status in ('CONFIRMED', 'PARTIAL') and expected_date between ? and ?
                """, today, until);
        long overdue = count("""
                select count(*) from rm_purchase_order
                where status in ('CONFIRMED', 'PARTIAL') and expected_date < ?
                """, today);
        long pendingRequisition = count("select count(*) from rm_purchase_requisition where status = 'SUBMITTED'");
        kpis.add(kpi("monthAmount", "本月采购金额", monthAmount, "元", "", "按下单日期"));
        kpis.add(kpi("inTransit", "在途采购单", inTransit, "单", "", "已确认未收齐"));
        kpis.add(kpi("dueSoon", days + " 天内到货", dueSoon, "单", dueSoon > 0 ? "warn" : "ok", "按预计到货日"));
        kpis.add(kpi("overdue", "逾期未到货", overdue, "单", overdue > 0 ? "alert" : "ok", "预计到货日已过"));
        kpis.add(kpi("pendingRequisition", "待审批申请", pendingRequisition, "单", pendingRequisition > 0 ? "warn" : "ok", "已提交未审批"));

        List<Map<String, Object>> overdueRows = rows("""
                select o.order_no as "no", s.name as "name",
                       to_char(o.expected_date, 'YYYY-MM-DD') as "date",
                       ('¥' || coalesce(o.total_amount, 0)::text) as "note", o.status as "status"
                from rm_purchase_order o left join rm_supplier s on s.id = o.supplier_id
                where o.status in ('CONFIRMED', 'PARTIAL') and o.expected_date < ?
                order by o.expected_date limit 20
                """, today);
        addAlert(alerts, "purchaseOverdue", "逾期未到货采购单", "danger",
                "预计到货日已过，联系供应商确认发货", overdueRows);

        List<Map<String, Object>> dueSoonRows = rows("""
                select o.order_no as "no", s.name as "name",
                       to_char(o.expected_date, 'YYYY-MM-DD') as "date",
                       ('¥' || coalesce(o.total_amount, 0)::text) as "note", o.status as "status"
                from rm_purchase_order o left join rm_supplier s on s.id = o.supplier_id
                where o.status in ('CONFIRMED', 'PARTIAL') and o.expected_date between ? and ?
                order by o.expected_date limit 20
                """, today, until);
        addAlert(alerts, "purchaseDueSoon", days + " 天内到货", "warning",
                "即将到货，提前安排好验收与库位", dueSoonRows);

        List<Map<String, Object>> requisitionRows = rows("""
                select r.req_no as "no", coalesce(r.department, r.requester, '-') as "name",
                       to_char(r.created_at, 'YYYY-MM-DD') as "date",
                       ('¥' || coalesce(r.total_amount, 0)::text) as "note", r.status as "status"
                from rm_purchase_requisition r
                where r.status = 'SUBMITTED'
                order by r.created_at limit 20
                """);
        addAlert(alerts, "purchaseRequisition", "待审批采购申请", "warning",
                "申请已提交但还没审批，批完才能转采购单", requisitionRows);
    }

    // ------------------------------------------------------------------ 仓储管理

    private void warehouse(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts) {
        long skuCount = count("select count(*) from rm_inventory where qty > 0");
        BigDecimal stockValue = amount("""
                select coalesce(sum(i.qty * coalesce(p.cost, 0)), 0)
                from rm_inventory i join rm_product p on p.id = i.product_id
                """);
        long lowStock = count("""
                select count(*) from rm_inventory i join rm_product p on p.id = i.product_id
                where p.safety_stock is not null and i.qty < p.safety_stock
                """);
        long pendingInbound = count("""
                select count(*) from rm_inbound_order where status not in ('COMPLETED', 'CANCELLED')
                """);
        long pendingOutbound = count("""
                select count(*) from rm_outbound_order where status not in ('COMPLETED', 'CANCELLED')
                """);
        kpis.add(kpi("skuCount", "在库 SKU", skuCount, "个", "", "结存大于 0"));
        kpis.add(kpi("stockValue", "库存金额", stockValue, "元", "", "按产品标准成本估算"));
        kpis.add(kpi("lowStock", "低库存 SKU", lowStock, "个", lowStock > 0 ? "warn" : "ok", "低于安全库存"));
        kpis.add(kpi("pendingInbound", "待入库单", pendingInbound, "单", pendingInbound > 0 ? "warn" : "", ""));
        kpis.add(kpi("pendingOutbound", "待出库单", pendingOutbound, "单", pendingOutbound > 0 ? "warn" : "", ""));

        List<Map<String, Object>> lowStockRows = rows("""
                select p.code as "no", p.name as "name",
                       coalesce(w.name, '-') as "date",
                       ('结存 ' || i.qty || ' / 安全 ' || p.safety_stock) as "note", 'LOW' as "status"
                from rm_inventory i
                join rm_product p on p.id = i.product_id
                left join rm_warehouse w on w.id = i.warehouse_id
                where p.safety_stock is not null and i.qty < p.safety_stock
                order by (p.safety_stock - i.qty) desc limit 20
                """);
        addAlert(alerts, "warehouseLowStock", "低库存预警", "warning",
                "结存低于安全库存，补货前先确认在途采购能否覆盖", lowStockRows);
    }

    // ------------------------------------------------------------------ 质量与设备

    private void quality(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts,
                         LocalDate from, LocalDate to, LocalDate today, int days) {
        long inspections = count("""
                select count(*) from rm_inspection where inspected_at::date between ? and ?
                """, from, to);
        BigDecimal inspectQty = amount("""
                select coalesce(sum(inspect_qty), 0) from rm_inspection where inspected_at::date between ? and ?
                """, from, to);
        BigDecimal defectQty = amount("""
                select coalesce(sum(defect_qty), 0) from rm_inspection where inspected_at::date between ? and ?
                """, from, to);
        BigDecimal passRate = rate(inspectQty.subtract(defectQty), inspectQty);
        long pendingDefects = count("select count(*) from rm_defect where status = 'PENDING'");
        long rework = count("select count(*) from rm_work_order where status = 'REWORK'");
        long recentFail = count("""
                select count(*) from rm_inspection
                where result = 'FAIL' and inspected_at::date >= ?
                """, today.minusDays(days));
        kpis.add(kpi("inspections", "本月检验单", inspections, "单", "", "按检验时间"));
        kpis.add(kpi("inspectQty", "检验数量", inspectQty, "件", "", ""));
        kpis.add(kpi("defectQty", "不良数量", defectQty, "件", defectQty.signum() > 0 ? "warn" : "ok", ""));
        kpis.add(kpi("passRate", "一次合格率", passRate, "%", passRate.compareTo(new BigDecimal("97")) < 0 ? "warn" : "ok", ""));
        kpis.add(kpi("pendingDefects", "待处置不良", pendingDefects, "笔", pendingDefects > 0 ? "alert" : "ok", ""));
        kpis.add(kpi("rework", "返修在制", rework, "张", "", ""));
        kpis.add(kpi("recentFail", "近 " + days + " 天不合格", recentFail, "单", recentFail > 0 ? "warn" : "ok", ""));

        List<Map<String, Object>> defectRows = rows("""
                select d.defect_no as "no", p.name as "name",
                       to_char(d.created_at, 'YYYY-MM-DD') as "date",
                       ('不良 ' || d.qty || ' · ' || coalesce(d.reason, '未填原因')) as "note", d.status as "status"
                from rm_defect d left join rm_product p on p.id = d.product_id
                where d.status = 'PENDING'
                order by d.created_at limit 20
                """);
        addAlert(alerts, "qualityDefects", "待处置不良", "danger",
                "不良品还挂着未处理，影响结案与成本", defectRows);

        List<Map<String, Object>> failRows = rows("""
                select i.inspection_no as "no", p.name as "name",
                       to_char(i.inspected_at, 'YYYY-MM-DD') as "date",
                       ('不良 ' || coalesce(i.defect_qty, 0)::text) as "note", i.result as "status"
                from rm_inspection i left join rm_product p on p.id = i.product_id
                where i.result = 'FAIL' and i.inspected_at::date >= ?
                order by i.inspected_at desc limit 20
                """, today.minusDays(days));
        addAlert(alerts, "qualityFail", "近 " + days + " 天不合格检验", "warning",
                "看是否有集中不良，必要时开异常安灯", failRows);
    }

    // ------------------------------------------------------------------ 报表分析

    private void report(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts,
                        LocalDate from, LocalDate to) {
        BigDecimal monthSales = amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where created_at::date between ? and ? and status <> 'CANCELLED'
                  and currency = 'CNY'
                """, from, to);
        BigDecimal monthCost = amount("""
                select coalesce(sum(total_cost), 0) from rm_work_order where costed_at::date between ? and ?
                """, from, to);
        BigDecimal profit = monthSales.subtract(monthCost);
        BigDecimal margin = rate(profit, monthSales);
        long finished = count("""
                select count(*) from rm_work_order
                where status in ('FINISHED', 'CLOSED') and updated_at::date between ? and ?
                """, from, to);
        long pendingCost = count("""
                select count(*) from rm_work_order
                where status in ('FINISHED', 'CLOSED') and total_cost is null
                """);
        kpis.add(kpi("monthSales", "本月销售额（人民币）", monthSales, "元", "", "按订单创建时间，仅统计 CNY 订单"));
        kpis.add(kpi("monthCost", "本月完工成本", monthCost, "元", "", "按成本核算时间"));
        kpis.add(kpi("profit", "毛利", profit, "元", profit.signum() < 0 ? "alert" : "ok", "销售额 − 完工成本"));
        kpis.add(kpi("margin", "毛利率", margin, "%", margin.compareTo(BigDecimal.ZERO) < 0 ? "alert" : "", ""));
        kpis.add(kpi("finished", "本月完工工单", finished, "张", "", "按最后更新时间"));
        kpis.add(kpi("pendingCost", "未结算成本", pendingCost, "张", pendingCost > 0 ? "warn" : "ok", "完工但没有成本快照"));

        List<Map<String, Object>> pendingCostRows = rows("""
                select w.work_no as "no", p.name as "name",
                       to_char(w.updated_at, 'YYYY-MM-DD') as "date",
                       ('计划 ' || coalesce(w.plan_qty, 0)::text) as "note", w.status as "status"
                from rm_work_order w left join rm_product p on p.id = w.product_id
                where w.status in ('FINISHED', 'CLOSED') and w.total_cost is null
                order by w.updated_at limit 20
                """);
        addAlert(alerts, "reportPendingCost", "未结算成本工单", "warning",
                "已完工但还没有成本快照，月结前要补算", pendingCostRows);
    }

    // ------------------------------------------------------------------ 基础数据

    private void base(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts) {
        long products = count("select count(*) from rm_product");
        long customers = count("select count(*) from rm_customer");
        long suppliers = count("select count(*) from rm_supplier");
        long processes = count("select count(*) from rm_process");
        long withRouting = count("""
                select count(distinct r.product_id) from rm_product_routing r
                join rm_product p on p.id = r.product_id
                """);
        BigDecimal coverage = products == 0 ? BigDecimal.ZERO : rate(BigDecimal.valueOf(withRouting), BigDecimal.valueOf(products));
        long noRouting = count("""
                select count(*) from rm_product p
                where not exists (select 1 from rm_product_routing r where r.product_id = p.id)
                """);
        long noBom = count("""
                select count(*) from rm_product p
                where p.category in ('FINISHED', 'SEMI')
                  and not exists (select 1 from rm_bom_item b where b.product_id = p.id)
                """);
        kpis.add(kpi("products", "产品数", products, "个", "", "含成品/半成品/原料"));
        kpis.add(kpi("customers", "客户数", customers, "个", "", ""));
        kpis.add(kpi("suppliers", "供应商数", suppliers, "个", "", ""));
        kpis.add(kpi("processes", "工序数", processes, "道", "", ""));
        kpis.add(kpi("routingCoverage", "工艺路线覆盖率", coverage, "%", coverage.compareTo(new BigDecimal("90")) < 0 ? "warn" : "ok", "有路线的产品占比"));
        kpis.add(kpi("noRouting", "缺工艺路线产品", noRouting, "个", noRouting > 0 ? "alert" : "ok", "下达工单会产生空工序"));

        List<Map<String, Object>> noRoutingRows = rows("""
                select p.code as "no", p.name as "name",
                       to_char(p.created_at, 'YYYY-MM-DD') as "date",
                       '未配置工艺路线' as "note", p.category as "status"
                from rm_product p
                where not exists (select 1 from rm_product_routing r where r.product_id = p.id)
                order by p.created_at limit 20
                """);
        addAlert(alerts, "baseNoRouting", "缺工艺路线产品", "danger",
                "这些产品下达工单后工序是空的，需先补路线或手工加工序", noRoutingRows);

        List<Map<String, Object>> noBomRows = rows("""
                select p.code as "no", p.name as "name",
                       to_char(p.created_at, 'YYYY-MM-DD') as "date",
                       '未配置 BOM' as "note", p.category as "status"
                from rm_product p
                where p.category in ('FINISHED', 'SEMI')
                  and not exists (select 1 from rm_bom_item b where b.product_id = p.id)
                order by p.created_at limit 20
                """);
        addAlert(alerts, "baseNoBom", "缺 BOM 产品", "warning",
                "成品/半成品没有用料清单，转生产时不会自动算料", noBomRows);
    }

    // ------------------------------------------------------------------ 系统管理

    private void system(List<Map<String, Object>> kpis, List<Map<String, Object>> alerts) {
        long users = count("select count(*) from rm_sys_user where enabled = true");
        long roles = count("select count(*) from rm_sys_role");
        long locked = count("select count(*) from rm_sys_user where locked_until is not null and locked_until > now()");
        long mustChange = count("select count(*) from rm_sys_user where must_change_password = true");
        long failedJobs = count("""
                select count(*) from rm_sys_job_log
                where status = 'FAILED' and started_at >= now() - interval '7 day'
                """);
        kpis.add(kpi("users", "启用账号", users, "个", "", ""));
        kpis.add(kpi("roles", "角色数", roles, "个", "", ""));
        kpis.add(kpi("locked", "锁定账号", locked, "个", locked > 0 ? "alert" : "ok", "登录失败被锁定"));
        kpis.add(kpi("mustChange", "待改密账号", mustChange, "个", mustChange > 0 ? "warn" : "ok", "首次登录或强制改密"));
        kpis.add(kpi("failedJobs", "近 7 天失败任务", failedJobs, "次", failedJobs > 0 ? "warn" : "ok", "定时任务执行失败"));

        List<Map<String, Object>> failedRows = rows("""
                select job_code as "no", job_name as "name",
                       to_char(started_at, 'MM-DD HH24:MI') as "date",
                       coalesce(message, '执行失败') as "note", status as "status"
                from rm_sys_job_log
                where status = 'FAILED' and started_at >= now() - interval '7 day'
                order by started_at desc limit 20
                """);
        addAlert(alerts, "systemJobs", "近期失败的定时任务", "danger",
                "任务失败会导致预警、成本补算等后台动作没有执行", failedRows);

        List<Map<String, Object>> lockedRows = rows("""
                select username as "no", real_name as "name",
                       to_char(locked_until, 'MM-DD HH24:MI') as "date",
                       '登录失败次数过多' as "note", 'LOCKED' as "status"
                from rm_sys_user
                where locked_until is not null and locked_until > now()
                order by locked_until limit 20
                """);
        addAlert(alerts, "systemLocked", "被锁定账号", "warning",
                "确认是本人连续输错，还是有人在试探密码", lockedRows);
    }

    // ------------------------------------------------------------------ 工具

    private void addAlert(List<Map<String, Object>> alerts, String key, String title, String level,
                          String hint, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("key", key);
        alert.put("title", title);
        alert.put("level", level);
        alert.put("hint", hint);
        alert.put("count", items.size());
        alert.put("items", items);
        alerts.add(alert);
    }

    private Map<String, Object> kpi(String key, String label, Object value, String unit,
                                    String tone, String foot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("value", value);
        row.put("unit", unit);
        row.put("tone", tone == null || tone.isBlank() ? "normal" : tone);
        row.put("foot", foot);
        return row;
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (Exception error) {
            throw new BizException(1002, "月份格式应为 yyyy-MM");
        }
    }

    private BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal amount(String sql, Object... args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }
}
