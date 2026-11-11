package com.mes.purchase.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * 采购单据的对外只读契约：给仓储显示单号、金额与退货统计。
 *
 * <p>入库单列表要显示「从哪张采购单收的货」，入库异常概览要显示「本月采购退货单数与金额」，
 * 完成入库算单位成本时要回落到采购价。这三件事以前都是 {@code join purchase_order} /
 * {@code purchase_return} / {@code purchase_order_item}，属于采购的表却散在仓储的 SQL 里。</p>
 *
 * <p>和 {@link SupplyPort} 的分工：那边是 MRP 要的供应数量，这边是单据级的展示与金额。</p>
 */
public interface PurchaseDocPort {

    /** 退货汇总：单数 + 金额 */
    record ReturnStat(int count, BigDecimal amount) {
    }

    /** 按 id 批量取采购单号，找不到的不在结果里 */
    Map<Long, String> orderNos(Collection<Long> purchaseOrderIds);

    /** 按采购明细 id 批量取单价，找不到的不在结果里 */
    Map<Long, BigDecimal> itemPrices(Collection<Long> purchaseOrderItemIds);

    /** 入库单 id → 该入库单已完成的退货（单数 + 金额） */
    Map<Long, ReturnStat> completedReturnStatByInbound(Collection<Long> inboundOrderIds);

    /** 指定日期（含）之后完成的退货汇总，入库异常概览用 */
    ReturnStat completedReturnsSince(LocalDate from);
}
