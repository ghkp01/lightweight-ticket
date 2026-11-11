package com.mes.planning;

import com.mes.common.BizException;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.BomLineInfo;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.production.api.WorkOrderPort;
import com.mes.purchase.api.SupplyPort;
import com.mes.security.CurrentUser;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多层物料需求展开。
 *
 * 单阶 MRP 只算成品工单要买什么；这里继续往下展开半成品：
 * 半成品有 BOM 就按它的用量继续拆，拆到原料为止（层数可限，默认 3 层，防环）。
 * 展开结果分两段：
 * - 自制需求（make）：半成品要多少，扣掉现库存后的缺口就是「该开工单多少」；
 * - 采购需求（buy）：原料要多少，扣掉可用、在途、已申请未转，剩下的才是建议采购量。
 *
 * 只算与建议，不自动建单：半成品由计划确认后一键转工单，原料仍走采购申请。
 *
 * <p>毛需求问生产、在途与待转问采购、可用量问库存、物料档案与损耗率问 masterdata。</p>
 */
@Service
public class MultiLevelMrpService {

    private static final int DEFAULT_MAX_LEVEL = 3;
    private static final int MAX_LEVEL_LIMIT = 6;
    private static final int MAX_ROWS = 500;

    private final JdbcTemplate jdbcTemplate;
    private final InventoryPort inventory;
    private final WorkOrderPort workOrderPort;
    private final SupplyPort supply;
    private final MasterDataPort masterData;
    private final TaskPort taskService;

    public MultiLevelMrpService(JdbcTemplate jdbcTemplate, InventoryPort inventory,
                                WorkOrderPort workOrderPort, SupplyPort supply,
                                MasterDataPort masterData, TaskPort taskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventory = inventory;
        this.workOrderPort = workOrderPort;
        this.supply = supply;
        this.masterData = masterData;
        this.taskService = taskService;
    }

    public record ExplodeQuery(Boolean includeScheduled, Integer horizonDays, Integer maxLevel) {
    }

