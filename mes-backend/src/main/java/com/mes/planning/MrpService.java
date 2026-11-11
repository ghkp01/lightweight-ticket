package com.mes.planning;

import com.mes.common.BizException;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.planning.api.MrpAlertPort;
import com.mes.production.api.WorkOrderPort;
import com.mes.purchase.api.RequisitionCreated;
import com.mes.purchase.api.RequisitionDraft;
import com.mes.purchase.api.RequisitionLine;
import com.mes.purchase.api.RequisitionPort;
import com.mes.purchase.api.SupplyPort;
import com.mes.security.CurrentUser;
import com.mes.task.api.TaskDraft;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物料需求计算（MRP，单阶）。
 *
 * 只做「算」和「建议」，不自动下单：把未完工工单的 BOM 需求汇总起来，
 * 扣掉现有可用、采购在途、已申请未转单与安全库存，得出每个物料缺多少、什么时候要、建议哪天找谁下单。
 * 采购员确认后一键生成采购申请，审批与转采购单仍走原有流程。
 *
 * <p>三笔输入各归其主：毛需求问生产（{@link WorkOrderPort}）、在途与待转问采购
 * （{@link SupplyPort}）、可用量问库存（{@link InventoryPort}）、物料档案问 masterdata。
 * 以前这些是一条大 CTE 直接把别人的表 join 进来，等于计划模块复制了四份口径。</p>
 */
@Service
public class MrpService implements MrpAlertPort {

    private static final int MAX_ROWS = 500;
    private static final int DEFAULT_LEAD_DAYS = 7;
    /** 一次预警最多点名多少物料，剩下的用「等」收口，避免一条通知刷屏 */
    private static final int MAX_ALERT_ROWS = 10;

    private final RequisitionPort requisitionService;
    private final InventoryPort inventory;
    private final WorkOrderPort workOrderPort;
    private final SupplyPort supply;
    private final MasterDataPort masterData;
    private final TaskPort taskService;
    private final JdbcTemplate jdbcTemplate;
    private final NoticePort notice;

    public MrpService(RequisitionPort requisitionService, InventoryPort inventory,
                      WorkOrderPort workOrderPort, SupplyPort supply, MasterDataPort masterData,
                      TaskPort taskService, JdbcTemplate jdbcTemplate, NoticePort notice) {
        this.requisitionService = requisitionService;
        this.inventory = inventory;
        this.workOrderPort = workOrderPort;
        this.supply = supply;
        this.masterData = masterData;
        this.taskService = taskService;
        this.jdbcTemplate = jdbcTemplate;
        this.notice = notice;
    }

    public record MrpQuery(Boolean includeScheduled, Boolean onlyShortage, Integer horizonDays) {
    }

    public record CreateLine(Long productId, BigDecimal qty, Long suggestSupplierId,
                             BigDecimal targetPrice, String remark) {
    }

    public record CreateRequest(LocalDate needDate, String department, String remark, List<CreateLine> items) {
    }

    // ---------------------------------------------------------------- 计算

    /**
     * 跑一次物料需求。
     *
     * 毛需求 = 未完工工单 BOM 快照的待领量合计；
     * 可用 = 各仓结存 - 锁定；
     * 在途 = 已确认未收货的采购量 + 草稿/已提交/已批准未转单的申请量；
     * 缺口 = max(0, 毛需求 + 安全库存 - 可用 - 在途)。
     */
    @Transactional
    public Map<String, Object> run(MrpQuery query) {
        return calculate(query, true);
    }

