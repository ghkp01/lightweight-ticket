package com.mes.production.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生产工单的对外契约。
 *
 * <p>销售订单确认后要「按产品工艺路线生成工序、按 BOM 快照展开用料」，
 * 这两步是生产模块的内部规则（含损耗率放大、替代料处理），销售不该复制一份。</p>
 *
 * <p>采购申请要带「来源工单号」、工厂归属也要随工单走；质检要判断工单状态、
 * 取工序快照、完工后关单并回写合格数 —— 这些都是工单的状态机与字段口径，
 * 由生产侧统一收口，调用方不再直接读写 {@code work_order} / {@code work_order_operation}。</p>
 *
 * <p>排产（planning）是第三类调用方：它要按优先级与交期挑 DRAFT 工单、算每日负荷、
 * 再把 {@code schedule_date / schedule_seq / scheduled_start / scheduled_end} 写回去。
 * 这些字段属于工单，写入规则（改状态、递 version、临时占位避免顺序冲突）也属于工单，
 * 所以这里给的是「视图 + 命令」，而不是把 SQL 交给计划模块。</p>
 */
public interface WorkOrderPort {

    /** 工单概要：质检判断状态、拿产品与销售回链用 */
    record WorkOrderRef(long id, String workNo, String status, String workType, long productId,
                        Long parentWorkOrderId, Long salesOrderId, Long salesOrderItemId,
                        LocalDate planEnd) {
    }

    /** 工单工序快照 */
    record WorkOrderOperation(long id, long workOrderId, int seq, String name, Long processId) {
    }

    /** 排产视图：一张工单在排产里需要的全部字段 */
    record ScheduleOrder(long id, String workNo, long productId, BigDecimal planQty, BigDecimal doneQty,
                         String priority, String status, Long salesOrderId,
                         LocalDate scheduleDate, Integer scheduleSeq,
                         LocalDateTime scheduledStart, LocalDateTime scheduledEnd,
                         LocalDateTime createdAt) {
    }

    /** 排产视图的工序行：甘特条与有限产能都要按工序排 */
    record ScheduleOperation(long id, long workOrderId, int seq, String name, Long processId,
                             BigDecimal standardMinutes, BigDecimal planQty, BigDecimal doneQty,
                             LocalDate scheduleDate) {
    }

    /** 单阶 MRP 的毛需求：某物料还差多少、最早哪天要、涉及几张工单 */
    record MaterialDemand(long productId, BigDecimal grossNeed, LocalDate firstNeedDate,
                          int workOrderCount, String workOrders) {
    }

    /** 齐套判断的候选工单：用到某物料、且还有待领行；rank 是紧急程度（在制 0 / 已下达 1 / 其余 2） */
    record KittingCandidate(long id, String workNo, int rank, LocalDate dueDate) {
    }

    /** 工单待领行：某物料还差多少 */
    record MaterialNeed(long workOrderId, long materialId, BigDecimal needQty) {
    }

    /** 缺料巡检行：已下达 / 在制工单的待领行，带工单号与产品，省得调用方再拼一次工单表 */
    record ActiveMaterialNeed(long workOrderId, String workNo, long productId, long materialId,
                              BigDecimal needQty) {
    }

    /** 建单结果：销售要拿工单号回显 */
    record CreatedWorkOrder(long id, String workNo) {
    }

    /** 工单前后置关系：predecessor 完工后 successor 才能下达 */
    record WorkOrderDependency(long predecessorId, long successorId) {
    }

    /** 待退料行：撤单前告诉用户「这单还压着哪些料没退」 */
    record PendingReturn(long workOrderId, String workNo, long materialId, String materialCode,
                         String materialName, String unit, BigDecimal issuedQty, BigDecimal returnedQty,
                         BigDecimal returnableQty) {
    }

    /**
     * 按销售订单明细建一张 DRAFT 工单，并生成工序与 BOM 快照。
     *
     * <p>「销售订单确认后转生产」这条链路原来由销售直接 {@code insert into work_order}，
     * 再把工序/BOM 交给生产补 —— 等于销售替生产决定了工单的初始状态与字段口径。
     * 现在整条动作收在生产侧，销售只发一条命令。</p>
     */
    CreatedWorkOrder createFromSalesOrder(long salesOrderId, long salesOrderItemId, long productId,
                                          BigDecimal planQty, String priority, LocalDate planEnd);

    /**
     * 计划建单：MRP 为半成品等非销售明细物料建工单时使用，不带销售订单回链。
     * 单号、初始状态、工序与 BOM 快照仍由生产侧统一生成。
     */
    CreatedWorkOrder createPlanned(long productId, BigDecimal planQty, LocalDate planStart,
                                   LocalDate planEnd, String priority, String remark);

    /**
     * 建立「前置工单完工后才能下达后置工单」的依赖，重复调用幂等。
     * MRP 建完成品/半成品工单后，用 BOM 父子关系把半成品挂到成品前面。
     */
    void linkDependencies(Collection<WorkOrderDependency> dependencies);