    @Transactional
    public Map<String, Object> explode(ExplodeQuery query) {
        boolean includeScheduled = query != null && Boolean.TRUE.equals(query.includeScheduled());
        Integer horizonDays = query == null ? null : query.horizonDays();
        int maxLevel = query == null || query.maxLevel() == null ? DEFAULT_MAX_LEVEL : query.maxLevel();
        if (maxLevel < 1 || maxLevel > MAX_LEVEL_LIMIT) {
            throw new BizException(1002, "展开层数应在 1 ~ " + MAX_LEVEL_LIMIT + " 之间");
        }

        List<String> statuses = new ArrayList<>(List.of("RELEASED", "IN_PROGRESS"));
        if (includeScheduled) {
            statuses.add("SCHEDULED");
        }

        // 第一层：工单 BOM 的待领量就是毛需求
        List<WorkOrderPort.MaterialDemand> first = workOrderPort.openMaterialDemand(statuses, horizonDays);
        closeMrpTasks(first);

        Map<Long, BigDecimal> gross = new LinkedHashMap<>();
        Map<Long, Integer> levelMap = new LinkedHashMap<>();
        Map<Long, LocalDate> dateMap = new LinkedHashMap<>();
        Map<Long, Set<String>> parentMap = new LinkedHashMap<>();
        for (WorkOrderPort.MaterialDemand demand : first) {
            long productId = demand.productId();
            gross.merge(productId, demand.grossNeed() == null ? BigDecimal.ZERO : demand.grossNeed(),
                    BigDecimal::add);
            levelMap.put(productId, 1);
            dateMap.put(productId, demand.firstNeedDate() == null
                    ? LocalDate.now() : demand.firstNeedDate());
            parentMap.computeIfAbsent(productId, key -> new LinkedHashSet<>()).add(demand.workOrders());
        }

        // 逐层净算：自制件先扣库存，再按净生产量展开 BOM；外购件只汇总
        Map<Long, BigDecimal> plannedMake = new LinkedHashMap<>();
        Map<Long, BigDecimal> processedGross = new LinkedHashMap<>();
        Map<Long, List<BomLineInfo>> bomCache = new LinkedHashMap<>();
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 2000) {
            changed = false;
            for (Long productId : new ArrayList<>(gross.keySet())) {
                ProductInfo product = masterData.product(productId).orElse(null);
                if (product == null || !product.make()) {
                    continue;
                }
                BigDecimal grossNow = gross.get(productId);
                BigDecimal previous = processedGross.getOrDefault(productId, BigDecimal.ZERO);
                if (grossNow.compareTo(previous) <= 0) {
                    continue;
                }
                processedGross.put(productId, grossNow);
                BigDecimal stock = stockOf(productId);
                BigDecimal netTotal = grossNow.subtract(stock).max(BigDecimal.ZERO);
                BigDecimal already = plannedMake.getOrDefault(productId, BigDecimal.ZERO);
                BigDecimal delta = netTotal.subtract(already);
                if (delta.signum() <= 0) {
                    continue;
                }
                plannedMake.put(productId, netTotal);
                int level = levelMap.getOrDefault(productId, 1);
                if (level >= maxLevel) {
                    continue;
                }
                List<BomLineInfo> lines = bomCache.computeIfAbsent(productId,
                        key -> masterData.activeBomLines(key));
                if (lines.isEmpty()) {
                    continue;
                }
                BigDecimal loss = product.lossRate() == null ? BigDecimal.ZERO : product.lossRate();
                BigDecimal factor = BigDecimal.ONE.add(loss);
                String parentLabel = product.code() + "（投产 " + plain(delta) + "）";
                LocalDate parentDate = dateMap.getOrDefault(productId, LocalDate.now());
                LocalDate childDate = backDate(parentDate, product.leadDays());
                for (BomLineInfo line : lines) {
                    if (line.substituteMaterialId() != null) {
                        continue;
                    }
                    BigDecimal need = line.qtyPerUnit().multiply(delta).multiply(factor)
                            .setScale(4, RoundingMode.HALF_UP);
                    gross.merge(line.materialId(), need, BigDecimal::add);
                    levelMap.merge(line.materialId(), level + 1, Math::max);
                    dateMap.merge(line.materialId(), childDate,
                            (left, right) -> left.isBefore(right) ? left : right);
                    parentMap.computeIfAbsent(line.materialId(), key -> new LinkedHashSet<>())
                            .add(parentLabel);
                    changed = true;
                }
            }
        }

