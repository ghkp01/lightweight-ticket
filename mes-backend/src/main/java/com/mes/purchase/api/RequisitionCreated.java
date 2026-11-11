package com.mes.purchase.api;

/**
 * 采购申请创建结果。
 *
 * <p>把单号一起返回，是为了让调用方（MRP 一键生成）不必再回头查 {@code purchase_requisition} 表 ——
 * 那张表属于采购模块，别的模块不该为了一个单号去读它。</p>
 */
public record RequisitionCreated(long id, String reqNo) {
}