    /** 按产品工艺路线为工单生成工序，没有路线时静默跳过 */
    void createOperations(long workOrderId, long productId, BigDecimal planQty);

    /** 按产品 BOM 为工单生成用料快照（含损耗率放大） */
    void snapshotBom(long workOrderId, long productId, BigDecimal planQty);

    /** 工单是否存在 */
    boolean exists(long workOrderId);

    /** 工单号，不存在返回 null */
    String workNo(long workOrderId);

    /** 批量取工单号，返回 id → 工单号；找不到的 id 不出现在结果里 */
    Map<Long, String> workNos(Collection<Long> workOrderIds);

    /** 按工单号模糊搜索，返回 id 集合，用于把「按工单号搜索」换成 id 条件 */
    Set<Long> searchIds(String keyword);

    /** 工单归属工厂，没有归属返回 null */
    Long factoryOrgId(long workOrderId);

    /** 工单概要，不存在返回 null */
    WorkOrderRef workOrder(long workOrderId);

    /** 工单状态，不存在返回 null */
    String statusOf(long workOrderId);

    /** 工单产品，不存在返回 null */
    Long productIdOf(long workOrderId);

    /**
     * 取工单下的一道工序：优先按工序 id，其次按序号；不属于该工单或找不到返回 null。
     * 质检扫码与不良处置都靠它把「工序」对上「工单」。
     */
    WorkOrderOperation operation(long workOrderId, Long operationId, Integer seq);

    /** 批量取工序，返回 id → 工序 */
    Map<Long, WorkOrderOperation> operations(Collection<Long> operationIds);

    /** 完工检合格：回写合格数并关单；返修工单完工时顺带关掉父工单 */
    void closeAfterFinalInspection(long workOrderId, BigDecimal qualifiedQty);

    /** 完工检不合格：把工单置为返修中 */
    void markRework(long workOrderId);

    /** 让步接收等场景累加合格数，不动状态 */
    void addQualifiedQty(long workOrderId, BigDecimal qty);

    /** 销售订单下是否还有未完工（非 FINISHED / CLOSED）的工单 */
    boolean hasUnfinishedForSalesOrder(long salesOrderId);

    /** 销售订单下是否有任何工单（转生产的幂等校验用；已作废的工单不算） */
    boolean existsForSalesOrder(long salesOrderId);

    /**
     * 销售订单下还没收尾的工单号（未作废、未关闭）。
     * 订单取消前用它给出「先作废哪几张工单」的明确提示，空列表表示可以取消。
     */
    List<String> openWorkNos(long salesOrderId);

    /**
     * 某销售订单明细下的工单（含已作废）。
     *
     * <p>明细级撤单要连带作废这几张工单，所以由销售侧问生产侧「这一行派了哪些单」。
     * 早期的工单只写了 {@code sales_order_id} 没写明细 id，这里按
     * 「明细 id 命中，或者明细 id 为空但订单 + 产品命中」兜底，避免漏掉历史单。</p>
     */
    List<WorkOrderRef> workOrdersOfItem(long salesOrderItemId, long salesOrderId, long productId);

    /**
     * 工单作废的阻塞原因，空列表表示可以作废。
     *
     * <p>车间「作废」与销售「撤明细行」都走这一套判断，提示口径保持一致；
     * 预检场景只用它看结果，不落任何状态。</p>
     */
    List<String> cancelBlockers(long workOrderId);

    /**
     * 作废工单：客户取消、下单错误时把还没开工的工单撤掉。
     *
     * <p>这是工单状态机的入口，销售撤明细行也调它，不再由销售直接改
     * {@code work_order.status}。返回工单 id、工单号、新状态与原因。</p>
     */
    Map<String, Object> cancelWorkOrder(long workOrderId, String reason);

    /** 这些工单已领未退的料（只列还有可退量的行），撤单前给「待退料清单」 */
    List<PendingReturn> pendingReturns(Collection<Long> workOrderIds);

    /**
     * 缩短工单计划量：客户砍掉一部分数量时用。
     *
     * <p>只允许在「还没动过料和人」的工单上改：工序计划量与标准工时、BOM 快照用量
     * 一起按比例缩，避免车间照着老数量多干。已经领料 / 报工的改动会让账实不符，
     * 因此与作废共用同一套前置条件。</p>
     *
     * <p>返回工单号、原计划量、新计划量，以及被缩掉的工序数与 BOM 行数。</p>
     */
    Map<String, Object> resizeWorkOrder(long workOrderId, BigDecimal newQty);

    // ---------------------------------------------------------------- 计划：需求与排产

    /**
     * 单阶 MRP 的毛需求：指定状态工单的 BOM 待领量，按物料汇总。
     *
     * @param statuses     计入毛需求的工单状态，一般是 RELEASED / IN_PROGRESS（可加 SCHEDULED）
     * @param horizonDays  只统计排产日（或计划开始日）在 N 天内的工单；null 表示不限
     */
    List<MaterialDemand> openMaterialDemand(Collection<String> statuses, Integer horizonDays);

