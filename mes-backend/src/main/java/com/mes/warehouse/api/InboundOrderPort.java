package com.mes.warehouse.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入库单的对外契约。
 *
 * <p>采购这边对入库单是「双向」的：收货时由采购触发建单（{@link #createPurchaseInbound}），
 * 建完之后又要反复回读（待收数量里扣在途、采购退货反查可退明细、暂估与对账要带入库单号）。
 * 这些以前都是直接写 {@code join inbound_order} 甚至 {@code insert into inbound_order}，
 * 表结构一改采购就得跟着改。</p>
 *
 * <p>写接口和调用方在同一个事务里执行：收货建单与采购单状态回写要么一起提交，要么一起回滚。</p>
 */
public interface InboundOrderPort {

    /** 建单结果：只需要 id 与单号，其余信息采购用不到 */
    record CreatedInbound(long id, String inboundNo) {
    }

    /** 入库单主信息（只读视图，不含明细）。供应商名称属于采购侧的主数据，这里只给 id */
    record InboundOrderView(long id, String inboundNo, String type, String status, String source,
                            Long purchaseOrderId, Long supplierId, Long warehouseId,
                            BigDecimal totalQty, LocalDate completedDate,
                            String createdAt, String completedAt) {
    }

    /** 入库明细行 */
    record InboundLine(long id, long inboundOrderId, long productId, BigDecimal qty,
                       String batchNo, Long purchaseItemId, BigDecimal unitPrice) {
    }

    /** 采购收货的一行：采购明细 id 是回链的关键，暂估与实收回写都靠它 */
    record PurchaseInboundLine(Long purchaseItemId, long productId, BigDecimal qty,
                               String batchNo, BigDecimal unitPrice) {
    }

    record PurchaseInboundRequest(long warehouseId, String supplierName, Long supplierId,
                                  long purchaseOrderId, boolean needInspection, String remark,
                                  List<PurchaseInboundLine> lines) {
    }

    /** 采购收货建单：生成草稿入库单与明细，单号由仓储自己按规则生成 */
    CreatedInbound createPurchaseInbound(PurchaseInboundRequest request);

    /** 按 id 取单，不存在返回 null */
    InboundOrderView inbound(long inboundOrderId);

    /** 按 id 批量取单，返回 id → 视图 */
    Map<Long, InboundOrderView> inbounds(Collection<Long> inboundOrderIds);

    /** 采购单回链，非采购来源返回 null */
    Long purchaseOrderIdOf(long inboundOrderId);

    /** 采购单名下入库单总数 */
    int countByPurchaseOrder(long purchaseOrderId);

    /** 采购单名下还没走完的入库单（草稿 / 待检），用于「在途」与「不能取消/删除」的判断 */
    int countOpenByPurchaseOrder(long purchaseOrderId);

    /** 批量版：采购单 id → 未完成入库单数，列表页一次问清楚 */
    Map<Long, Integer> openCountByPurchaseOrder(Collection<Long> purchaseOrderIds);

    /** 采购单名下的入库单，按 id 倒序 */
    List<InboundOrderView> byPurchaseOrder(long purchaseOrderId);

    /** 在途数量：按采购明细汇总草稿与待检入库单的数量 */
    Map<Long, BigDecimal> receivingQtyByPurchaseItem(long purchaseOrderId);

    /** 实收数量：按采购明细汇总某张入库单的数量，入库完成时回写采购单用 */
    Map<Long, BigDecimal> receivedQtyByPurchaseItem(long inboundOrderId);

    /** 已完成的采购入库单（挂了采购单的），退货入口用 */
    List<InboundOrderView> completedPurchaseInbounds(int limit);

    /** 某张入库单的明细，按 id 正序 */
    List<InboundLine> lines(long inboundOrderId);

    /** 批量取明细，返回入库单 id → 明细 */
    Map<Long, List<InboundLine>> linesByInbound(Collection<Long> inboundOrderIds);

    /** 已完成的入库单，按完成日期过滤，供应商为空时取全部；供应商绩效用 */
    List<InboundOrderView> completedInbounds(Long supplierId, LocalDate from, LocalDate to);

    /** 按单号模糊搜索，返回 id 集合，用于把「按入库单号搜索」换成 id 条件 */
    Set<Long> searchIds(String keyword);
}
