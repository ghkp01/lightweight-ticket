package com.mes.planning;

import com.mes.common.BizException;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.BomLineInfo;
import com.mes.masterdata.api.CustomerInfo;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.production.api.WorkOrderPort;
import com.mes.purchase.api.RequisitionCreated;
import com.mes.purchase.api.RequisitionDraft;
import com.mes.purchase.api.RequisitionLine;
import com.mes.purchase.api.RequisitionPort;
import com.mes.purchase.api.SupplyPort;
import com.mes.sales.api.SalesOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 销售订单 MRP：从客户需求出发，逐层净算，最后生成工单与采购申请。
 *
 * <p>与「按工单算料」的区别：这里的第一层需求是销售订单，不是已有工单。
 * 成品先扣现有库存得到净生产量；半成品同样先扣库存再展开它的 BOM；
 * 外购件跨层汇总后统一扣库存，避免同一物料重复下单。</p>
 *
 * <p>自制/外购的判断只看物料主数据的 {@code source}，不看 BOM。
 * 提前期用于倒排建议开工日与建议下单日。</p>
 *
 * <p><b>库存占用（ATP）</b>：成品库存按交期先到先得，分配给订单的部分写成
 * {@code sales_stock_reservation} 的 ACTIVE 预留；其它订单只能用
 * 「可用库存 - 其它订单的 ACTIVE 预留」。全局 MRP 一次处理所有已确认且未生成工单的订单，
 * 避免多张订单重复占用同一批库存。</p>
 *
 * <p><b>组件跨轮次占用</b>：已经有在制工单（含 DRAFT）的未领材料需求会从可用库存中扣掉，
 * 相当于上一轮 MRP 已经占用了这些组件。否则先跑订单 A、再跑订单 B 时，
 * B 会把 A 已经用掉的组件库存再用一次，导致少采购。</p>
 *
 * <p><b>安全库存</b>：按库存底线保留，不参与销售订单消耗。库存低于安全库存时，
 * 这里不会在每张订单上重复补一遍，补库由「工单缺料」页按净需求口径统一处理。</p>
 */
@Service
public class SalesMrpService {

    private static final int DEFAULT_MAX_LEVEL = 6;
    private static final int MAX_LEVEL_LIMIT = 8;
    private static final Set<String> CLOSED_ORDER_STATUSES = Set.of("CANCELLED", "SHIPPED", "COMPLETED");

    private final SalesOrderPort salesOrders;
    private final MasterDataPort masterData;
    private final InventoryPort inventory;
    private final SupplyPort supply;
    private final WorkOrderPort workOrders;
    private final RequisitionPort requisitions;
    private final JdbcTemplate jdbcTemplate;