    /**
     * 所有未收尾工单（含 DRAFT / SCHEDULED / RELEASED / IN_PROGRESS）的待领需求，按物料汇总。
     *
     * <p>销售订单 MRP 跨轮次计算时用：上一轮已经生成工单的组件需求，相当于占用了现有库存。
     * 不扣这部分，下一张订单会把同一份组件库存再用一次，导致少采购。</p>
     */
    List<MaterialDemand> committedMaterialDemand();

    /** 指定状态的工单（排产用），按 id 正序 */
    List<ScheduleOrder> ordersForScheduling(Collection<String> statuses);

    /** 按 id 批量取工单排产视图，找不到的不在结果里 */
    Map<Long, ScheduleOrder> scheduleOrders(Collection<Long> workOrderIds);

    /** 指定工单的全部工序（不限排产日），按工单 + 序号排列；有限产能排产逐单取用 */
    List<ScheduleOperation> operationsOfWorkOrders(Collection<Long> workOrderIds);

    /** 指定工单的工序合计工时，返回工单 id → 分钟数；没有工序的不在结果里 */
    Map<Long, BigDecimal> standardMinutes(Collection<Long> workOrderIds);

    /** 指定状态工单在排产日区间内的工序行（甘特与有限产能负荷用） */
    List<ScheduleOperation> operationsForScheduling(Collection<String> statuses,
                                                     LocalDate from, LocalDate to);

    /** 只做了整单排产、还没细分到工序的单（甘特图拿它画「整单」灰条） */
    record OrderLevelPlan(long id, String workNo, long productId, BigDecimal planQty, BigDecimal doneQty,
                          String status, String priority, LocalDate scheduleDate, Integer scheduleSeq) {
    }

    /**
     * 排产日落在区间内、且**没有任何工序**写过排产日的工单。
     *
     * <p>一键排产 / 单张排产 / 加急插队都只在工单级落一个日期，没有工序级数据。
     * 甘特图按工序取数，这批单会整体消失 —— 用它兜底画成「整单」条，
     * 让粗排结果也能在甘特上看见，同时明确标出还没精细到工序。</p>
     */
    List<OrderLevelPlan> orderLevelPlans(Collection<String> statuses, LocalDate from, LocalDate to);

    /** 某天已排产的工单总工时，返回排产日 → 分钟数（一键排产算剩余产能用） */
    Map<LocalDate, BigDecimal> scheduledMinutesByDate(LocalDate from, LocalDate to);

    /** 某天已用的最大排产顺序，没有返回 null */
    Integer maxScheduleSeq(LocalDate date, Long excludeWorkOrderId);

    /** 某天已用的最小排产顺序，没有返回 null */
    Integer minScheduleSeq(LocalDate date, Long excludeWorkOrderId);

    /** 同一天、顺序相邻的另一张工单；{@code up} 为 true 取前一张，否则取后一张；没有返回 null */
    ScheduleOrder scheduleNeighbor(LocalDate date, int seq, long workOrderId, boolean up);

    /** 落排产：改状态 + 写四个排产字段（date 为 null 时按 start 的日期） */
    void placeSchedule(long workOrderId, LocalDate date, int seq, LocalDateTime start,
                       LocalDateTime end, String status);

    /** 改优先级（加急插队把工单置 RUSH） */
    void updatePriority(long workOrderId, String priority);

    /** 撤销排产：状态回 DRAFT 并清空排产字段 */
    void clearSchedule(long workOrderId);

    /** 交换两张工单的排产顺序（内部用临时值占位，避免顺序冲突） */
    void swapScheduleSeq(long workOrderId, int workOrderSeq, long neighborId, int neighborSeq);

    /** 写工序的排产日与起止时间 */
    void scheduleOperation(long operationId, LocalDate date, LocalDateTime start, LocalDateTime end);

    /** 清空某张工单所有工序的排产时间 */
    void clearOperationSchedule(long workOrderId);

    // ---------------------------------------------------------------- 仓库：齐套判断

    /**
     * 用到这些物料、且还有待领行的工单（只算 SCHEDULED / RELEASED / IN_PROGRESS）。
     * 到货入库后判断「哪些工单可以领料了」用，紧急程度由生产按自己的状态口径给出。
     */
    List<KittingCandidate> kittingCandidates(Collection<Long> materialIds);

    /** 这些工单的全部待领行（不含替代行），齐套判断要在内存里把缺口算完 */
    List<MaterialNeed> openMaterialNeeds(Collection<Long> workOrderIds);

    /** 所有已下达 / 在制工单的待领行（不含替代行），定时缺料巡检用 */
    List<ActiveMaterialNeed> activeMaterialNeeds();

    /**
     * 这些工单号所属的销售订单 id（去重，没有关联订单的不算）。
     * 跑完 MRP 要按订单收口算料任务，计划只知道工单号，订单归属由生产给。
     */
    Set<Long> salesOrderIdsOfWorkNos(Collection<String> workNos);
}
