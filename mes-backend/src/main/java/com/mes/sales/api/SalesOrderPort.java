package com.mes.sales.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 销售订单的对外契约。
 *
 * <p>三类能力：</p>
 * <ol>
 *   <li><b>状态跃迁</b>：工单全部完工后把订单推进到「待发货」、发货完把订单推进到「已发货 / 已完成」。
 *       以前是质检与仓储各自 {@code update sales_order set status = ...}，等于替销售决定了订单状态机；</li>
 *   <li><b>订单概要</b>：排产要按交期排序、看板上要显示订单号，以前是 {@code left join sales_order}
 *       直接读别人的表；</li>
 *   <li><b>发货进度</b>：发货单要判断「这条明细还能发多少」并回写已发数量，
 *       已发数量是销售订单的口径，因此累加也放在这里。</li>
 * </ol>
 */
public interface SalesOrderPort {

    /** 订单概要：排产排序与看板展示用 */
    record SalesOrderBrief(long id, String orderNo, LocalDate deliveryDate, String status) {
    }

    /** 订单头：发货、退货要判断状态与客户归属 */
    record SalesOrderRef(long id, String orderNo, String status, Long customerId, Long factoryOrgId) {
    }

    /**
     * 订单明细：发货按明细校验可发数量并回写已发数量。
     *
     * <p>{@code status} 是明细自身的状态（ACTIVE / CANCELLED）：客户只取消其中几行时，
     * 发货与退货都不该再把取消掉的行算进来。</p>
     */
    record OrderItemRef(long id, long orderId, long productId, BigDecimal qty, BigDecimal shippedQty,
                        String status) {

        /** 这一行是否已被单独取消 */
        public boolean cancelled() {
            return "CANCELLED".equals(status);
        }
    }

    /** 交期预警行：还没发完的订单，带交期与已发数量 */
    record DeliveryLine(long id, String orderNo, Long customerId, LocalDate deliveryDate,
                        BigDecimal totalQty, BigDecimal shippedQty) {
    }

    /** 推进到待发货：只对「生产中」的订单生效，其它状态静默忽略 */
    void markReadyToShip(long salesOrderId);

    /** MRP 已为该订单生成生产/采购单据，推进到生产中 */
    void markInProduction(long salesOrderId);

    /** 全部明细发完则推进到「已发货」，没发完静默忽略 */
    void markShippedIfComplete(long salesOrderId);

    /** 全部明细发完则推进到「已完成」，没发完静默忽略 */
    void markCompletedIfComplete(long salesOrderId);

    /** 按 id 批量取订单概要，找不到的不在结果里 */
    Map<Long, SalesOrderBrief> briefs(Collection<Long> salesOrderIds);

    /** 订单头，不存在返回 null */
    SalesOrderRef order(long salesOrderId);

    /** 批量取订单头，找不到的不在结果里 */
    Map<Long, SalesOrderRef> orders(Collection<Long> salesOrderIds);

    /** 批量取订单号，找不到的不在结果里 */
    Map<Long, String> orderNos(Collection<Long> salesOrderIds);

    /** 按订单号模糊搜索，返回 id 集合，用于把「按订单号搜发货单」换成 id 条件 */
    Set<Long> searchIds(String keyword);

    /**
     * 全局 MRP 的运行范围：已确认、且还没生成过生产工单的订单，按交期升序。
     * 已生成工单的订单不再参与，避免库存被同一张订单重复占用。
     */
    List<SalesOrderRef> openForMrp();

    /** 某张订单的全部明细 */
    List<OrderItemRef> items(long salesOrderId);

    /** 按明细 id 取，不存在返回 null */
    OrderItemRef item(long orderItemId);

    /** 回写已发数量（累加） */
    void addShippedQty(long orderItemId, BigDecimal qty);

    /** 本厂（含未归属）可见的订单 id，用于发货单等下游单据做工厂隔离 */
    Set<Long> idsInFactory(Long factoryOrgId);

    /**
     * 交期预警：未完成、有交期、且交期不晚于 {@code aheadDate}、还没发完的订单，按交期升序。
     * 「还没发完」是订单口径（总数量 vs 明细已发数量），所以由销售侧回答。
     */
    List<DeliveryLine> pendingDeliveries(LocalDate aheadDate, int limit);
}
