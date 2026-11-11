package com.mes.purchase.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * 采购供应的对外只读契约：MRP 算缺口时要用到的三笔数。
 *
 * <p>物料需求计算的公式是「毛需求 + 安全库存 − 可用 − 在途 − 已申请未转」，
 * 其中「在途」「已申请未转」「最近价格与交期」都在采购模块里。
 * 以前这几个数是用一条大 CTE 直接 join {@code purchase_order} / {@code purchase_requisition} /
 * {@code supplier_price} / {@code supplier} 算出来的 —— 计划模块复制了一遍采购的口径。</p>
 *
 * <p>这里按物料 id 批量给数，采购只负责「有多少在途、有多少待转」，
 * 「够不够、缺多少」仍然是计划模块自己判断。</p>
 */
public interface SupplyPort {

    /** 某物料最近一次有效报价 */
    record PriceQuote(long productId, Long supplierId, BigDecimal price, Integer leadDays) {
    }

    /** 供应商概要：buyer 为负责该供应商的采购员用户名，可为空 */
    record SupplierBrief(long id, String name, Integer leadDays, String buyer) {
    }

    /** 在途：已确认未收货的采购单里，该物料还没收的数量 */
    Map<Long, BigDecimal> onWayQty(Collection<Long> productIds);

    /** 已申请未转：已提交 / 已批准 / 部分转单的申请里，该物料还没转采购单的数量 */
    Map<Long, BigDecimal> pendingRequisitionQty(Collection<Long> productIds);

    /** 价格库里的有效报价（valid_to 为空或未过期），按 valid_from 倒序取最新一条 */
    Map<Long, PriceQuote> lastPriceQuotes(Collection<Long> productIds);

    /** 最近一张已确认及以上状态的采购单里的价格与供应商 */
    Map<Long, PriceQuote> lastPurchaseQuotes(Collection<Long> productIds);

    /** 按 id 批量取供应商 */
    Map<Long, SupplierBrief> suppliers(Collection<Long> supplierIds);
}