        return summarize(gross, levelMap, dateMap, parentMap, plannedMake, maxLevel);
    }

    private Map<String, Object> summarize(Map<Long, BigDecimal> gross, Map<Long, Integer> levelMap,
                                          Map<Long, LocalDate> dateMap,
                                          Map<Long, Set<String>> parentMap,
                                          Map<Long, BigDecimal> plannedMake, int maxLevel) {
        List<Long> ids = new ArrayList<>(gross.keySet());
        Map<Long, ProductInfo> products = ids.isEmpty() ? Map.of() : masterData.products(ids);
        Map<Long, BigDecimal> available = new LinkedHashMap<>();
        Map<Long, BigDecimal> onWay = new LinkedHashMap<>();
        Map<Long, BigDecimal> pending = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            available.putAll(inventory.availableStocks(ids));
            onWay.putAll(supply.onWayQty(ids));
            pending.putAll(supply.pendingRequisitionQty(ids));
        }

        List<Map<String, Object>> make = new ArrayList<>();
        List<Map<String, Object>> buy = new ArrayList<>();
        for (Long productId : ids) {
            ProductInfo product = products.get(productId);
            if (product == null) {
                continue;
            }
            BigDecimal grossNeed = gross.getOrDefault(productId, BigDecimal.ZERO)
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal stock = available.getOrDefault(productId, BigDecimal.ZERO);
            LocalDate needDate = dateMap.getOrDefault(productId, LocalDate.now());
            LocalDate suggestDate = backDate(needDate, product.leadDays());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", productId);
            row.put("productCode", product.code());
            row.put("productName", product.name());
            row.put("unit", product.unit());
            row.put("category", product.category());
            row.put("source", product.make() ? "MAKE" : "BUY");
            row.put("level", levelMap.getOrDefault(productId, 1));
            row.put("grossNeed", grossNeed);
            row.put("stock", stock);
            row.put("needDate", String.valueOf(needDate));
            row.put("leadDays", product.leadDays());
            row.put("parents", String.join("、", parentMap.getOrDefault(productId, Set.of())));
            if (product.make()) {
                BigDecimal shortage = plannedMake.getOrDefault(productId,
                        grossNeed.subtract(stock).max(BigDecimal.ZERO));
                row.put("shortage", shortage.setScale(4, RoundingMode.HALF_UP));
                row.put("suggestStart", String.valueOf(suggestDate));
                make.add(row);
            } else {
                BigDecimal transit = onWay.getOrDefault(productId, BigDecimal.ZERO);
                BigDecimal applied = pending.getOrDefault(productId, BigDecimal.ZERO);
                row.put("onWay", transit);
                row.put("pending", applied);
                row.put("shortage", grossNeed.subtract(stock).subtract(transit).subtract(applied)
                        .max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP));
                row.put("suggestOrderDate", String.valueOf(suggestDate));
                buy.add(row);
            }
        }
        make.sort(Comparator.comparingInt((Map<String, Object> row) -> ((Number) row.get("level")).intValue())
                .thenComparing(row -> String.valueOf(row.get("productCode"))));
        buy.sort(Comparator.comparing(row -> String.valueOf(row.get("productCode"))));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("maxLevel", maxLevel);
        summary.put("demandRows", ids.size());
        summary.put("makeRows", make.size());
        summary.put("buyRows", buy.size());
        summary.put("makeShortageRows", make.stream()
                .filter(row -> decimal(row.get("shortage")).signum() > 0).count());
        summary.put("buyShortageRows", buy.stream()
                .filter(row -> decimal(row.get("shortage")).signum() > 0).count());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("make", make.size() > MAX_ROWS ? make.subList(0, MAX_ROWS) : make);
        result.put("buy", buy.size() > MAX_ROWS ? buy.subList(0, MAX_ROWS) : buy);
        return result;
    }

    private BigDecimal stockOf(long productId) {
        Map<Long, BigDecimal> stocks = inventory.availableStocks(List.of(productId));
        return stocks.getOrDefault(productId, BigDecimal.ZERO);
    }

    private LocalDate backDate(LocalDate needDate, int leadDays) {
        if (leadDays <= 0) {
            return needDate;
        }
        LocalDate date = needDate.minusDays(leadDays);
        LocalDate today = LocalDate.now();
        return date.isBefore(today) ? today : date;
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    /**
     * 收口算料任务：跟单阶 MRP 完全一致——只关被本轮工单覆盖到的销售订单。
     * 工单在多个物料行里会重复出现，这里先去重再让生产侧按工单号查订单归属。
     */
    private void closeMrpTasks(List<WorkOrderPort.MaterialDemand> demands) {
        Set<String> workNos = new LinkedHashSet<>();
        for (WorkOrderPort.MaterialDemand demand : demands) {
            String text = demand.workOrders() == null ? "" : demand.workOrders();
            for (String no : text.split("、")) {
                if (!no.isBlank()) {
                    workNos.add(no.trim());
                }
            }
        }
        if (workNos.isEmpty()) {
            return;
        }
        Set<Long> orderIds = workOrderPort.salesOrderIdsOfWorkNos(workNos);
        if (orderIds.isEmpty()) {
            return;
        }
        taskService.closeByRef(TaskTypes.MRP_RUN, TaskTypes.REF_SALES_ORDER, orderIds,
                CurrentUser.name(), "已完成物料需求计算（多层展开）");
    }
}