    /**
     * 算一次需求，{@code closeTasks} 决定是否顺手收口算料任务。
     *
     * <p>预警任务只要数字、不想动任务状态，就走 {@code false}；页面上的「跑 MRP」走 {@code true}。</p>
     */
    private Map<String, Object> calculate(MrpQuery query, boolean closeTasks) {
        boolean includeScheduled = query != null && Boolean.TRUE.equals(query.includeScheduled());
        boolean onlyShortage = query == null || query.onlyShortage() == null || Boolean.TRUE.equals(query.onlyShortage());
        Integer horizonDays = query == null ? null : query.horizonDays();

        List<String> statuses = new ArrayList<>(List.of("RELEASED", "IN_PROGRESS"));
        if (includeScheduled) {
            statuses.add("SCHEDULED");
        }

        // 毛需求：生产按工单 BOM 待领量汇总，计划不再自己拼 work_order_bom
        List<WorkOrderPort.MaterialDemand> demands = workOrderPort.openMaterialDemand(statuses, horizonDays);
        // 算料这一步本身就算「做完了」：只关被本次 BOM 需求覆盖到的订单，
        // 还没下达工单的订单根本没算到料，任务要留在任务页提醒
        if (closeTasks) {
            closeMrpTasks(demands);
        }
        List<Long> productIds = demands.stream()
                .map(WorkOrderPort.MaterialDemand::productId)
                .distinct()
                .toList();

        Map<Long, ProductInfo> products = productIds.isEmpty() ? Map.of() : masterData.products(productIds);
        // 可用量走库存契约（全仓结存 - 锁定）
        Map<Long, BigDecimal> availableMap = inventory.availableStocks(productIds);
        // 在途 / 已申请未转 / 最近报价 / 最近采购价，都由采购按物料批量给
        Map<Long, BigDecimal> onWayMap = supply.onWayQty(productIds);
        Map<Long, BigDecimal> pendingMap = supply.pendingRequisitionQty(productIds);
        Map<Long, SupplyPort.PriceQuote> lastQuotes = supply.lastPriceQuotes(productIds);
        Map<Long, SupplyPort.PriceQuote> purchaseQuotes = supply.lastPurchaseQuotes(productIds);
        // 建议供应商：价格库优先，其次最近采购单
        Set<Long> supplierIds = new LinkedHashSet<>();
        for (Long productId : productIds) {
            Long supplierId = suggestSupplierId(productId, lastQuotes, purchaseQuotes);
            if (supplierId != null) {
                supplierIds.add(supplierId);
            }
        }
        Map<Long, SupplyPort.SupplierBrief> suppliers =
                supplierIds.isEmpty() ? Map.of() : supply.suppliers(supplierIds);

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalGap = BigDecimal.ZERO;
        int shortageCount = 0;
        int overdueCount = 0;
        for (WorkOrderPort.MaterialDemand demand : demands) {
            ProductInfo product = products.get(demand.productId());
            if (product == null) {
                continue;
            }
            long productId = demand.productId();
            BigDecimal gross = demand.grossNeed() == null ? BigDecimal.ZERO : demand.grossNeed();
            BigDecimal safety = product.safetyStock() == null ? BigDecimal.ZERO : product.safetyStock();
            BigDecimal available = decimal(availableMap.get(productId));
            BigDecimal onWay = decimal(onWayMap.get(productId));
            BigDecimal pendingReq = decimal(pendingMap.get(productId));
            BigDecimal gap = gross.add(safety).subtract(available).subtract(onWay).subtract(pendingReq);
            if (gap.signum() < 0) {
                gap = BigDecimal.ZERO;
            }

            Long supplierId = suggestSupplierId(productId, lastQuotes, purchaseQuotes);
            SupplyPort.SupplierBrief supplier = supplierId == null ? null : suppliers.get(supplierId);
            BigDecimal price = suggestPrice(productId, lastQuotes, purchaseQuotes);
            Integer quoteLeadDays = lastQuotes.get(productId) == null
                    ? null : lastQuotes.get(productId).leadDays();
            int leadDays = firstNonNull(quoteLeadDays,
                    supplier == null ? null : supplier.leadDays(), DEFAULT_LEAD_DAYS);
            LocalDate needDate = demand.firstNeedDate() == null ? today : demand.firstNeedDate();
            LocalDate orderDate = needDate.minusDays(leadDays);
            boolean overdue = orderDate.isBefore(today);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", productId);
            row.put("productCode", product.code());
            row.put("productName", product.name());
            row.put("spec", product.spec());
            row.put("unit", product.unit());
            row.put("category", product.category());
            row.put("safetyStock", safety);
            row.put("grossNeed", gross);
            row.put("needDate", needDate.toString());
            row.put("workOrderCount", demand.workOrderCount());
            row.put("workOrders", demand.workOrders());
            row.put("onWay", onWay);
            row.put("pendingRequisition", pendingReq);
            row.put("suggestSupplierId", supplierId);
            row.put("suggestSupplierName", supplier == null ? null : supplier.name());
            row.put("suggestSupplierBuyer", supplier == null ? null : supplier.buyer());
            row.put("suggestPrice", price);
            row.put("available", available);
            row.put("gapQty", gap);
            row.put("leadDays", leadDays);
            row.put("suggestOrderDate", orderDate.toString());
            row.put("overdue", overdue);
            row.put("urgent", needDate.isBefore(today.plusDays(leadDays)));
            row.put("suggestQty", gap);
            rows.add(row);

            if (gap.signum() > 0) {
                shortageCount++;
                totalGap = totalGap.add(gap);
                if (overdue) {
                    overdueCount++;
                }
            }
        }
        if (onlyShortage) {
            rows.removeIf(row -> decimal(row.get("gapQty")).signum() <= 0);
        }
        rows.sort((a, b) -> {
            boolean ao = Boolean.TRUE.equals(a.get("overdue"));
            boolean bo = Boolean.TRUE.equals(b.get("overdue"));
            if (ao != bo) {
                return ao ? -1 : 1;
            }
            int dateCompare = String.valueOf(a.get("suggestOrderDate"))
                    .compareTo(String.valueOf(b.get("suggestOrderDate")));
            if (dateCompare != 0) {
                return dateCompare;
            }
            return decimal(b.get("gapQty")).compareTo(decimal(a.get("gapQty")));
        });
        if (rows.size() > MAX_ROWS) {
            rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("materialCount", demands.size());
        summary.put("shortageCount", shortageCount);
        summary.put("overdueCount", overdueCount);
        summary.put("totalGapQty", totalGap);
        summary.put("rowCount", rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("summary", summary);
        result.put("includeScheduled", includeScheduled);
        result.put("onlyShortage", onlyShortage);
        result.put("horizonDays", horizonDays);
        result.put("calculatedAt", today.toString());
        return result;
    }

    // ---------------------------------------------------------------- 缺口逾期预警

    @Override
    @Transactional
    public int alertOverdueShortages() {
        Map<String, Object> result = calculate(new MrpQuery(false, true, null), false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        List<Map<String, Object>> overdue = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("overdue")) && decimal(row.get("gapQty")).signum() > 0) {
                overdue.add(row);
                if (overdue.size() >= MAX_ALERT_ROWS) {
                    break;
                }
            }
        }
        if (overdue.isEmpty()) {
            return 0;
        }

        // 按采购员分组：有指定采购员的直接推到本人，没指定的回退给计划角色
        Map<String, List<Map<String, Object>>> byBuyer = new LinkedHashMap<>();
        for (Map<String, Object> row : overdue) {
            Object buyerObj = row.get("suggestSupplierBuyer");
            String buyer = buyerObj == null ? null : String.valueOf(buyerObj);
            String key = (buyer == null || buyer.isBlank()) ? "PLANNER" : buyer;
            byBuyer.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        int pushed = 0;
        for (Map.Entry<String, List<Map<String, Object>>> entry : byBuyer.entrySet()) {
            String receiver = entry.getKey();
            List<Map<String, Object>> group = entry.getValue();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> row : group) {
                // 同一天同一物料只推一次：手工重跑、定时重跑都不会重复轰炸
                int inserted = jdbcTemplate.update("""
                        insert into mrp_alert_log (alert_date, product_id, product_code, gap_qty,
                                                   need_date, order_count, pushed_to)
                        values (current_date, ?, ?, ?, ?::date, ?, ?)
                        on conflict (alert_date, product_id) do nothing
                        """, ((Number) row.get("productId")).longValue(), row.get("productCode"),
                        decimal(row.get("gapQty")), row.get("needDate"),
                        row.get("workOrderCount") == null ? 0 : ((Number) row.get("workOrderCount")).intValue(),
                        receiver);
                if (inserted > 0) {
                    pushed++;
                    names.add(row.get("productCode") + " 缺 "
                            + decimal(row.get("gapQty")).stripTrailingZeros().toPlainString());
                }
            }
            if (names.isEmpty()) {
                continue;
            }
            String title = names.size() + " 项物料已过建议下单日";
            String content = "这些料按交期倒推，最迟下单日已经过了：" + String.join("、", names)
                    + "。到「物料需求」页确认缺口后生成采购申请，否则会拖住对应工单开工。";
            if ("PLANNER".equals(receiver)) {
                notice.pushToRoles(List.of("PLANNER"), NoticeMessage.of("ALERT", title, content));
            } else {
                notice.push(receiver, NoticeMessage.of("ALERT", title, content));
            }
        }
        return pushed;
    }