    public SalesMrpService(SalesOrderPort salesOrders, MasterDataPort masterData,
                           InventoryPort inventory, SupplyPort supply,
                           WorkOrderPort workOrders, RequisitionPort requisitions,
                           JdbcTemplate jdbcTemplate) {
        this.salesOrders = salesOrders;
        this.masterData = masterData;
        this.inventory = inventory;
        this.supply = supply;
        this.workOrders = workOrders;
        this.requisitions = requisitions;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record MakeRow(long productId, String productCode, String productName, String unit,
                          String source, int level, BigDecimal grossNeed, BigDecimal stock,
                          BigDecimal committed, BigDecimal safetyStock, BigDecimal netNeed,
                          LocalDate needDate, LocalDate suggestStart, int leadDays, String parents) {
    }

    public record BuyRow(long productId, String productCode, String productName, String unit,
                         String source, int level, BigDecimal grossNeed, BigDecimal stock,
                         BigDecimal committed, BigDecimal safetyStock, BigDecimal onWay,
                         BigDecimal pending, BigDecimal netNeed, LocalDate needDate,
                         LocalDate suggestOrderDate, int leadDays, String parents) {
    }

    /** 一次 MRP 的订单范围：订单头 + 本次用的交期 */
    private record DemandOrder(SalesOrderPort.SalesOrderRef order, LocalDate deliveryDate) {
    }

    /** 订单明细的库存分配结果 */
    private record ItemPlan(SalesOrderPort.OrderItemRef item, ProductInfo product,
                            BigDecimal demand, BigDecimal available, BigDecimal reserved,
                            BigDecimal netNeed) {
    }

    private record OrderPlan(SalesOrderPort.SalesOrderRef order, LocalDate deliveryDate,
                             List<ItemPlan> items) {

        BigDecimal demandQty() {
            return items.stream().map(ItemPlan::demand).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal reservedQty() {
            return items.stream().map(ItemPlan::reserved).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal netQty() {
            return items.stream().map(ItemPlan::netNeed).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        String status() {
            return netQty().signum() > 0 ? "IN_PRODUCTION" : "READY_TO_SHIP";
        }
    }

    /** 逐层展开后的中间结果 */
    private static final class Plan {
        final Map<Long, BigDecimal> gross = new LinkedHashMap<>();
        final Map<Long, Integer> levels = new HashMap<>();
        final Map<Long, LocalDate> dates = new HashMap<>();
        final Map<Long, Set<String>> parents = new HashMap<>();
        final Set<Long> seedNetted = new HashSet<>();
        final Map<Long, BigDecimal> processedGross = new HashMap<>();
        final Map<Long, BigDecimal> plannedMake = new HashMap<>();
        final Map<Long, BigDecimal> committed = new HashMap<>();
        final Map<Long, Set<Long>> childParents = new HashMap<>();
        final Map<Long, ProductInfo> products = new LinkedHashMap<>();
        final Map<Long, List<BomLineInfo>> bomCache = new HashMap<>();
        /** 展开过程中发现缺少生效 BOM 的自制件：productId → 展示文案 */
        final Map<Long, String> missingBom = new LinkedHashMap<>();
        List<MakeRow> makeRows = List.of();
        List<BuyRow> buyRows = List.of();
    }

    private record RunResult(List<OrderPlan> orderPlans, Plan plan,
                             List<Map<String, Object>> workOrders,
                             List<Map<String, Object>> requisitions,
                             List<Map<String, Object>> bomPending) {
    }

    /** 库存分配结果：可执行的订单计划 + 因缺少生效 BOM 被跳过的订单 */
    private record Allocation(List<OrderPlan> plans, List<Map<String, Object>> bomPending) {
    }

    /**
     * 为一张已确认销售订单跑 MRP，并直接生成成品/半成品工单与采购申请。
     *
     * @param maxLevel 展开层数，默认 6
     */
    @Transactional
    public Map<String, Object> run(long salesOrderId, Integer maxLevel) {
        int limit = maxLevel(maxLevel);
        SalesOrderPort.SalesOrderRef order = salesOrders.order(salesOrderId);
        if (order == null) {
            throw new BizException(2002, "销售订单不存在");
        }
        if (!"CONFIRMED".equals(order.status())) {
            throw new BizException(2001, "只有已确认的销售订单可以运行 MRP，当前状态：" + order.status());
        }
        if (workOrders.existsForSalesOrder(salesOrderId)) {
            throw new BizException(2004, "该订单已经生成过生产工单，请勿重复运行 MRP");
        }

        SalesOrderPort.SalesOrderBrief brief = salesOrders.briefs(List.of(salesOrderId)).get(salesOrderId);
        LocalDate deliveryDate = brief == null || brief.deliveryDate() == null
                ? LocalDate.now().plusDays(7) : brief.deliveryDate();
        List<SalesOrderPort.OrderItemRef> activeItems = salesOrders.items(salesOrderId).stream()
                .filter(item -> !item.cancelled())
                .filter(item -> openQty(item).signum() > 0)
                .toList();
        if (activeItems.isEmpty()) {
            throw new BizException(2002, "订单没有可执行的有效明细");
        }
        // BOM 门槛：自制件没有生效 BOM 时先不跑，避免按空 BOM 建出无料的工单
        Map<Long, ProductInfo> seedProducts = masterData.products(activeItems.stream()
                .map(SalesOrderPort.OrderItemRef::productId).distinct().toList());
        Map<Long, Long> bomCache = new HashMap<>();
        List<String> missingBom = activeItems.stream()
                .map(item -> seedProducts.get(item.productId()))
                .filter(product -> product != null && product.make()
                        && activeBomIdCached(bomCache, product.id()) == null)
                .map(ProductInfo::code)
                .distinct()
                .toList();
        if (!missingBom.isEmpty()) {
            throw new BizException(2005, "订单还有产品未完成 BOM："
                    + String.join("、", missingBom) + "，请先派 BOM 任务并让 BOM 生效");
        }

        String context = "销售订单 " + order.orderNo() + " MRP";
        RunResult run = execute(List.of(new DemandOrder(order, deliveryDate)), limit, context, true);
        if (run.orderPlans().isEmpty()) {
            throw new BizException(2005, "订单 BOM 未就绪，请先完成 BOM 任务");
        }
        OrderPlan orderPlan = run.orderPlans().get(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("salesOrderId", salesOrderId);
        result.put("orderNo", order.orderNo());
        result.put("deliveryDate", String.valueOf(deliveryDate));
        result.put("maxLevel", limit);
        result.put("status", orderPlan.status());
        result.put("reservedQty", orderPlan.reservedQty());
        result.put("finishedRows", finishedRows(run.orderPlans()));
        result.put("make", makeView(run.plan().makeRows));
        result.put("buy", buyView(run.plan().buyRows));
        result.put("workOrders", run.workOrders());
        result.put("requisitions", run.requisitions());
        result.put("bomPending", run.bomPending());
        result.put("createdWorkOrders", run.workOrders().size());
        result.put("createdRequisitions", run.requisitions().size());
        return result;
    }

    /**
     * 全局 MRP：一次处理所有已确认、还没生成工单的销售订单。
     *
     * <p>成品库存按交期先到先得分配并写 ACTIVE 预留，净生产量按物料汇总后只展开一次 BOM，
     * 因此半成品工单与采购申请都是全局各一张，不会因为订单多而重复扣库存、重复下单。</p>
     */
    @Transactional
    public Map<String, Object> runGlobal(Integer maxLevel) {
        int limit = maxLevel(maxLevel);
        reconcileReservations();

        List<SalesOrderPort.SalesOrderRef> openOrders = salesOrders.openForMrp();
        if (openOrders.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("maxLevel", limit);
            result.put("orderCount", 0);
            result.put("orders", List.of());
            result.put("reservedQty", BigDecimal.ZERO);
            result.put("finishedRows", List.of());
            result.put("make", List.of());
            result.put("buy", List.of());
            result.put("workOrders", List.of());
            result.put("requisitions", List.of());
            result.put("bomPending", List.of());
            result.put("createdWorkOrders", 0);
            result.put("createdRequisitions", 0);
            return result;
        }

        List<Long> orderIds = openOrders.stream().map(SalesOrderPort.SalesOrderRef::id).toList();
        Map<Long, SalesOrderPort.SalesOrderBrief> briefs = salesOrders.briefs(orderIds);
        List<DemandOrder> scope = openOrders.stream()
                .map(order -> {
                    SalesOrderPort.SalesOrderBrief brief = briefs.get(order.id());
                    LocalDate deliveryDate = brief == null || brief.deliveryDate() == null
                            ? LocalDate.now().plusDays(7) : brief.deliveryDate();
                    return new DemandOrder(order, deliveryDate);
                })
                .sorted(Comparator.comparing(DemandOrder::deliveryDate)
                        .thenComparing(demand -> demand.order().id()))
                .toList();

        String context = "全局 MRP（" + (openOrders.size() == 1
                ? "销售订单 " + openOrders.get(0).orderNo()
                : openOrders.size() + " 张销售订单") + "）";
        // 全局场景不再把上一轮生成的采购在途/采购申请当作本轮的供给：
        // 那些供给已经属于旧订单，跨轮次相减会让新订单少买。
        RunResult run = execute(scope, limit, context, false);

        List<Map<String, Object>> orderRows = new ArrayList<>();
        BigDecimal reservedTotal = BigDecimal.ZERO;
        for (OrderPlan orderPlan : run.orderPlans()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("salesOrderId", orderPlan.order().id());
            row.put("orderNo", orderPlan.order().orderNo());
            row.put("deliveryDate", String.valueOf(orderPlan.deliveryDate()));
            row.put("status", orderPlan.status());
            row.put("demandQty", orderPlan.demandQty());
            row.put("reservedQty", orderPlan.reservedQty());
            row.put("netQty", orderPlan.netQty());
            orderRows.add(row);
            reservedTotal = reservedTotal.add(orderPlan.reservedQty());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxLevel", limit);
        result.put("orderCount", run.orderPlans().size());
        result.put("orders", orderRows);
        result.put("reservedQty", reservedTotal);
        result.put("finishedRows", finishedRows(run.orderPlans()));
        result.put("make", makeView(run.plan().makeRows));
        result.put("buy", buyView(run.plan().buyRows));
        result.put("workOrders", run.workOrders());
        result.put("requisitions", run.requisitions());
        result.put("bomPending", run.bomPending());
        result.put("createdWorkOrders", run.workOrders().size());
        result.put("createdRequisitions", run.requisitions().size());
        return result;
    }

    /**
     * 全局 MRP 的运行范围（页面展示用）：已确认、还没生成工单的订单，
     * 带上客户名称与已经占用的成品库存。
     */
    public List<Map<String, Object>> openOrders() {
        List<SalesOrderPort.SalesOrderRef> orders = salesOrders.openForMrp();
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = orders.stream().map(SalesOrderPort.SalesOrderRef::id).toList();
        Map<Long, SalesOrderPort.SalesOrderBrief> briefs = salesOrders.briefs(orderIds);
        List<Long> customerIds = orders.stream()
                .map(SalesOrderPort.SalesOrderRef::customerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, CustomerInfo> customers = customerIds.isEmpty()
                ? Map.of() : masterData.customers(customerIds);
        Map<Long, BigDecimal> reserved = reservedByOrder(orderIds);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SalesOrderPort.SalesOrderRef order : orders) {
            SalesOrderPort.SalesOrderBrief brief = briefs.get(order.id());
            CustomerInfo customer = order.customerId() == null ? null : customers.get(order.customerId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", order.id());
            row.put("orderNo", order.orderNo());
            row.put("customerName", customer == null ? null : customer.name());
            row.put("deliveryDate", brief == null || brief.deliveryDate() == null
                    ? null : String.valueOf(brief.deliveryDate()));
            row.put("reservedQty", reserved.getOrDefault(order.id(), BigDecimal.ZERO));
            rows.add(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- 执行：分配库存、建单、展开

    private RunResult execute(List<DemandOrder> scope, int limit, String context, boolean includeOpenSupply) {
        Map<Long, BigDecimal> committed = committedMaterialDemand();
        Allocation allocation = allocate(scope, committed);
        List<OrderPlan> orderPlans = allocation.plans();

        // 先把 BOM 完整展开并校验，再建单；任何一层缺 BOM 都整体中止，避免半成品工单无料可领
        Plan plan = plan(orderPlans, limit, includeOpenSupply, committed);
        if (!plan.missingBom.isEmpty()) {
            throw new BizException(2005, "以下自制件缺少生效 BOM，MRP 已中止："
                    + String.join("；", plan.missingBom.values())
                    + "。请先完成 BOM 审批后重跑");
        }

        List<Map<String, Object>> createdWorks = new ArrayList<>();
        for (OrderPlan orderPlan : orderPlans) {
            for (ItemPlan item : orderPlan.items()) {
                // 成品按「订单明细」建工单，保证销售订单能回链完成进度
                if (item.netNeed().signum() <= 0 || !item.product().make()) {
                    continue;
                }
                WorkOrderPort.CreatedWorkOrder created = workOrders.createFromSalesOrder(
                        orderPlan.order().id(), item.item().id(), item.product().id(),
                        item.netNeed(), "NORMAL", orderPlan.deliveryDate());
                createdWorks.add(workView(created, item.product(), item.netNeed(), "FINISHED"));
            }
        }

        for (MakeRow row : plan.makeRows) {
            // 半成品按物料全局各一张，避免两张订单各建一张相同半成品工单
            if (row.level() == 0 || row.netNeed().signum() <= 0) {
                continue;
            }
            WorkOrderPort.CreatedWorkOrder created = workOrders.createPlanned(row.productId(),
                    row.netNeed(), row.suggestStart(), row.needDate(), "NORMAL", context);
            createdWorks.add(workView(created, plan.products.get(row.productId()), row.netNeed(), "SEMI"));
        }
        // 按 BOM 父子关系把半成品工单挂到成品/上层半成品工单前面：前置完工后才能下达后置
        linkWorkOrderDependencies(plan, createdWorks);

        List<Map<String, Object>> createdRequisitions = new ArrayList<>();
        for (BuyRow row : plan.buyRows) {
            if (row.netNeed().signum() <= 0) {
                continue;
            }
            ProductInfo product = plan.products.get(row.productId());
            LocalDate needDate = row.needDate().isBefore(LocalDate.now())
                    ? LocalDate.now() : row.needDate();
            RequisitionCreated created = requisitions.create(new RequisitionDraft(
                    "PRODUCTION", "计划", null, needDate,
                    context,
                    "MRP 自动生成，建议下单日 " + row.suggestOrderDate(),
                    List.of(new RequisitionLine(row.productId(), row.netNeed(), null,
                            product == null ? null : product.defaultSupplierId(), false,
                            "由" + context + "生成")),
                    "MATERIAL", false, null));
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", created.id());
            req.put("reqNo", created.reqNo());
            req.put("productId", row.productId());
            req.put("productCode", row.productCode());
            req.put("productName", row.productName());
            req.put("qty", row.netNeed());
            createdRequisitions.add(req);
        }

        for (OrderPlan orderPlan : orderPlans) {
            salesOrders.markInProduction(orderPlan.order().id());
            if (orderPlan.netQty().signum() <= 0) {
                // 成品库存已完全覆盖：不需要生产，直接待发货
                salesOrders.markReadyToShip(orderPlan.order().id());
            }
        }
        return new RunResult(orderPlans, plan, createdWorks, createdRequisitions,
                allocation.bomPending());
    }

    /**
     * 用 BOM 父子边把本轮创建的工单连成依赖：子件工单（半成品）是前置，
     * 父件工单（成品/上层半成品）是后置。同一物料有多张工单时全部互连，
     * 保证任何一张成品工单都不会在它需要的半成品完工前下达。
     */
    private void linkWorkOrderDependencies(Plan plan, List<Map<String, Object>> createdWorks) {
        if (plan.childParents.isEmpty() || createdWorks.isEmpty()) {
            return;
        }
        Map<Long, List<Long>> idsByProduct = new HashMap<>();
        for (Map<String, Object> work : createdWorks) {
            Object productId = work.get("productId");
            Object workOrderId = work.get("id");
            if (productId instanceof Number && workOrderId instanceof Number) {
                idsByProduct.computeIfAbsent(((Number) productId).longValue(), key -> new ArrayList<>())
                        .add(((Number) workOrderId).longValue());
            }
        }
        List<WorkOrderPort.WorkOrderDependency> dependencies = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> entry : plan.childParents.entrySet()) {
            List<Long> predecessors = idsByProduct.get(entry.getKey());
            if (predecessors == null || predecessors.isEmpty()) {
                continue;
            }
            for (Long parentProductId : entry.getValue()) {
                List<Long> successors = idsByProduct.get(parentProductId);
                if (successors == null || successors.isEmpty()) {
                    continue;
                }
                for (Long predecessor : predecessors) {
                    for (Long successor : successors) {
                        if (!predecessor.equals(successor)) {
                            dependencies.add(new WorkOrderPort.WorkOrderDependency(predecessor, successor));
                        }
                    }
                }
            }
        }
        if (!dependencies.isEmpty()) {
            workOrders.linkDependencies(dependencies);
        }
    }

    /**
     * 给本次订单分配成品库存：ATP = 可用库存 - 其它订单的 ACTIVE 预留 - 已有在制工单的未领需求。
     *
     * <p>本次涉及的订单先释放旧预留再重新分配，保证交期顺序变化或数量变化时预留能跟着收敛。
     * 自制件缺少生效 BOM 的订单整体跳过，返回在 {@code bomPending} 里，等 BOM 完成后再算，
     * 避免按空 BOM 建出无料工单、也避免把订单误推进到生产中。</p>
     */
    private Allocation allocate(List<DemandOrder> scope, Map<Long, BigDecimal> committed) {
        Map<Long, List<SalesOrderPort.OrderItemRef>> itemsByOrder = new LinkedHashMap<>();
        Set<Long> productIds = new LinkedHashSet<>();
        for (DemandOrder demand : scope) {
            List<SalesOrderPort.OrderItemRef> items = salesOrders.items(demand.order().id()).stream()
                    .filter(item -> !item.cancelled())
                    .filter(item -> openQty(item).signum() > 0)
                    .sorted(Comparator.comparingLong(SalesOrderPort.OrderItemRef::id))
                    .toList();
            itemsByOrder.put(demand.order().id(), items);
            items.forEach(item -> productIds.add(item.productId()));
        }

        Map<Long, ProductInfo> products = productIds.isEmpty() ? Map.of() : masterData.products(productIds);
        for (DemandOrder demand : scope) {
            for (SalesOrderPort.OrderItemRef item : itemsByOrder.get(demand.order().id())) {
                if (!products.containsKey(item.productId())) {
                    throw new BizException(1002, "订单 " + demand.order().orderNo()
                            + " 的明细物料不存在：" + item.productId());
                }
            }
        }

        List<DemandOrder> readyDemands = new ArrayList<>();
        List<Map<String, Object>> bomPending = new ArrayList<>();
        Map<Long, Long> bomCache = new HashMap<>();
        for (DemandOrder demand : scope) {
            List<String> missing = itemsByOrder.get(demand.order().id()).stream()
                    .map(item -> products.get(item.productId()))
                    .filter(product -> product != null && product.make()
                            && activeBomIdCached(bomCache, product.id()) == null)
                    .map(ProductInfo::code)
                    .distinct()
                    .toList();
            if (missing.isEmpty()) {
                readyDemands.add(demand);
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("salesOrderId", demand.order().id());
                row.put("orderNo", demand.order().orderNo());
                row.put("productCodes", String.join("、", missing));
                bomPending.add(row);
            }
        }

        if (readyDemands.isEmpty()) {
            return new Allocation(List.of(), bomPending);
        }

        releaseActiveOfOrders(readyDemands.stream().map(demand -> demand.order().id()).toList());
        Map<Long, BigDecimal> reservedByOthers = reservedByProduct(productIds);
        Map<Long, BigDecimal> stocks = productIds.isEmpty() ? Map.of() : inventory.availableStocks(productIds);
        Map<Long, BigDecimal> atp = new HashMap<>();
        for (Long productId : productIds) {
            ProductInfo product = products.get(productId);
            // 已有在制工单的未领需求也会占用成品库存（同一物料既可能被卖、也可能被别的工单当组件领用）
            BigDecimal available = stocks.getOrDefault(productId, BigDecimal.ZERO)
                    .subtract(reservedByOthers.getOrDefault(productId, BigDecimal.ZERO))
                    .subtract(committed.getOrDefault(productId, BigDecimal.ZERO))
                    .subtract(safetyOf(product));
            atp.put(productId, available.max(BigDecimal.ZERO));
        }

        List<OrderPlan> plans = new ArrayList<>();
        for (DemandOrder demand : readyDemands) {
            List<ItemPlan> itemPlans = new ArrayList<>();
            for (SalesOrderPort.OrderItemRef item : itemsByOrder.get(demand.order().id())) {
                long productId = item.productId();
                ProductInfo product = products.get(productId);
                BigDecimal need = openQty(item);
                BigDecimal available = atp.getOrDefault(productId, BigDecimal.ZERO);
                BigDecimal allocated = available.min(need);
                atp.put(productId, available.subtract(allocated));
                BigDecimal netNeed = need.subtract(allocated).max(BigDecimal.ZERO);
                if (allocated.signum() > 0) {
                    insertReservation(demand.order().id(), item.id(), productId, allocated);
                }
                itemPlans.add(new ItemPlan(item, product, need, available, allocated, netNeed));
            }
            plans.add(new OrderPlan(demand.order(), demand.deliveryDate(), itemPlans));
        }
        return new Allocation(plans, bomPending);
    }

    /** 构造净需求并按 BOM 逐层展开，最后汇总成 make / buy 两套行 */
    private Plan plan(List<OrderPlan> orderPlans, int limit, boolean includeOpenSupply,
                      Map<Long, BigDecimal> committed) {
        Plan plan = new Plan();
        plan.committed.putAll(committed);
        for (OrderPlan orderPlan : orderPlans) {
            for (ItemPlan item : orderPlan.items()) {
                if (item.netNeed().signum() <= 0) {
                    continue;
                }
                long productId = item.product().id();
                plan.products.putIfAbsent(productId, item.product());
                plan.gross.merge(productId, item.netNeed(), BigDecimal::add);
                plan.levels.putIfAbsent(productId, 0);
                plan.dates.merge(productId, orderPlan.deliveryDate(),
                        (left, right) -> left.isBefore(right) ? left : right);
                plan.parents.computeIfAbsent(productId, key -> new LinkedHashSet<>())
                        .add("销售订单 " + orderPlan.order().orderNo());
                // 成品库存已在上面的 ATP 分配里扣过，展开时不能再扣一次
                plan.seedNetted.add(productId);
            }
        }
        explodeBom(plan, limit);
        summarize(plan, includeOpenSupply);
        return plan;
    }

    private void explodeBom(Plan plan, int limit) {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 2000) {
            changed = false;
            for (Long productId : new ArrayList<>(plan.gross.keySet())) {
                ProductInfo product = productOf(plan, productId);
                if (product == null || !product.make()) {
                    continue;
                }
                BigDecimal grossNow = plan.gross.get(productId);
                BigDecimal previous = plan.processedGross.getOrDefault(productId, BigDecimal.ZERO);
                if (grossNow.compareTo(previous) <= 0) {
                    continue;
                }
                plan.processedGross.put(productId, grossNow);
                BigDecimal stock = stockOf(plan, productId);
                BigDecimal netTotal = plan.seedNetted.contains(productId)
                        ? grossNow : grossNow.subtract(stock).max(BigDecimal.ZERO);
                BigDecimal already = plan.plannedMake.getOrDefault(productId, BigDecimal.ZERO);
                BigDecimal delta = netTotal.subtract(already);
                if (delta.signum() <= 0) {
                    continue;
                }
                plan.plannedMake.put(productId, netTotal);
                int level = plan.levels.getOrDefault(productId, 0);
                if (level >= limit) {
                    continue;
                }
                List<BomLineInfo> lines = plan.bomCache.computeIfAbsent(productId,
                        key -> masterData.activeBomLines(key));
                if (lines.isEmpty()) {
                    // 自制件没有生效 BOM：记录下来并中止本次 MRP，不能静默漏算下层物料
                    String parents = String.join("、",
                            plan.parents.getOrDefault(productId, Set.of()));
                    plan.missingBom.putIfAbsent(productId, product.code() + " " + product.name()
                            + "（第 " + (level + 1) + " 层"
                            + (parents.isBlank() ? "" : "，父件：" + parents) + "）");
                    continue;
                }
                BigDecimal loss = product.lossRate() == null ? BigDecimal.ZERO : product.lossRate();
                BigDecimal factor = BigDecimal.ONE.add(loss);
                String parentLabel = product.code() + "（投产 " + plain(delta) + "）";
                LocalDate parentDate = plan.dates.getOrDefault(productId, LocalDate.now());
                // 子件需求日 = 父件开工日（父件需求日往前推父件提前期）
                LocalDate childDate = backDate(parentDate, product.leadDays());
                for (BomLineInfo line : lines) {
                    // 替代料不参与 MRP 净算
                    if (line.substituteMaterialId() != null) {
                        continue;
                    }
                    BigDecimal need = line.qtyPerUnit().multiply(delta).multiply(factor)
                            .setScale(4, RoundingMode.HALF_UP);
                    plan.gross.merge(line.materialId(), need, BigDecimal::add);
                    plan.levels.merge(line.materialId(), level + 1, Math::max);
                    // 记录 BOM 父子边，建完工单后据此生成前置/后置依赖
                    plan.childParents.computeIfAbsent(line.materialId(), key -> new LinkedHashSet<>())
                            .add(productId);
                    plan.dates.merge(line.materialId(), childDate,
                            (left, right) -> left.isBefore(right) ? left : right);
                    plan.parents.computeIfAbsent(line.materialId(), key -> new LinkedHashSet<>())
                            .add(parentLabel);
                    changed = true;
                }
            }
        }
    }

    /** 汇总：自制件看净生产量，外购件汇总后统一扣库存（全局场景不再扣跨轮次的在途/已申请） */
    private void summarize(Plan plan, boolean includeOpenSupply) {
        List<Long> ids = new ArrayList<>(plan.gross.keySet());
        Map<Long, BigDecimal> stocks = inventory.availableStocks(ids);
        Map<Long, BigDecimal> onWay = includeOpenSupply ? supply.onWayQty(ids) : Map.of();
        Map<Long, BigDecimal> pending = includeOpenSupply ? supply.pendingRequisitionQty(ids) : Map.of();

        List<MakeRow> makeRows = new ArrayList<>();
        List<BuyRow> buyRows = new ArrayList<>();
        for (Long productId : ids) {
            ProductInfo product = productOf(plan, productId);
            if (product == null) {
                continue;
            }
            BigDecimal grossNeed = plan.gross.getOrDefault(productId, BigDecimal.ZERO)
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal stock = stocks.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal committed = plan.committed.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal safety = safetyOf(product);
            BigDecimal usableStock = stock.subtract(committed).subtract(safety).max(BigDecimal.ZERO);
            LocalDate needDate = plan.dates.getOrDefault(productId, LocalDate.now());
            String parents = String.join("、", plan.parents.getOrDefault(productId, Set.of()));
            int level = plan.levels.getOrDefault(productId, 0);
            if (product.make()) {
                BigDecimal net = plan.plannedMake.getOrDefault(productId,
                        grossNeed.subtract(usableStock).max(BigDecimal.ZERO));
                LocalDate start = backDate(needDate, product.leadDays());
                makeRows.add(new MakeRow(productId, product.code(), product.name(), product.unit(),
                        "MAKE", level, grossNeed, stock, committed, safety,
                        net.setScale(4, RoundingMode.HALF_UP),
                        needDate, start, product.leadDays(), parents));
            } else {
                BigDecimal nettedStock = plan.seedNetted.contains(productId) ? BigDecimal.ZERO : usableStock;
                BigDecimal net = grossNeed.subtract(nettedStock)
                        .subtract(onWay.getOrDefault(productId, BigDecimal.ZERO))
                        .subtract(pending.getOrDefault(productId, BigDecimal.ZERO))
                        .max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
                LocalDate suggestOrder = backDate(needDate, product.leadDays());
                buyRows.add(new BuyRow(productId, product.code(), product.name(), product.unit(),
                        "BUY", level, grossNeed, stock, committed, safety,
                        onWay.getOrDefault(productId, BigDecimal.ZERO),
                        pending.getOrDefault(productId, BigDecimal.ZERO), net, needDate,
                        suggestOrder, product.leadDays(), parents));
            }
        }
        makeRows.sort(Comparator.comparingInt(MakeRow::level).thenComparing(MakeRow::productCode));
        buyRows.sort(Comparator.comparing(BuyRow::productCode));
        plan.makeRows = makeRows;
        plan.buyRows = buyRows;
    }

    private ProductInfo productOf(Plan plan, long productId) {
        if (plan.products.containsKey(productId)) {
            return plan.products.get(productId);
        }
        ProductInfo product = masterData.product(productId).orElse(null);
        plan.products.put(productId, product);
        return product;
    }

    /** 已有在制工单（含 DRAFT）的未领材料需求，按物料汇总；跨轮次 MRP 用它做组件占用 */
    private Map<Long, BigDecimal> committedMaterialDemand() {
        Map<Long, BigDecimal> committed = new HashMap<>();
        for (WorkOrderPort.MaterialDemand demand : workOrders.committedMaterialDemand()) {
            if (demand.grossNeed() != null && demand.grossNeed().signum() > 0) {
                committed.merge(demand.productId(), demand.grossNeed(), BigDecimal::add);
            }
        }
        return committed;
    }

    /** 本物料可用的库存：账面可用 - 工单占用 - 安全库存（安全库存不参与销售订单消耗） */
    private BigDecimal stockOf(Plan plan, long productId) {
        Map<Long, BigDecimal> stocks = inventory.availableStocks(List.of(productId));
        BigDecimal available = stocks.getOrDefault(productId, BigDecimal.ZERO);
        BigDecimal committed = plan.committed.getOrDefault(productId, BigDecimal.ZERO);
        return available.subtract(committed).subtract(safetyOf(productOf(plan, productId)))
                .max(BigDecimal.ZERO);
    }

    /** 同一轮 MRP 里同一物料只查一次生效 BOM，避免订单行多时重复查库 */
    private Long activeBomIdCached(Map<Long, Long> cache, long productId) {
        if (cache.containsKey(productId)) {
            return cache.get(productId);
        }
        Long bomId = masterData.activeBomId(productId);
        cache.put(productId, bomId);
        return bomId;
    }

    private BigDecimal safetyOf(ProductInfo product) {
        if (product == null || product.safetyStock() == null) {
            return BigDecimal.ZERO;
        }
        return product.safetyStock().max(BigDecimal.ZERO);
    }

    // ---------------------------------------------------------------- 库存预留

    /**
     * 收口历史预留：订单已取消/已发货/已完成，或明细已取消、已发完、预留量大于未发数量时，
     * 释放或缩减预留，避免死库存一直占着 ATP。
     */
    private void reconcileReservations() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, sales_order_id, sales_order_item_id, qty
                from sales_stock_reservation where status = 'ACTIVE'
                """);
        if (rows.isEmpty()) {
            return;
        }
        List<Long> orderIds = rows.stream()
                .map(row -> ((Number) row.get("sales_order_id")).longValue())
                .distinct().toList();
        Map<Long, SalesOrderPort.SalesOrderRef> orders = salesOrders.orders(orderIds);
        Map<Long, Map<Long, SalesOrderPort.OrderItemRef>> itemCache = new HashMap<>();
        for (Map<String, Object> row : rows) {
            long reservationId = ((Number) row.get("id")).longValue();
            long orderId = ((Number) row.get("sales_order_id")).longValue();
            long itemId = ((Number) row.get("sales_order_item_id")).longValue();
            BigDecimal qty = new BigDecimal(String.valueOf(row.get("qty")));
            SalesOrderPort.SalesOrderRef order = orders.get(orderId);
            if (order == null || CLOSED_ORDER_STATUSES.contains(order.status())) {
                releaseReservation(reservationId);
                continue;
            }
            Map<Long, SalesOrderPort.OrderItemRef> items = itemCache.computeIfAbsent(orderId,
                    id -> {
                        Map<Long, SalesOrderPort.OrderItemRef> map = new HashMap<>();
                        for (SalesOrderPort.OrderItemRef item : salesOrders.items(id)) {
                            map.put(item.id(), item);
                        }
                        return map;
                    });
            SalesOrderPort.OrderItemRef item = items.get(itemId);
            if (item == null || item.cancelled()) {
                releaseReservation(reservationId);
                continue;
            }
            BigDecimal open = openQty(item);
            if (open.signum() <= 0) {
                releaseReservation(reservationId);
            } else if (qty.compareTo(open) > 0) {
                jdbcTemplate.update("""
                        update sales_stock_reservation set qty = ?, updated_at = now()
                        where id = ? and status = 'ACTIVE'
                        """, open, reservationId);
            }
        }
    }

    private void releaseActiveOfOrders(Collection<Long> orderIds) {
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        String marks = String.join(", ", Collections.nCopies(ids.size(), "?"));
        jdbcTemplate.update("""
                update sales_stock_reservation set status = 'RELEASED', updated_at = now()
                where status = 'ACTIVE' and sales_order_id in (""" + marks + ")", ids.toArray());
    }

    private Map<Long, BigDecimal> reservedByProduct(Collection<Long> productIds) {
        Map<Long, BigDecimal> reserved = new HashMap<>();
        List<Long> ids = productIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return reserved;
        }
        String marks = String.join(", ", Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select product_id, sum(qty) as qty from sales_stock_reservation
                where status = 'ACTIVE' and product_id in (""" + marks + ") group by product_id",
                ids.toArray())) {
            reserved.put(((Number) row.get("product_id")).longValue(),
                    new BigDecimal(String.valueOf(row.get("qty"))));
        }
        return reserved;
    }

    private Map<Long, BigDecimal> reservedByOrder(Collection<Long> orderIds) {
        Map<Long, BigDecimal> reserved = new HashMap<>();
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return reserved;
        }
        String marks = String.join(", ", Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select sales_order_id, sum(qty) as qty from sales_stock_reservation
                where status = 'ACTIVE' and sales_order_id in (""" + marks + ") group by sales_order_id",
                ids.toArray())) {
            reserved.put(((Number) row.get("sales_order_id")).longValue(),
                    new BigDecimal(String.valueOf(row.get("qty"))));
        }
        return reserved;
    }

    private void insertReservation(long orderId, long itemId, long productId, BigDecimal qty) {
        jdbcTemplate.update("""
                insert into sales_stock_reservation
                    (sales_order_id, sales_order_item_id, product_id, qty, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, orderId, itemId, productId, qty);
    }

    private void releaseReservation(long reservationId) {
        jdbcTemplate.update("""
                update sales_stock_reservation set status = 'RELEASED', updated_at = now()
                where id = ? and status = 'ACTIVE'
                """, reservationId);
    }

    // ---------------------------------------------------------------- 视图

    private List<Map<String, Object>> finishedRows(List<OrderPlan> orderPlans) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderPlan orderPlan : orderPlans) {
            for (ItemPlan item : orderPlan.items()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("salesOrderId", orderPlan.order().id());
                row.put("orderNo", orderPlan.order().orderNo());
                row.put("productId", item.product().id());
                row.put("productCode", item.product().code());
                row.put("productName", item.product().name());
                row.put("unit", item.product().unit());
                row.put("demandQty", item.demand());
                row.put("stockQty", item.available());
                row.put("reservedQty", item.reserved());
                row.put("netQty", item.netNeed());
                row.put("source", item.product().make() ? "MAKE" : "BUY");
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, Object> workView(WorkOrderPort.CreatedWorkOrder created, ProductInfo product,
                                         BigDecimal qty, String type) {
        Map<String, Object> work = new LinkedHashMap<>();
        work.put("id", created.id());
        work.put("workNo", created.workNo());
        work.put("productId", product == null ? null : product.id());
        work.put("productCode", product == null ? null : product.code());
        work.put("productName", product == null ? null : product.name());
        work.put("qty", qty);
        work.put("type", type);
        return work;
    }

    private List<Map<String, Object>> makeView(List<MakeRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MakeRow row : rows) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("productId", row.productId());
            view.put("productCode", row.productCode());
            view.put("productName", row.productName());
            view.put("unit", row.unit());
            view.put("source", row.source());
            view.put("level", row.level());
            view.put("grossNeed", row.grossNeed());
            view.put("stock", row.stock());
            view.put("committed", row.committed());
            view.put("safetyStock", row.safetyStock());
            view.put("netNeed", row.netNeed());
            view.put("needDate", String.valueOf(row.needDate()));
            view.put("suggestStart", String.valueOf(row.suggestStart()));
            view.put("leadDays", row.leadDays());
            view.put("parents", row.parents());
            result.add(view);
        }
        return result;
    }

    private List<Map<String, Object>> buyView(List<BuyRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BuyRow row : rows) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("productId", row.productId());
            view.put("productCode", row.productCode());
            view.put("productName", row.productName());
            view.put("unit", row.unit());
            view.put("source", row.source());
            view.put("level", row.level());
            view.put("grossNeed", row.grossNeed());
            view.put("stock", row.stock());
            view.put("committed", row.committed());
            view.put("safetyStock", row.safetyStock());
            view.put("onWay", row.onWay());
            view.put("pending", row.pending());
            view.put("netNeed", row.netNeed());
            view.put("needDate", String.valueOf(row.needDate()));
            view.put("suggestOrderDate", String.valueOf(row.suggestOrderDate()));
            view.put("leadDays", row.leadDays());
            view.put("parents", row.parents());
            result.add(view);
        }
        return result;
    }

    // ---------------------------------------------------------------- 工具

    private int maxLevel(Integer maxLevel) {
        int limit = maxLevel == null ? DEFAULT_MAX_LEVEL : maxLevel;
        if (limit < 1 || limit > MAX_LEVEL_LIMIT) {
            throw new BizException(1002, "展开层数应在 1 ~ " + MAX_LEVEL_LIMIT + " 之间");
        }
        return limit;
    }

    private BigDecimal openQty(SalesOrderPort.OrderItemRef item) {
        BigDecimal qty = item.qty() == null ? BigDecimal.ZERO : item.qty();
        BigDecimal shipped = item.shippedQty() == null ? BigDecimal.ZERO : item.shippedQty();
        return qty.subtract(shipped);
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
}
