package com.mes.planning;

import com.mes.common.BizException;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.org.api.WorkCenterPort;
import com.mes.process.api.ProcessPort;
import com.mes.production.api.WorkOrderPort;
import com.mes.sales.api.SalesOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排产甘特与插单模拟。
 *
 * 甘特：按工作中心一行、按天分列，给出每天的计划工时与负荷率，以及落在该天的工序条。
 * 插单模拟：按工艺路线逐道工序消耗「工作中心日产能 − 当日已排产占用」，
 * 一路推到所有工序都排得下为止，得到预计完成日；同时给出会与本单抢产能的工单数。
 * 模拟只读，不写库，看结果再决定是否真的插单。
 *
 * <p>甘特本质是排产域内的跨资源聚合：工序来自生产、产能来自工作中心、名称来自工序与产品。
 * 四份数据各走各的契约，聚合留在计划模块 —— 以前是一条 {@code work_order_operation ⨝ process ⨝
 * work_center ⨝ work_order ⨝ product} 的五表 join。</p>
 */
@Service
public class ApsService {

    private static final int MAX_HORIZON_DAYS = 120;
    private static final List<String> ACTIVE = List.of("SCHEDULED", "RELEASED", "IN_PROGRESS");

    private final JdbcTemplate jdbcTemplate;
    private final WorkOrderPort workOrderPort;
    private final ProcessPort processPort;
    private final WorkCenterPort workCenterPort;
    private final SalesOrderPort salesOrderPort;
    private final MasterDataPort masterData;

    public ApsService(JdbcTemplate jdbcTemplate, WorkOrderPort workOrderPort, ProcessPort processPort,
                      WorkCenterPort workCenterPort, SalesOrderPort salesOrderPort,
                      MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.workOrderPort = workOrderPort;
        this.processPort = processPort;
        this.workCenterPort = workCenterPort;
        this.salesOrderPort = salesOrderPort;
        this.masterData = masterData;
    }

    public record SimulateRequest(Long productId, BigDecimal qty, LocalDate dueDate) {
    }

    /** 拖动模拟：把某张工单移到目标日期（可换工作中心），只算不写库 */
    public record SimulateMoveRequest(Long workOrderId, LocalDate targetDate, Long targetCenterId) {
    }

    public Map<String, Object> gantt(String from, String to) {
        LocalDate start = parseDate(from, LocalDate.now());
        LocalDate end = parseDate(to, start.plusDays(13));
        if (start.isAfter(end)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > 62) {
            throw new BizException(1002, "甘特图区间最大 62 天");
        }
        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            LocalDate day = start.plusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", String.valueOf(day));
            row.put("weekday", day.getDayOfWeek().getValue());
            row.put("today", day.equals(LocalDate.now()));
            days.add(row);
        }

        List<WorkCenterPort.WorkCenterInfo> centers = workCenterPort.centers(true);
        // 工序 → 所属工作中心：排产要按中心汇总负荷
        Map<Long, Long> processCenter = new HashMap<>();
        for (ProcessPort.ProcessInfo process : processPort.processes().values()) {
            if (process.workCenterId() != null) {
                processCenter.put(process.id(), process.workCenterId());
            }
        }

        // 窗内的工序条；工单号/状态与产品名分别来自生产与 masterdata
        List<WorkOrderPort.ScheduleOperation> operations =
                workOrderPort.operationsForScheduling(ACTIVE, start, end);
        Set<Long> productIds = new LinkedHashSet<>();
        Map<Long, WorkOrderPort.ScheduleOrder> orders = workOrderPort.scheduleOrders(
                operations.stream().map(WorkOrderPort.ScheduleOperation::workOrderId).toList());
        for (WorkOrderPort.ScheduleOrder order : orders.values()) {
            productIds.add(order.productId());
        }
        Map<Long, ProductInfo> products = productIds.isEmpty() ? Map.of() : masterData.products(productIds);

