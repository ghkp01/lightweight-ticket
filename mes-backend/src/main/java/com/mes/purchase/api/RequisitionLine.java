package com.mes.purchase.api;

import java.math.BigDecimal;

/**
 * 采购申请明细行（对外契约）。
 *
 * @param productId         物料
 * @param qty               申请数量
 * @param targetPrice       目标单价，可空
 * @param suggestSupplierId 建议供应商，可空
 * @param needQuote         是否需要询价
 * @param remark            备注
 * @param category          行级采购类别，可空（空表示跟随单据头）；行级分流按它选审批链
 */
public record RequisitionLine(Long productId, BigDecimal qty, BigDecimal targetPrice,
                              Long suggestSupplierId, Boolean needQuote, String remark,
                              String category) {

    /** 兼容老调用方：不带行级类别，跟随单据头 */
    public RequisitionLine(Long productId, BigDecimal qty, BigDecimal targetPrice,
                           Long suggestSupplierId, Boolean needQuote, String remark) {
        this(productId, qty, targetPrice, suggestSupplierId, needQuote, remark, null);
    }
}
