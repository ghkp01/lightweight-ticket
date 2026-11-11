package com.mes.dashboard;

import com.mes.common.BizException;
import com.mes.inventory.api.InventoryPort;
import com.mes.inventory.api.StockLine;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.masterdata.api.WarehouseInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;
    private final InventoryPort inventory;
    private final MasterDataPort masterData;
    /** 大屏聚合结果短时缓存：多块屏同一时刻只查一次库；测试环境配 0 关闭 */
    private final long cacheMs;
    private final Map<String, CachedSummary> summaryCache = new ConcurrentHashMap<>();
    /** 大屏实时推送：每个连接按 10 秒推一次，连接断开后自动取消 */
    private final ScheduledExecutorService streamScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "dashboard-sse");
        thread.setDaemon(true);
        return thread;
    });

    private record CachedSummary(long expiresAt, Map<String, Object> data) {
    }

    public DashboardService(JdbcTemplate jdbcTemplate, InventoryPort inventory, MasterDataPort masterData,
                            @Value("${mes.dashboard.cache-ms:8000}") long cacheMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventory = inventory;
        this.masterData = masterData;
        this.cacheMs = cacheMs;
    }

    /**
     * 大屏实时数据流：建立连接后立即推一次，之后每 10 秒推一次摘要。
     * 前端用带 Authorization 头的 fetch 读取 text/event-stream，避免把令牌放进 URL。
     */
    public SseEmitter stream(LocalDate from, LocalDate to) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable push = () -> {
            try {
                emitter.send(SseEmitter.event().name("summary").data(summary(from, to)));
            } catch (Exception error) {
                ScheduledFuture<?> task = future.get();
                if (task != null) {
                    task.cancel(false);
                }
                emitter.complete();
            }
        };
        future.set(streamScheduler.scheduleAtFixedRate(push, 0, 10, TimeUnit.SECONDS));
        emitter.onCompletion(() -> cancel(future));
        emitter.onTimeout(() -> cancel(future));
        emitter.onError(error -> cancel(future));
        return emitter;
    }

    private void cancel(AtomicReference<ScheduledFuture<?>> future) {
        ScheduledFuture<?> task = future.get();
        if (task != null) {
            task.cancel(false);
        }
    }

    public Map<String, Object> summary() {
        return summary(null, null);
    }

    /**
     * 工作台 / 经营大屏共用：除固定的今日、本月口径外，再给一段可选的 from~to 区间，
     * 以及该区间的环比（上一等长周期）与同比（去年同区间），供大屏做经营对比。
     *
     * <p>同一区间在 cacheMs 内复用结果，避免多块大屏同时刷新把同一批 SQL 重复打一遍。</p>
     */
    public Map<String, Object> summary(LocalDate from, LocalDate to) {
        if (cacheMs <= 0) {
            return computeSummary(from, to);
        }
        String key = String.valueOf(from) + "|" + String.valueOf(to);
        long now = System.currentTimeMillis();
        CachedSummary cached = summaryCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.data();
        }
        synchronized (summaryCache) {
            cached = summaryCache.get(key);
            if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
                return cached.data();
            }
            Map<String, Object> data = computeSummary(from, to);
            if (summaryCache.size() > 32) {
                summaryCache.clear();
            }
            summaryCache.put(key, new CachedSummary(System.currentTimeMillis() + cacheMs, data));
            return data;
        }
    }

    private Map<String, Object> computeSummary(LocalDate from, LocalDate to) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todaySales", amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where created_at::date = current_date and status <> 'CANCELLED'
                  and currency = 'CNY'
                """));
        result.put("monthSales", amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where date_trunc('month', created_at) = date_trunc('month', now()) and status <> 'CANCELLED'
                  and currency = 'CNY'
                """));
        result.put("activeOrders", count("select count(*) from rm_sales_order where status not in ('COMPLETED', 'CANCELLED')"));
        result.put("producingOrders", count("select count(*) from rm_work_order where status in ('RELEASED', 'IN_PROGRESS')"));
        result.put("pendingShip", count("select count(*) from rm_sales_order where status = 'READY_TO_SHIP'"));
        // 低库存：结存属于库存模块、安全库存属于基础数据，各自按契约取数，比较放在这里做
        List<Map<String, Object>> lowStockRows = lowStockRows();
        result.put("lowStock", (long) lowStockRows.size());
        result.put("pendingReturns", count("select count(*) from return_order where status = 'PENDING'"));

        // 合格率口径：一次合格率 = 报工合格数 / 报工数（工序直接检验的结果，最贴近制造水平）
        Map<String, Object> quality = jdbcTemplate.queryForList("""
                select coalesce(sum(qty), 0) as inspect_qty, coalesce(sum(qualified_qty), 0) as qualified_qty
                from work_report where reported_at >= now() - interval '30 day'
                """).get(0);
        BigDecimal inspectQty = new BigDecimal(String.valueOf(quality.get("inspect_qty")));
        BigDecimal qualifiedQty = new BigDecimal(String.valueOf(quality.get("qualified_qty")));
        BigDecimal rate = inspectQty.signum() == 0 ? BigDecimal.valueOf(100)
                : qualifiedQty.multiply(BigDecimal.valueOf(100))
                .divide(inspectQty, 2, RoundingMode.HALF_UP);
        result.put("passRate", rate);
        result.put("reportQty", inspectQty);
        result.put("pendingDefects", count("select count(*) from defect_record where status = 'PENDING'"));

        result.put("trend", jdbcTemplate.queryForList("""
                select to_char(d.day, 'MM-DD') as "date", coalesce(sum(so.total_amount), 0) as "amount"
                from generate_series(current_date - 6, current_date, interval '1 day') d(day)
                left join rm_sales_order so on so.created_at::date = d.day and so.status <> 'CANCELLED'
                    and so.currency = 'CNY'
                group by d.day order by d.day
                """));

        result.put("workOrderStatus", jdbcTemplate.queryForList("""
                select status as "status", count(*) as "value"
                from rm_work_order group by status order by status
                """));

        result.put("lowStockList", lowStockRows.stream().limit(6).toList());

        result.put("recentOrders", jdbcTemplate.queryForList("""
                select so.order_no as "orderNo", c.name as "customerName", so.total_amount as "totalAmount",
                       so.currency,
                       so.status, to_char(so.created_at, 'MM-DD HH24:MI') as "createdAt"
                from rm_sales_order so join rm_customer c on c.id = so.customer_id
                order by so.id desc limit 5
                """));

        result.put("pendingShipments", jdbcTemplate.queryForList("""
                select s.shipment_no as "shipmentNo", c.name as "customerName", s.carrier, s.status,
                       to_char(s.created_at, 'MM-DD') as "createdAt"
                from rm_shipment s join rm_customer c on c.id = s.customer_id
                where s.status in ('DRAFT', 'SHIPPED') order by s.id desc limit 5
                """));
        result.put("tasks", tasks());

        // ---------- 区间口径与经营对比：环比 = 上一等长周期，同比 = 去年同区间 ----------
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end : from;
        if (start.isAfter(end)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        LocalDate lastYearStart = start.minusYears(1);
        LocalDate lastYearEnd = end.minusYears(1);

        BigDecimal rangeSales = amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where created_at::date between ? and ? and status <> 'CANCELLED'
                  and currency = 'CNY'
                """, start, end);
        BigDecimal prevSales = amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where created_at::date between ? and ? and status <> 'CANCELLED'
                  and currency = 'CNY'
                """, prevStart, prevEnd);
        BigDecimal lastYearSales = amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where created_at::date between ? and ? and status <> 'CANCELLED'
                  and currency = 'CNY'
                """, lastYearStart, lastYearEnd);
        BigDecimal rangeOutput = outputInRange(start, end);
        BigDecimal prevOutput = outputInRange(prevStart, prevEnd);
        BigDecimal rangePassRate = passRateInRange(start, end);
        BigDecimal prevPassRate = passRateInRange(prevStart, prevEnd);

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", start.toString());
        range.put("to", end.toString());
        range.put("days", days);
        result.put("range", range);
        result.put("rangeSales", rangeSales);
        result.put("prevRangeSales", prevSales);
        result.put("lastYearSales", lastYearSales);
        result.put("salesMom", percentChange(rangeSales, prevSales));
        result.put("salesYoy", percentChange(rangeSales, lastYearSales));
        result.put("rangeOutput", rangeOutput);
        result.put("prevRangeOutput", prevOutput);
        result.put("outputMom", percentChange(rangeOutput, prevOutput));
        result.put("rangePassRate", rangePassRate);
        result.put("prevRangePassRate", prevPassRate);
        result.put("rangeTrend", trendInRange(start, end));
        return result;
    }

    private BigDecimal outputInRange(LocalDate start, LocalDate end) {
        return amount("""
                select coalesce(sum(qty), 0) from work_report
                where reported_at::date between ? and ?
                """, start, end);
    }

    private BigDecimal passRateInRange(LocalDate start, LocalDate end) {
        Map<String, Object> row = jdbcTemplate.queryForList("""
                select coalesce(sum(qty), 0) as qty, coalesce(sum(qualified_qty), 0) as qualified
                from work_report where reported_at::date between ? and ?
                """, start, end).get(0);
        BigDecimal qty = new BigDecimal(String.valueOf(row.get("qty")));
        BigDecimal qualified = new BigDecimal(String.valueOf(row.get("qualified")));
        return qty.signum() == 0 ? null
                : qualified.multiply(BigDecimal.valueOf(100)).divide(qty, 2, RoundingMode.HALF_UP);
    }

    private List<Map<String, Object>> trendInRange(LocalDate start, LocalDate end) {
        return jdbcTemplate.queryForList("""
                select to_char(d.day, 'MM-DD') as "date", coalesce(sum(so.total_amount), 0) as "amount"
                from generate_series(?::date, ?::date, interval '1 day') d(day)
                left join rm_sales_order so on so.created_at::date = d.day and so.status <> 'CANCELLED'
                    and so.currency = 'CNY'
                group by d.day order by d.day
                """, start, end);
    }

    /** 环比 / 同比：上一周期为 0 时没有意义，返回 null 让前端显示「—」 */
    private BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 2, RoundingMode.HALF_UP);
    }

    /**
     * 一线今天要干的活。四个数字都按「还没做完」的口径算，点进去就是对应列表：
     * 待报工工序、待领料工单、外协待收回、逾期在制工单。
     *
     * <p>外协工序的料不在本厂领，所以「待领料」要把外协工序排掉，
     * 否则工人点进去会发现这条料根本领不出来。</p>
     */
    private List<Map<String, Object>> tasks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(task("pendingReport", "待报工工序", "工单已下达，还有工序没报完",
                "/production", "warning", count("""
                        select count(*) from rm_work_order_operation o
                        join rm_work_order w on w.id = o.work_order_id
                        where w.status in ('RELEASED', 'IN_PROGRESS') and o.status <> 'FINISHED'
                        """)));
        rows.add(task("pendingIssue", "待领料工单", "还没领齐料，料不到就开不了工",
                "/production", "danger", count("""
                        select count(distinct w.id) from rm_work_order w
                        join rm_work_order_bom b on b.work_order_id = w.id and b.source <> 'SUBSTITUTE'
                        where w.status in ('RELEASED', 'IN_PROGRESS') and b.issued_qty < b.plan_qty
                          and not exists (select 1 from rm_work_order_operation o
                                           where o.work_order_id = w.id and o.seq = b.operation_seq
                                             and o.outsource_status in ('PENDING_SEND', 'SENT'))
                        """)));
        rows.add(task("outsourcing", "外协待收回", "已发给外协厂，还没收回来",
                "/production/outsource", "primary", count("""
                        select count(*) from rm_outsource_order where status in ('SENT', 'PARTIAL')
                        """)));
        rows.add(task("lateOrders", "逾期在制工单", "过了订单交期还没完工",
                "/production", "danger", count("""
                        select count(*) from rm_work_order w
                        join rm_sales_order so on so.id = w.sales_order_id
                        where w.status not in ('FINISHED', 'CLOSED')
                          and so.delivery_date is not null and so.delivery_date < current_date
                        """)));
        return rows;
    }

    private Map<String, Object> task(String key, String label, String hint, String path,
                                     String tone, long value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("hint", hint);
        row.put("path", path);
        row.put("tone", tone);
        row.put("value", value);
        return row;
    }

    /**
     * 低库存明细：结存走库存契约、安全库存与产品/仓库信息走基础数据契约，
     * 缺口（安全库存 - 结存）在本模块比出来并按缺口倒序 —— 和原来那条三表 join 的口径一致。
     */
    private List<Map<String, Object>> lowStockRows() {
        List<StockLine> stocks = inventory.allStocks();
        if (stocks.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductInfo> products = masterData.products(
                stocks.stream().map(StockLine::productId).toList());
        Map<Long, WarehouseInfo> warehouses = masterData.warehouses(
                stocks.stream().map(StockLine::warehouseId).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockLine line : stocks) {
            ProductInfo product = products.get(line.productId());
            // 没维护安全库存的产品不参与预警，与 SQL 里 qty < null 恒为假保持一致
            if (product == null || product.safetyStock() == null || line.qty() == null
                    || line.qty().compareTo(product.safetyStock()) >= 0) {
                continue;
            }
            WarehouseInfo warehouse = warehouses.get(line.warehouseId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productCode", product.code());
            row.put("productName", product.name());
            row.put("unit", product.unit());
            row.put("warehouseName", warehouse == null ? null : warehouse.name());
            row.put("qty", line.qty());
            row.put("safetyStock", product.safetyStock());
            row.put("_gap", product.safetyStock().subtract(line.qty()));
            rows.add(row);
        }
        rows.sort((left, right) -> ((BigDecimal) right.get("_gap")).compareTo((BigDecimal) left.get("_gap")));
        rows.forEach(row -> row.remove("_gap"));
        return rows;
    }

    private BigDecimal amount(String sql, Object... args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