    // ---------------------------------------------------------------- 一键生成采购申请

    @Transactional
    public Map<String, Object> createRequisition(CreateRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请选择要生成采购申请的物料");
        }
        List<RequisitionLine> items = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        for (CreateLine line : request.items()) {
            if (line == null || line.productId() == null || line.qty() == null || line.qty().signum() <= 0) {
                throw new BizException(1002, "物料与申请数量必须填写完整，数量需大于 0");
            }
            items.add(new RequisitionLine(line.productId(), line.qty(),
                    line.targetPrice() == null ? BigDecimal.ZERO : line.targetPrice(),
                    line.suggestSupplierId(), false, line.remark()));
            totalQty = totalQty.add(line.qty());
        }
        LocalDate needDate = request.needDate();
        if (needDate == null) {
            needDate = LocalDate.now().plusDays(1);
        }
        if (needDate.isBefore(LocalDate.now())) {
            // 需求日已经过去，按今天提，避免申请单校验不过
            needDate = LocalDate.now();
        }
        String purpose = "MRP 补料";
        String remark = request.remark() == null || request.remark().isBlank()
                ? "由物料需求计算生成，来源为未完工工单的 BOM 待领量" : request.remark();
        RequisitionCreated created = requisitionService.create(new RequisitionDraft(
                "PRODUCTION", request.department(), null, needDate, purpose, remark, items));
        // 单号由契约一并返回，不用回头查采购模块的申请表
        long id = created.id();
        String reqNo = created.reqNo();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reqId", id);
        result.put("reqNo", reqNo);
        result.put("itemCount", items.size());
        result.put("totalQty", totalQty);
        result.put("needDate", needDate.toString());
        result.put("status", "DRAFT");
        // 申请单只是把需求立住，真正落单还得转采购单：派一条任务给计划/采购，
        // 已批准的行转完自动关闭；不这样派，申请单会躺在列表里没人跟进
        taskService.open(TaskDraft.of(TaskTypes.REQ_CONVERT, "采购申请 " + reqNo + " 待转采购单",
                        "MRP 生成 " + items.size() + " 项物料，合计 " + totalQty.stripTrailingZeros().toPlainString()
                                + "；提交审批后按已批准的行转采购单",
                        TaskTypes.REF_REQUISITION, id, reqNo, "PLANNER")
                .dueDate(needDate));
        return result;
    }

    /**
     * 收口算料任务：只关被本轮 BOM 需求覆盖到的销售订单。
     *
     * <p>以前这里 closeAll，等于把还没下达工单、根本没算到料的订单也算「算过了」，
     * 任务页从此不再提醒；按订单关才和「算没算过」对得上。</p>
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
                CurrentUser.name(), "已完成物料需求计算");
    }

    /** 建议供应商：价格库里的有效报价优先，没有就看最近一张采购单 */
    private static Long suggestSupplierId(long productId, Map<Long, SupplyPort.PriceQuote> lastQuotes,
                                          Map<Long, SupplyPort.PriceQuote> purchaseQuotes) {
        SupplyPort.PriceQuote quote = lastQuotes.get(productId);
        if (quote != null && quote.supplierId() != null) {
            return quote.supplierId();
        }
        SupplyPort.PriceQuote purchase = purchaseQuotes.get(productId);
        return purchase == null ? null : purchase.supplierId();
    }

    private static BigDecimal suggestPrice(long productId, Map<Long, SupplyPort.PriceQuote> lastQuotes,
                                           Map<Long, SupplyPort.PriceQuote> purchaseQuotes) {
        SupplyPort.PriceQuote quote = lastQuotes.get(productId);
        if (quote != null && quote.price() != null) {
            return quote.price();
        }
        SupplyPort.PriceQuote purchase = purchaseQuotes.get(productId);
        return purchase == null ? null : purchase.price();
    }

    /** 交期优先级：报价上的交期 > 供应商档案的交期 > 默认值 */
    private static int firstNonNull(Integer first, Integer second, int fallback) {
        if (first != null) {
            return first;
        }
        return second == null ? fallback : second;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