        Map<Long, Map<String, BigDecimal>> loadMap = new LinkedHashMap<>();
        Map<Long, Map<String, List<Map<String, Object>>>> barMap = new LinkedHashMap<>();
        int placedOperations = 0;
        for (WorkOrderPort.ScheduleOperation operation : operations) {
            Long centerId = operation.processId() == null ? null : processCenter.get(operation.processId());
            if (centerId == null) {
                // 没有挂工作中心的工序不上甘特（原 SQL 是 join work_center，同样会漏掉）
                continue;
            }
            String date = String.valueOf(operation.scheduleDate());
            loadMap.computeIfAbsent(centerId, key -> new LinkedHashMap<>())
                    .merge(date, decimal(operation.standardMinutes()), BigDecimal::add);
            WorkOrderPort.ScheduleOrder order = orders.get(operation.workOrderId());
            ProductInfo product = order == null ? null : products.get(order.productId());
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("workOrderId", operation.workOrderId());
            bar.put("workNo", order == null ? null : order.workNo());
            bar.put("productCode", product == null ? null : product.code());
            bar.put("productName", product == null ? null : product.name());
            bar.put("seq", operation.seq());
            bar.put("operationName", operation.name());
            bar.put("status", order == null ? null : order.status());
            bar.put("planQty", operation.planQty());
            bar.put("doneQty", operation.doneQty());
            bar.put("minutes", decimal(operation.standardMinutes()).setScale(0, RoundingMode.HALF_UP));
            barMap.computeIfAbsent(centerId, key -> new LinkedHashMap<>())
                    .computeIfAbsent(date, key -> new ArrayList<>())
                    .add(bar);
            placedOperations++;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalLoad = BigDecimal.ZERO;
        for (WorkCenterPort.WorkCenterInfo center : centers) {
            long centerId = center.id();
            BigDecimal capacity = BigDecimal.valueOf(center.dailyCapacityMinutes());
            List<Map<String, Object>> cells = new ArrayList<>();
            for (Map<String, Object> day : days) {
                String date = String.valueOf(day.get("date"));
                BigDecimal load = loadMap.getOrDefault(centerId, Map.of())
                        .getOrDefault(date, BigDecimal.ZERO);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("date", date);
                cell.put("minutes", load.setScale(0, RoundingMode.HALF_UP));
                cell.put("rate", capacity.signum() == 0 ? BigDecimal.ZERO
                        : load.multiply(new BigDecimal("100")).divide(capacity, 0, RoundingMode.HALF_UP));
                cell.put("bars", barMap.getOrDefault(centerId, Map.of()).getOrDefault(date, List.of()));
                cells.add(cell);
                totalLoad = totalLoad.add(load);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("centerId", centerId);
            row.put("code", center.code());
            row.put("name", center.name());
            row.put("dailyCapacity", capacity);
            row.put("cells", cells);
            rows.add(row);
        }

        // 只做了整单排产的单（一键排产 / 单张排产 / 加急插队）：甘特按工序取数会整体漏掉，
        // 这里兜底画成一条「整单」灰条，让粗排结果也看得见，同时明确标出还没精细到工序
        List<WorkOrderPort.OrderLevelPlan> orderLevelPlans =
                workOrderPort.orderLevelPlans(ACTIVE, start, end);
        Map<String, Object> orderLevelRow = null;
        if (!orderLevelPlans.isEmpty()) {
            Set<Long> planProductIds = new LinkedHashSet<>();
            List<Long> planIds = new ArrayList<>();
            for (WorkOrderPort.OrderLevelPlan plan : orderLevelPlans) {
                planProductIds.add(plan.productId());
                planIds.add(plan.id());
            }
            Map<Long, ProductInfo> planProducts = masterData.products(planProductIds);
            Map<Long, BigDecimal> planMinutes = workOrderPort.standardMinutes(planIds);
            Map<String, List<Map<String, Object>>> barsByDate = new LinkedHashMap<>();
            Map<String, BigDecimal> minutesByDate = new LinkedHashMap<>();
            for (WorkOrderPort.OrderLevelPlan plan : orderLevelPlans) {
                String date = String.valueOf(plan.scheduleDate());
                BigDecimal minutes = planMinutes.get(plan.id());
                if (minutes == null) {
                    minutes = BigDecimal.ZERO;
                }
                ProductInfo product = planProducts.get(plan.productId());
                Map<String, Object> bar = new LinkedHashMap<>();
                bar.put("workOrderId", plan.id());
                bar.put("workNo", plan.workNo());
                bar.put("productCode", product == null ? null : product.code());
                bar.put("productName", product == null ? null : product.name());
                bar.put("seq", plan.scheduleSeq());
                bar.put("operationName", "整单");
                bar.put("status", plan.status());
                bar.put("planQty", plan.planQty());
                bar.put("doneQty", plan.doneQty());
                bar.put("minutes", minutes.setScale(0, RoundingMode.HALF_UP));
                bar.put("orderLevel", true);
                barsByDate.computeIfAbsent(date, key -> new ArrayList<>()).add(bar);
                minutesByDate.merge(date, minutes, BigDecimal::add);
            }
            List<Map<String, Object>> orderCells = new ArrayList<>();
            BigDecimal orderLevelLoad = BigDecimal.ZERO;
            for (Map<String, Object> day : days) {
                String date = String.valueOf(day.get("date"));
                BigDecimal load = minutesByDate.getOrDefault(date, BigDecimal.ZERO);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("date", date);
                cell.put("minutes", load.setScale(0, RoundingMode.HALF_UP));
                // 整单排产没有落到具体工作中心，没有「日产能」可对比，负荷率留空
                cell.put("rate", null);
                cell.put("bars", barsByDate.getOrDefault(date, List.of()));
                orderCells.add(cell);
                orderLevelLoad = orderLevelLoad.add(load);
            }
            orderLevelRow = new LinkedHashMap<>();
            orderLevelRow.put("centerId", null);
            orderLevelRow.put("code", "ORDER");
            orderLevelRow.put("name", "整单排产（未到工序级）");
            orderLevelRow.put("dailyCapacity", null);
            orderLevelRow.put("orderLevel", true);
            orderLevelRow.put("cells", orderCells);
            orderLevelRow.put("minutes", orderLevelLoad.setScale(0, RoundingMode.HALF_UP));
            rows.add(orderLevelRow);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("centers", rows.size() - (orderLevelRow == null ? 0 : 1));
        summary.put("operations", placedOperations);
        summary.put("orderLevelCount", orderLevelPlans.size());
        summary.put("totalMinutes", totalLoad.setScale(0, RoundingMode.HALF_UP));
        summary.put("overloadedCells", rows.stream()
                .flatMap(row -> ((List<Map<String, Object>>) row.get("cells")).stream())
                .filter(cell -> decimal(cell.get("rate")).compareTo(new BigDecimal("100")) > 0)
                .count());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", String.valueOf(start));
        result.put("to", String.valueOf(end));
        result.put("days", days);
        result.put("centers", rows);
        result.put("summary", summary);
        return result;
    }

    /** 插单模拟：只读，给出预计完成日与受影响工单 */
    public Map<String, Object> simulate(SimulateRequest request) {
        if (request == null || request.productId() == null || request.qty() == null
                || request.qty().signum() <= 0) {
            throw new BizException(1002, "请选择产品并填写大于 0 的数量");
        }
        ProductInfo product = masterData.product(request.productId()).orElse(null);
        if (product == null) {
            throw new BizException(1002, "产品不存在");
        }

        // 工艺路线：product_routing 是按产品的路线行，工序与产能各自问自己的模块
        Map<Long, ProcessPort.ProcessInfo> processes = processPort.processes();
        Map<Long, WorkCenterPort.WorkCenterInfo> centers = workCenterPort.centersByIds(
                processes.values().stream().map(ProcessPort.ProcessInfo::workCenterId)
                        .filter(java.util.Objects::nonNull).distinct().toList());
        List<Map<String, Object>> routing = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select seq, process_id as "processId", minutes_per_piece as "minutes"
                from product_routing
                where product_id = ?
                order by seq
                """, request.productId())) {
            ProcessPort.ProcessInfo process = row.get("processId") == null
                    ? null : processes.get(((Number) row.get("processId")).longValue());
            if (process != null && !process.enabled()) {
                continue;
            }
            WorkCenterPort.WorkCenterInfo center = process == null || process.workCenterId() == null
                    ? null : centers.get(process.workCenterId());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("seq", row.get("seq"));
            line.put("minutes", decimal(row.get("minutes")));
            line.put("processName", process == null ? null : process.name());
            line.put("centerId", center == null ? null : center.id());
            line.put("centerCode", center == null ? null : center.code());
            line.put("centerName", center == null ? null : center.name());
            line.put("dailyCapacity", center == null
                    ? BigDecimal.valueOf(480) : BigDecimal.valueOf(center.dailyCapacityMinutes()));
            routing.add(line);
        }
        if (routing.isEmpty()) {
            throw new BizException(2014, "该产品没有工艺路线，无法模拟排产");
        }

        LocalDate today = LocalDate.now();
        Map<Long, Map<LocalDate, BigDecimal>> load = loadByCenter(today, today.plusDays(MAX_HORIZON_DAYS));
        Map<Long, BigDecimal> defaultCapacity = new LinkedHashMap<>();
        for (Map<String, Object> line : routing) {
            Object centerId = line.get("centerId");
            if (centerId != null) {
                defaultCapacity.put(((Number) centerId).longValue(), decimal(line.get("dailyCapacity")));
            }
        }

        // 逐道工序按天消耗产能，指针只往后走，保证工序顺序
        LocalDate pointer = today;
        List<Map<String, Object>> steps = new ArrayList<>();
        BigDecimal totalMinutes = BigDecimal.ZERO;
        for (Map<String, Object> line : routing) {
            BigDecimal minutes = request.qty().multiply(decimal(line.get("minutes")))
                    .setScale(0, RoundingMode.CEILING);
            totalMinutes = totalMinutes.add(minutes);
            Long centerId = line.get("centerId") == null ? null : ((Number) line.get("centerId")).longValue();
            BigDecimal capacity = centerId == null ? new BigDecimal("480")
                    : defaultCapacity.getOrDefault(centerId, new BigDecimal("480"));
            BigDecimal remaining = minutes;
            LocalDate day = pointer;
            int guard = 0;
            while (remaining.signum() > 0 && guard < MAX_HORIZON_DAYS) {
                BigDecimal used = centerId == null ? BigDecimal.ZERO
                        : load.getOrDefault(centerId, Map.of()).getOrDefault(day, BigDecimal.ZERO);
                BigDecimal free = capacity.subtract(used).max(BigDecimal.ZERO);
                if (free.signum() > 0) {
                    BigDecimal take = free.min(remaining);
                    remaining = remaining.subtract(take);
                    if (centerId != null) {
                        load.computeIfAbsent(centerId, key -> new LinkedHashMap<>())
                                .merge(day, take, BigDecimal::add);
                    }
                    if (remaining.signum() > 0) {
                        day = day.plusDays(1);
                    }
                } else {
                    day = day.plusDays(1);
                }
                guard++;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("seq", line.get("seq"));
            step.put("processName", line.get("processName"));
            step.put("centerName", line.get("centerName") == null ? "未指定工作中心" : line.get("centerName"));
            step.put("minutes", minutes);
            step.put("finishDate", String.valueOf(day));
            steps.add(step);
            pointer = day;
        }

        LocalDate finish = pointer;
        Long dueDelayDays = null;
        if (request.dueDate() != null) {
            dueDelayDays = ChronoUnit.DAYS.between(request.dueDate(), finish);
        }

        // 会被挤压的工单：用到相同工作中心、计划日或交期在预计完成日之前、且尚未完成
        List<Map<String, Object>> affected = affectedOrders(finish, defaultCapacity.keySet());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("productCode", product.code());
        summary.put("productName", product.name());
        summary.put("qty", request.qty());
        summary.put("totalMinutes", totalMinutes);
        summary.put("startDate", String.valueOf(today));
        summary.put("finishDate", String.valueOf(finish));
        summary.put("dueDate", request.dueDate() == null ? null : String.valueOf(request.dueDate()));
        summary.put("dueDelayDays", dueDelayDays);
        summary.put("affectedOrders", affected.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("steps", steps);
        result.put("affected", affected);
        return result;
    }

    /**
     * 拖动模拟：把某张工单移到目标日期（可选换工作中心），只算不写库。
     *
     * <p>计算方式：先排除本工单自身的已排产占用，得到「其他工单的真实负荷」；
     * 再把本工单各道工序从 targetDate 起逐天往后填，消耗剩余产能，得到新的预计完成日。
     * 如果某格装不下（剩余产能为负），记为冲突，返回冲突明细。</p>
     *
     * <p>受影响工单：跟本工单用到相同工作中心、且计划日在新完成日之前的未完工工单。</p>
     */
    public Map<String, Object> simulateMove(SimulateMoveRequest request) {
        if (request == null || request.workOrderId() == null || request.targetDate() == null) {
            throw new BizException(1002, "请选择工单并指定目标日期");
        }
        WorkOrderPort.ScheduleOrder order = workOrderPort.scheduleOrders(List.of(request.workOrderId()))
                .get(request.workOrderId());
        if (order == null) {
            throw new BizException(1002, "工单不存在");
        }
        List<WorkOrderPort.ScheduleOperation> ownOps =
                workOrderPort.operationsOfWorkOrders(List.of(request.workOrderId()));
        if (ownOps.isEmpty()) {
            throw new BizException(2014, "该工单没有工序，无法模拟排产");
        }

        Map<Long, ProcessPort.ProcessInfo> processes = processPort.processes();
        Map<Long, Long> processCenter = new HashMap<>();
        for (ProcessPort.ProcessInfo process : processes.values()) {
            if (process.workCenterId() != null) {
                processCenter.put(process.id(), process.workCenterId());
            }
        }

        // 其他工单的负荷（排除本工单）
        Map<Long, Map<LocalDate, BigDecimal>> load = loadByCenterExcluding(
                request.targetDate(), request.targetDate().plusDays(MAX_HORIZON_DAYS), request.workOrderId());

        Map<Long, WorkCenterPort.WorkCenterInfo> centers = workCenterPort.centersByIds(
                processCenter.values().stream().distinct().toList());

        LocalDate pointer = request.targetDate();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        BigDecimal totalMinutes = BigDecimal.ZERO;
        for (WorkOrderPort.ScheduleOperation op : ownOps) {
            BigDecimal minutes = op.standardMinutes() == null
                    ? BigDecimal.ZERO : op.standardMinutes();
            totalMinutes = totalMinutes.add(minutes);
            Long centerId = request.targetCenterId() != null
                    ? request.targetCenterId()
                    : (op.processId() == null ? null : processCenter.get(op.processId()));
            WorkCenterPort.WorkCenterInfo center = centerId == null ? null : centers.get(centerId);
            BigDecimal capacity = center == null
                    ? new BigDecimal("480") : BigDecimal.valueOf(center.dailyCapacityMinutes());
            BigDecimal remaining = minutes;
            LocalDate day = pointer;
            int guard = 0;
            while (remaining.signum() > 0 && guard < MAX_HORIZON_DAYS) {
                BigDecimal used = centerId == null ? BigDecimal.ZERO
                        : load.getOrDefault(centerId, Map.of()).getOrDefault(day, BigDecimal.ZERO);
                BigDecimal free = capacity.subtract(used);
                BigDecimal take = free.max(BigDecimal.ZERO).min(remaining);
                if (free.signum() < 0) {
                    conflicts.add(conflict(center, day, used, capacity, order.workNo()));
                }
                remaining = remaining.subtract(take);
                if (centerId != null) {
                    load.computeIfAbsent(centerId, key -> new LinkedHashMap<>())
                            .merge(day, take, BigDecimal::add);
                }
                if (remaining.signum() > 0) {
                    day = day.plusDays(1);
                }
                guard++;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("seq", op.seq());
            step.put("processName", op.name());
            step.put("centerId", centerId);
            step.put("centerName", center == null ? "未指定工作中心" : center.name());
            step.put("minutes", minutes);
            step.put("finishDate", String.valueOf(day));
            steps.add(step);
            pointer = day;
        }

        LocalDate finish = pointer;
        boolean feasible = conflicts.isEmpty();
        List<Map<String, Object>> affected =
                affectedOrders(finish, ownOps.stream()
                        .map(o -> request.targetCenterId() != null
                                ? request.targetCenterId()
                                : (o.processId() == null ? null : processCenter.get(o.processId())))
                        .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));

        ProductInfo product = masterData.product(order.productId()).orElse(null);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("workNo", order.workNo());
        summary.put("productCode", product == null ? null : product.code());
        summary.put("productName", product == null ? null : product.name());
        summary.put("planQty", order.planQty());
        summary.put("originalDate", order.scheduleDate() == null
                ? null : String.valueOf(order.scheduleDate()));
        summary.put("targetDate", String.valueOf(request.targetDate()));
        summary.put("newFinishDate", String.valueOf(finish));
        summary.put("totalMinutes", totalMinutes);
        summary.put("feasible", feasible);
        summary.put("conflictCount", conflicts.size());
        summary.put("affectedOrders", affected.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("steps", steps);
        result.put("conflicts", conflicts);
        result.put("affected", affected);
        return result;
    }

    private Map<String, Object> conflict(WorkCenterPort.WorkCenterInfo center, LocalDate day,
                                         BigDecimal used, BigDecimal capacity, String workNo) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("centerName", center == null ? "未指定" : center.name());
        c.put("date", String.valueOf(day));
        c.put("used", used.setScale(0, RoundingMode.HALF_UP));
        c.put("capacity", capacity.setScale(0, RoundingMode.HALF_UP));
        c.put("overflow", used.subtract(capacity).setScale(0, RoundingMode.HALF_UP));
        c.put("workNo", workNo);
        return c;
    }

    /**
     * 受影响工单：同工作中心、计划日或交期不晚于预计完成日、还没做完的工单。
     * 交期在销售、工序在工作中心映射、产品名在 masterdata —— 逐个契约取回后在内存里筛。
     */
    private List<Map<String, Object>> affectedOrders(LocalDate finish, Set<Long> centerIds) {
        List<WorkOrderPort.ScheduleOrder> orders = workOrderPort.ordersForScheduling(ACTIVE);
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, WorkOrderPort.ScheduleOrder> byId = new LinkedHashMap<>();
        orders.forEach(order -> byId.put(order.id(), order));
        Map<Long, SalesOrderPort.SalesOrderBrief> briefs = salesOrderPort.briefs(orders.stream()
                .map(WorkOrderPort.ScheduleOrder::salesOrderId)
                .filter(java.util.Objects::nonNull).distinct().toList());
        Map<Long, ProductInfo> products = masterData.products(orders.stream()
                .map(WorkOrderPort.ScheduleOrder::productId).distinct().toList());
        Map<Long, Long> processCenter = new HashMap<>();
        for (ProcessPort.ProcessInfo process : processPort.processes().values()) {
            if (process.workCenterId() != null) {
                processCenter.put(process.id(), process.workCenterId());
            }
        }
        // 哪些工单用到了被占用的工作中心
        Set<Long> affectedWorkOrders = new LinkedHashSet<>();
        for (WorkOrderPort.ScheduleOperation operation
                : workOrderPort.operationsOfWorkOrders(byId.keySet())) {
            if (operation.processId() == null) {
                continue;
            }
            Long centerId = processCenter.get(operation.processId());
            if (centerId != null && (centerIds.isEmpty() || centerIds.contains(centerId))) {
                affectedWorkOrders.add(operation.workOrderId());
            } else if (centerId == null && centerIds.isEmpty()) {
                affectedWorkOrders.add(operation.workOrderId());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long workOrderId : affectedWorkOrders) {
            WorkOrderPort.ScheduleOrder order = byId.get(workOrderId);
            if (order == null) {
                continue;
            }
            SalesOrderPort.SalesOrderBrief brief = order.salesOrderId() == null
                    ? null : briefs.get(order.salesOrderId());
            LocalDate planDate = order.scheduleDate() != null
                    ? order.scheduleDate() : (brief == null ? null : brief.deliveryDate());
            if (planDate != null && planDate.isAfter(finish)) {
                continue;
            }
            ProductInfo product = products.get(order.productId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workNo", order.workNo());
            row.put("productCode", product == null ? null : product.code());
            row.put("productName", product == null ? null : product.name());
            row.put("planDate", planDate == null ? null : String.valueOf(planDate));
            row.put("deliveryDate", brief == null || brief.deliveryDate() == null
                    ? null : String.valueOf(brief.deliveryDate()));
            rows.add(row);
            if (rows.size() >= 20) {
                break;
            }
        }
        rows.sort((a, b) -> {
            int left = String.valueOf(a.get("planDate")).compareTo(String.valueOf(b.get("planDate")));
            return left != 0 ? left : String.valueOf(a.get("workNo")).compareTo(String.valueOf(b.get("workNo")));
        });
        return rows;
    }

    /** 已排产负荷：工作中心 × 日期 → 工时 */
    private Map<Long, Map<LocalDate, BigDecimal>> loadByCenter(LocalDate from, LocalDate to) {
        return loadByCenterExcluding(from, to, null);
    }

    /** 已排产负荷（排除指定工单）：拖动模拟时要先把本工单自身的占用拿掉 */
    private Map<Long, Map<LocalDate, BigDecimal>> loadByCenterExcluding(LocalDate from, LocalDate to,
                                                                        Long excludeWorkOrderId) {
        Map<Long, Long> processCenter = new HashMap<>();
        for (ProcessPort.ProcessInfo process : processPort.processes().values()) {
            if (process.workCenterId() != null) {
                processCenter.put(process.id(), process.workCenterId());
            }
        }
        Map<Long, Map<LocalDate, BigDecimal>> map = new LinkedHashMap<>();
        for (WorkOrderPort.ScheduleOperation operation : workOrderPort.operationsForScheduling(ACTIVE, from, to)) {
            if (excludeWorkOrderId != null && operation.workOrderId() == excludeWorkOrderId) {
                continue;
            }
            if (operation.processId() == null || operation.scheduleDate() == null) {
                continue;
            }
            Long centerId = processCenter.get(operation.processId());
            if (centerId == null) {
                continue;
            }
            map.computeIfAbsent(centerId, key -> new LinkedHashMap<>())
                    .merge(operation.scheduleDate(), decimal(operation.standardMinutes()), BigDecimal::add);
        }
        return map;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new BizException(1002, "日期格式应为 yyyy-MM-dd：" + value);
        }
    }
}
