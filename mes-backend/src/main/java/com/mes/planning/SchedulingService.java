package com.mes.planning;

import com.mes.common.BizException;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.org.api.WorkCenterPort;
import com.mes.process.api.ProcessPort;
import com.mes.production.api.WorkOrderPort;
import com.mes.sales.api.SalesOrderPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排产：一键排产、排产看板、手工排 / 加急插队 / 调顺序 / 撤销，以及有限产能排产。
 *
 * <p>排产算的是「谁先做、哪天做」，但排产字段在工单上、产能字段在工作中心与工序上：
 * 所以这里的读全部走契约（{@link WorkOrderPort} / {@link ProcessPort} / {@link WorkCenterPort} /
 * {@link SalesOrderPort} / {@link MasterDataPort}），写也只发命令
 * （{@code placeSchedule} / {@code clearSchedule} / {@code scheduleOperation}）。
 * 计划模块自己不再出现 {@code work_order} / {@code work_center} 这些表名。</p>
 */
@Service
public class SchedulingService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<String> ACTIVE = List.of("SCHEDULED", "RELEASED", "IN_PROGRESS");
    private static final List<String> DRAFT = List.of("DRAFT");

    private final WorkOrderPort workOrderPort;
    private final ProcessPort processPort;
    private final WorkCenterPort workCenterPort;
    private final SalesOrderPort salesOrderPort;
    private final MasterDataPort masterData;

    public SchedulingService(WorkOrderPort workOrderPort, ProcessPort processPort,
                             WorkCenterPort workCenterPort, SalesOrderPort salesOrderPort,
                             MasterDataPort masterData) {
        this.workOrderPort = workOrderPort;
        this.processPort = processPort;
        this.workCenterPort = workCenterPort;
        this.salesOrderPort = salesOrderPort;
        this.masterData = masterData;
    }

    public record ScheduleRequest(LocalDate date, Integer sequence) {
    }

    public record RushRequest(LocalDate date) {
    }

    public record MoveRequest(String direction) {
    }

    public record AutoRequest(LocalDate startDate, Integer days, Integer dailyCapacity) {
    }

    public record ApsRequest(LocalDate startDate, Integer days) {
    }

    /**
     * 一键智能排产：按优先级、交期排序，在每日可用工时内依次填充。
     */
    @Transactional
    public Map<String, Object> autoSchedule(AutoRequest request) {
        LocalDate start = request == null || request.startDate() == null ? LocalDate.now() : request.startDate();
        int days = clamp(request == null ? null : request.days(), 14, 1, 60);
        int capacity = clamp(request == null ? null : request.dailyCapacity(), 480, 60, 1440);
        LocalDate end = start.plusDays(days - 1L);

        // 已占用：按排产日汇总工单工序工时
        Map<LocalDate, Double> used = new HashMap<>();
        for (Map.Entry<LocalDate, BigDecimal> entry
                : workOrderPort.scheduledMinutesByDate(start, end).entrySet()) {
            used.put(entry.getKey(), decimal(entry.getValue()).doubleValue());
        }
        Map<LocalDate, Integer> nextSeq = new HashMap<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            Integer max = workOrderPort.maxScheduleSeq(date, null);
            nextSeq.put(date, max == null ? 0 : max);
        }
        Map<LocalDate, Integer> counts = new HashMap<>();

        List<WorkOrderPort.ScheduleOrder> works = workOrderPort.ordersForScheduling(DRAFT);
        Map<Long, String> productNames = productNames(works.stream()
                .map(WorkOrderPort.ScheduleOrder::productId).toList());
        Map<Long, SalesOrderPort.SalesOrderBrief> orders = briefOf(works);
        Map<Long, BigDecimal> minutesMap = works.isEmpty()
                ? Map.of() : workOrderPort.standardMinutes(works.stream()
                        .map(WorkOrderPort.ScheduleOrder::id).toList());
        works = sorted(works, orders);

        List<Map<String, Object>> assignments = new ArrayList<>();
        int late = 0;
        int skipped = 0;
        for (WorkOrderPort.ScheduleOrder work : works) {
            long id = work.id();
            BigDecimal minutes = minutesMap.get(id);
            if (minutes == null || minutes.signum() <= 0) {
                minutes = work.planQty().multiply(new BigDecimal("6.5"));
            }
            double need = minutes.doubleValue();
            LocalDate chosen = null;
            for (int i = 0; i < days; i++) {
                LocalDate date = start.plusDays(i);
                double load = used.getOrDefault(date, 0.0);
                if (load <= 0 || load + need <= capacity + 0.001) {
                    chosen = date;
                    break;
                }
            }
            if (chosen == null) {
                skipped++;
                continue;
            }
            int seq = nextSeq.getOrDefault(chosen, 0) + 10;
            nextSeq.put(chosen, seq);
            used.put(chosen, used.getOrDefault(chosen, 0.0) + need);
            counts.put(chosen, counts.getOrDefault(chosen, 0) + 1);
            workOrderPort.placeSchedule(id, chosen, seq, chosen.atStartOfDay(), chosen.atStartOfDay(),
                    "SCHEDULED");

            SalesOrderPort.SalesOrderBrief order = orderOf(work, orders);
            LocalDate delivery = order == null ? null : order.deliveryDate();
            boolean isLate = delivery != null && chosen.isAfter(delivery);
            if (isLate) {
                late++;
            }
            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put("id", id);
            assignment.put("workNo", work.workNo());
            assignment.put("productName", productNames.get(work.productId()));
            assignment.put("priority", work.priority());
            assignment.put("scheduleDate", chosen);
            assignment.put("scheduleSeq", seq);
            assignment.put("minutes", need);
            assignment.put("deliveryDate", delivery);
            assignment.put("late", isLate);
            assignments.add(assignment);
        }

        List<Map<String, Object>> dayRows = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            row.put("usedMinutes", used.getOrDefault(date, 0.0));
            row.put("capacityMinutes", capacity);
            row.put("workOrders", counts.getOrDefault(date, 0));
            row.put("overloaded", used.getOrDefault(date, 0.0) > capacity);
            dayRows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduledCount", assignments.size());
        result.put("lateCount", late);
        result.put("skippedCount", skipped);
        result.put("dailyCapacity", capacity);
        result.put("assignments", assignments);
        result.put("days", dayRows);
        return result;
    }

    /**
     * 排产看板：待排产工单 + 近 7 天到未来 14 天的已排产工单。
     */
    public Map<String, Object> board() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", LocalDate.now());

        List<WorkOrderPort.ScheduleOrder> drafts = workOrderPort.ordersForScheduling(DRAFT);
        drafts = sorted(drafts, briefOf(drafts));
        List<WorkOrderPort.ScheduleOrder> active = workOrderPort.ordersForScheduling(ACTIVE);
        Map<Long, SalesOrderPort.SalesOrderBrief> orders = briefOf(active);
        List<Long> productIds = new ArrayList<>();
        drafts.forEach(order -> productIds.add(order.productId()));
        active.forEach(order -> productIds.add(order.productId()));
        Map<Long, ProductInfo> products = productMap(productIds);

        LocalDate from = LocalDate.now().minusDays(7);
        LocalDate to = LocalDate.now().plusDays(14);
        List<Map<String, Object>> unscheduled = new ArrayList<>();
        for (WorkOrderPort.ScheduleOrder work : drafts) {
            ProductInfo product = products.get(work.productId());
            SalesOrderPort.SalesOrderBrief order = orderOf(work, orders);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", work.id());
            row.put("workNo", work.workNo());
            row.put("productCode", product == null ? null : product.code());
            row.put("productName", product == null ? null : product.name());
            row.put("planQty", work.planQty());
            row.put("priority", work.priority());
            row.put("orderNo", order == null ? null : order.orderNo());
            row.put("deliveryDate", order == null ? null : order.deliveryDate());
            row.put("createdAt", work.createdAt() == null ? null : work.createdAt().format(DATE));
            unscheduled.add(row);
        }
        List<Map<String, Object>> scheduled = new ArrayList<>();
        for (WorkOrderPort.ScheduleOrder work : active) {
            if (work.scheduleDate() == null
                    || work.scheduleDate().isBefore(from) || work.scheduleDate().isAfter(to)) {
                continue;
            }
            ProductInfo product = products.get(work.productId());
            SalesOrderPort.SalesOrderBrief order = orderOf(work, orders);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", work.id());
            row.put("workNo", work.workNo());
            row.put("productCode", product == null ? null : product.code());
            row.put("productName", product == null ? null : product.name());
            row.put("planQty", work.planQty());
            row.put("doneQty", work.doneQty());
            row.put("priority", work.priority());
            row.put("status", work.status());
            row.put("scheduleDate", work.scheduleDate());
            row.put("scheduleSeq", work.scheduleSeq());
            row.put("scheduledStart", work.scheduledStart());
            row.put("scheduledEnd", work.scheduledEnd());
            row.put("orderNo", order == null ? null : order.orderNo());
            row.put("deliveryDate", order == null ? null : order.deliveryDate());
            scheduled.add(row);
        }
        scheduled.sort(Comparator.comparing((Map<String, Object> row) -> (LocalDate) row.get("scheduleDate"))
                .thenComparingLong(row -> row.get("scheduleSeq") == null
                        ? Long.MAX_VALUE : ((Number) row.get("scheduleSeq")).longValue()));
        result.put("unscheduled", unscheduled);
        result.put("scheduled", scheduled);
        return result;
    }

    /**
     * 排产：仅 DRAFT 工单可排，缺省顺序为当日最大顺序 +10。
     */
    @Transactional
    public Map<String, Object> schedule(long id, ScheduleRequest request) {
        WorkOrderPort.ScheduleOrder work = loadWork(id);
        if (!"DRAFT".equals(work.status())) {
            throw new BizException(2014, "只有待排产的草稿工单可以排产");
        }
        LocalDate date = request == null || request.date() == null ? LocalDate.now() : request.date();
        Integer sequence = request == null ? null : request.sequence();
        if (sequence != null && sequence <= 0) {
            throw new BizException(1002, "排产顺序必须大于 0");
        }
        Integer max = workOrderPort.maxScheduleSeq(date, null);
        int seq = sequence == null ? (max == null ? 10 : max + 10) : sequence;
        workOrderPort.placeSchedule(id, date, seq, date.atStartOfDay(), date.atStartOfDay(), "SCHEDULED");
        return result(id, date, seq, "SCHEDULED");
    }

    /**
     * 加急插队：DRAFT 先转 SCHEDULED，优先级置 RUSH，顺序取当日最小 -10。
     */
    @Transactional
    public Map<String, Object> rush(long id, RushRequest request) {
        WorkOrderPort.ScheduleOrder work = loadWork(id);
        String status = work.status();
        if (!"DRAFT".equals(status) && !isActiveScheduled(status)) {
            throw new BizException(2018, "当前状态不允许加急插队");
        }
        LocalDate date = request == null ? null : request.date();
        if (date == null) {
            date = work.scheduleDate();
        }
        if (date == null) {
            date = LocalDate.now();
        }
        Integer min = workOrderPort.minScheduleSeq(date, id);
        int seq = min == null ? 10 : min - 10;
        String targetStatus = "DRAFT".equals(status) ? "SCHEDULED" : status;
        workOrderPort.updatePriority(id, "RUSH");
        workOrderPort.placeSchedule(id, date, seq, date.atStartOfDay(), date.atStartOfDay(), targetStatus);
        return result(id, date, seq, targetStatus);
    }

    /**
     * 调整顺序：与同日期相邻工单交换 schedule_seq。
     */
    @Transactional
    public Map<String, Object> move(long id, MoveRequest request) {
        WorkOrderPort.ScheduleOrder work = loadWork(id);
        LocalDate date = work.scheduleDate();
        Integer seq = work.scheduleSeq();
        if (date == null || seq == null) {
            throw new BizException(2016, "工单尚未排产，无法调整顺序");
        }
        String direction = request == null || request.direction() == null
                ? "" : request.direction().trim().toUpperCase();
        if (!"UP".equals(direction) && !"DOWN".equals(direction)) {
            throw new BizException(1002, "调整方向只能是 UP 或 DOWN");
        }
        boolean up = "UP".equals(direction);
        WorkOrderPort.ScheduleOrder neighbor = workOrderPort.scheduleNeighbor(date, seq, id, up);
        if (neighbor == null) {
            throw new BizException(2015, up ? "已经是当日第一单" : "已经是当日最后一单");
        }
        workOrderPort.swapScheduleSeq(id, seq, neighbor.id(), neighbor.scheduleSeq());
        return result(id, date, neighbor.scheduleSeq(), work.status());
    }

    /**
     * 撤销排产：SCHEDULED 回 DRAFT，并清空排产字段。
     */
    @Transactional
    public Map<String, Object> unschedule(long id) {
        WorkOrderPort.ScheduleOrder work = loadWork(id);
        if (!"SCHEDULED".equals(work.status())) {
            throw new BizException(2017, "只有已排产且未下达的工单可以撤销排产");
        }
        workOrderPort.clearSchedule(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("workNo", work.workNo());
        result.put("status", "DRAFT");
        return result;
    }

    /**
     * 有限产能排产：按工序占用各工作中心的日产能，给出工序级开始 / 结束时间。
     */
    @Transactional
    public Map<String, Object> apsSchedule(ApsRequest request) {
        LocalDate start = request == null || request.startDate() == null ? LocalDate.now() : request.startDate();
        int days = clamp(request == null ? null : request.days(), 14, 1, 60);
        LocalDate end = start.plusDays(days - 1L);

        // 工序与工作中心：产能口径都在各自的模块里
        Map<Long, ProcessPort.ProcessInfo> processes = processPort.processes();
        Map<Long, Integer> processCapacity = new HashMap<>();
        Map<Long, Long> processCenter = new HashMap<>();
        Map<Long, String> processNames = new HashMap<>();
        for (ProcessPort.ProcessInfo process : processes.values()) {
            processNames.put(process.id(), process.name());
            processCapacity.put(process.id(), process.dailyCapacityMinutes() == null
                    ? 480 : process.dailyCapacityMinutes());
            if (process.workCenterId() != null) {
                processCenter.put(process.id(), process.workCenterId());
            }
        }

        List<WorkCenterPort.WorkCenterInfo> centers = workCenterPort.centers(false);
        Map<Long, String> centerNames = new HashMap<>();
        Map<Long, Integer> centerCapacity = new HashMap<>();
        Map<Long, String> centerTypes = new HashMap<>();
        for (WorkCenterPort.WorkCenterInfo center : centers) {
            centerNames.put(center.id(), center.name());
            centerCapacity.put(center.id(), center.dailyCapacityMinutes());
            centerTypes.put(center.id(), center.type());
        }

        // 设备台账：可用设备（运行 / 待机）折算中心实际可用产能，停机与维修不计
        Map<Long, Integer> deviceCapacity = new HashMap<>();
        Map<Long, Integer> deviceCount = new HashMap<>();
        for (Map.Entry<Long, WorkCenterPort.DeviceCapacity> entry : workCenterPort.deviceCapacity().entrySet()) {
            deviceCount.put(entry.getKey(), entry.getValue().deviceCount());
            deviceCapacity.put(entry.getKey(), entry.getValue().capacityMinutes());
        }
        Map<Long, Integer> effectiveCapacity = new HashMap<>();
        for (Long centerId : centerCapacity.keySet()) {
            int base = centerCapacity.getOrDefault(centerId, 480);
            Integer devices = deviceCapacity.get(centerId);
            // 维护了设备就按设备可用产能折算，但不超过中心日产能；没维护设备则沿用中心产能
            effectiveCapacity.put(centerId, devices == null ? base : Math.min(base, devices));
        }

        // 已占用：按「资源 + 日期」汇总工序工时；资源优先按工作中心共享，没有中心才退回按工序
        Map<String, Double> used = new HashMap<>();
        for (WorkOrderPort.ScheduleOperation operation
                : workOrderPort.operationsForScheduling(ACTIVE, start, end)) {
            LocalDate day = operation.scheduleDate();
            if (day == null) {
                continue;
            }
            Long centerId = operation.processId() == null ? null : processCenter.get(operation.processId());
            String key = day + "|" + (centerId == null ? "P" + operation.processId() : "C" + centerId);
            used.merge(key, decimal(operation.standardMinutes()).doubleValue(), Double::sum);
        }

        List<WorkOrderPort.ScheduleOrder> works = workOrderPort.ordersForScheduling(DRAFT);
        works = sorted(works, briefOf(works));
        Map<Long, String> productNames = productNames(works.stream()
                .map(WorkOrderPort.ScheduleOrder::productId).toList());
        Map<Long, SalesOrderPort.SalesOrderBrief> orders = briefOf(works);
        Map<Long, List<WorkOrderPort.ScheduleOperation>> operationsByWorkOrder = new HashMap<>();
        for (WorkOrderPort.ScheduleOperation operation : workOrderPort.operationsOfWorkOrders(
                works.stream().map(WorkOrderPort.ScheduleOrder::id).toList())) {
            operationsByWorkOrder.computeIfAbsent(operation.workOrderId(), key -> new ArrayList<>())
                    .add(operation);
        }
        for (List<WorkOrderPort.ScheduleOperation> lines : operationsByWorkOrder.values()) {
            lines.sort(Comparator.comparingInt(WorkOrderPort.ScheduleOperation::seq));
        }

        int scheduled = 0;
        int late = 0;
        int skipped = 0;
        List<Map<String, Object>> assignments = new ArrayList<>();
        Map<LocalDate, Integer> nextSeq = new HashMap<>();
        for (WorkOrderPort.ScheduleOrder work : works) {
            long workId = work.id();
            List<WorkOrderPort.ScheduleOperation> ops = operationsByWorkOrder.getOrDefault(workId, List.of());
            if (ops.isEmpty()) {
                skipped++;
                continue;
            }
            LocalDateTime cursor = start.atTime(8, 0);
            LocalDate firstDay = null;
            LocalDateTime firstStart = null;
            LocalDateTime lastEnd = null;
            List<Map<String, Object>> workAssignments = new ArrayList<>();
            boolean placed = true;
            for (WorkOrderPort.ScheduleOperation op : ops) {
                long processId = op.processId() == null ? 0L : op.processId();
                Long centerId = processCenter.get(processId);
                // 外协工序发出去做，不占厂内日产能，也不参与过载判断
                boolean outsource = centerId != null && "OUTSOURCE".equals(centerTypes.get(centerId));
                int capacity = centerId != null
                        ? effectiveCapacity.getOrDefault(centerId, 480)
                        : processCapacity.getOrDefault(processId, 480);
                String centerName = centerId == null ? null : centerNames.getOrDefault(centerId, "-");
                double minutes = decimal(op.standardMinutes()).doubleValue();
                if (minutes <= 0) {
                    minutes = work.planQty().doubleValue();
                }
                LocalDate day = cursor.toLocalDate().isBefore(start) ? start : cursor.toLocalDate();
                LocalDateTime startAt = null;
                LocalDateTime endAt = null;
                int guard = 0;
                while (true) {
                    if (day.isAfter(end)) {
                        placed = false;
                        break;
                    }
                    String key = outsource
                            ? day + "|O" + centerId
                            : day + "|" + (centerId == null ? "P" + processId : "C" + centerId);
                    double load = used.getOrDefault(key, 0.0);
                    LocalDateTime candidate = day.atTime(8, 0).plusMinutes((long) Math.floor(load));
                    if (cursor.toLocalDate().equals(day) && cursor.isAfter(candidate)) {
                        candidate = cursor;
                    }
                    LocalDateTime finish = candidate.plusMinutes((long) Math.ceil(minutes));
                    boolean fits = outsource || load + minutes <= capacity + 0.001;
                    boolean sameDay = finish.toLocalDate().equals(day);
                    if (fits && sameDay) {
                        startAt = candidate;
                        endAt = finish;
                        used.put(key, load + minutes);
                        break;
                    }
                    day = day.plusDays(1);
                    if (++guard > days) {
                        placed = false;
                        break;
                    }
                }
                if (!placed) {
                    break;
                }
                workOrderPort.scheduleOperation(op.id(), day, startAt, endAt);
                if (firstDay == null) {
                    firstDay = day;
                    firstStart = startAt;
                }
                lastEnd = endAt;
                cursor = endAt;
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("operationId", op.id());
                line.put("seq", op.seq());
                line.put("operationName", op.name());
                line.put("processName", processNames.getOrDefault(processId, "-"));
                line.put("workCenterId", centerId);
                line.put("workCenterName", centerName == null ? "-" : centerName);
                line.put("scheduleDate", day);
                line.put("startAt", startAt);
                line.put("endAt", endAt);
                line.put("minutes", Math.round(minutes));
                line.put("capacity", capacity);
                line.put("outsource", outsource);
                line.put("deviceCount", centerId == null ? 0 : deviceCount.getOrDefault(centerId, 0));
                workAssignments.add(line);
            }
            if (!placed) {
                workOrderPort.clearOperationSchedule(workId);
                skipped++;
                continue;
            }
            int seq = nextSeq.getOrDefault(firstDay, 0) + 10;
            nextSeq.put(firstDay, seq);
            workOrderPort.placeSchedule(workId, firstDay, seq, firstStart, lastEnd, "SCHEDULED");
            scheduled++;
            SalesOrderPort.SalesOrderBrief order = orderOf(work, orders);
            LocalDate deliveryDate = order == null ? null : order.deliveryDate();
            if (deliveryDate != null && lastEnd.toLocalDate().isAfter(deliveryDate)) {
                late++;
            }
            for (Map<String, Object> line : workAssignments) {
                line.put("workNo", work.workNo());
                line.put("productName", productNames.get(work.productId()));
                line.put("scheduleSeq", seq);
                assignments.add(line);
            }
        }

        List<Map<String, Object>> dailyLoad = new ArrayList<>();
        used.forEach((key, minutes) -> {
            String[] parts = key.split("\\|");
            String resource = parts[1];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", LocalDate.parse(parts[0]));
            if (resource.startsWith("C")) {
                long centerId = Long.parseLong(resource.substring(1));
                row.put("workCenterId", centerId);
                row.put("workCenterName", centerNames.getOrDefault(centerId, "-"));
                row.put("capacity", effectiveCapacity.getOrDefault(centerId, 480));
                row.put("deviceCount", deviceCount.getOrDefault(centerId, 0));
                row.put("outsource", false);
            } else if (resource.startsWith("O")) {
                // 外协负荷单独一行：看得见外协花了多少，但不占厂内产能
                long centerId = Long.parseLong(resource.substring(1));
                row.put("workCenterId", centerId);
                row.put("workCenterName", centerNames.getOrDefault(centerId, "-"));
                row.put("capacity", centerCapacity.getOrDefault(centerId, 480));
                row.put("deviceCount", deviceCount.getOrDefault(centerId, 0));
                row.put("outsource", true);
            } else {
                long processId = Long.parseLong(resource.substring(1));
                row.put("workCenterId", null);
                row.put("workCenterName", processNames.getOrDefault(processId, "-"));
                row.put("capacity", processCapacity.getOrDefault(processId, 480));
                row.put("deviceCount", 0);
                row.put("outsource", false);
            }
            row.put("minutes", Math.round(minutes));
            boolean outsourceRow = Boolean.TRUE.equals(row.get("outsource"));
            row.put("overloaded", !outsourceRow
                    && Math.round(minutes) > Integer.parseInt(String.valueOf(row.get("capacity"))));
            dailyLoad.add(row);
        });
        dailyLoad.sort(Comparator.comparing(row -> String.valueOf(row.get("date"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduledCount", scheduled);
        result.put("lateCount", late);
        result.put("skippedCount", skipped);
        result.put("days", days);
        result.put("assignments", assignments);
        result.put("dailyLoad", dailyLoad);
        return result;
    }

    /** 按「优先级 → 交期 → 创建时间 → id」排序，与一键排产、看板、有限产能保持一致 */
    private static List<WorkOrderPort.ScheduleOrder> sorted(
            List<WorkOrderPort.ScheduleOrder> works,
            Map<Long, SalesOrderPort.SalesOrderBrief> orders) {
        List<WorkOrderPort.ScheduleOrder> list = new ArrayList<>(works);
        list.sort(Comparator
                .comparingInt((WorkOrderPort.ScheduleOrder order) -> priorityRank(order.priority()))
                .thenComparing(order -> {
                    SalesOrderPort.SalesOrderBrief brief = orderOf(order, orders);
                    return brief == null ? null : brief.deliveryDate();
                }, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(order -> order.createdAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(WorkOrderPort.ScheduleOrder::id));
        return list;
    }

    private static int priorityRank(String priority) {
        return switch (String.valueOf(priority)) {
            case "RUSH" -> 0;
            case "URGENT" -> 1;
            default -> 2;
        };
    }

    /** 工单上的销售回链 → 订单概要；没挂订单的返回 null */
    private static SalesOrderPort.SalesOrderBrief orderOf(
            WorkOrderPort.ScheduleOrder order, Map<Long, SalesOrderPort.SalesOrderBrief> orders) {
        return order.salesOrderId() == null ? null : orders.get(order.salesOrderId());
    }

    private Map<Long, SalesOrderPort.SalesOrderBrief> briefOf(List<WorkOrderPort.ScheduleOrder> works) {
        List<Long> ids = works.stream()
                .map(WorkOrderPort.ScheduleOrder::salesOrderId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of() : salesOrderPort.briefs(ids);
    }

    private Map<Long, ProductInfo> productMap(List<Long> productIds) {
        List<Long> ids = productIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : masterData.products(ids);
    }

    private Map<Long, String> productNames(List<Long> productIds) {
        Map<Long, String> names = new HashMap<>();
        productMap(productIds).forEach((id, product) -> names.put(id, product.name()));
        return names;
    }

    private boolean isActiveScheduled(String status) {
        return "SCHEDULED".equals(status) || "RELEASED".equals(status) || "IN_PROGRESS".equals(status);
    }

    private Map<String, Object> result(long id, LocalDate date, int seq, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("scheduleDate", date);
        result.put("scheduleSeq", seq);
        result.put("status", status);
        return result;
    }

    private WorkOrderPort.ScheduleOrder loadWork(long id) {
        WorkOrderPort.ScheduleOrder work = workOrderPort.scheduleOrders(List.of(id)).get(id);
        if (work == null) {
            throw new BizException("生产工单不存在");
        }
        return work;
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        int number = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, number));
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
