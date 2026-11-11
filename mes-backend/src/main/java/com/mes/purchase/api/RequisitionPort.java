package com.mes.purchase.api;

/**
 * 采购申请的对外契约。
 *
 * <p>目前只有计划模块（MRP 一键生成采购申请）需要跨模块建单。
 * 审批、转采购单这些仍走采购模块自己的流程，不进契约。</p>
 */
public interface RequisitionPort {

    /** 建一张草稿采购申请；金额分级审批在提交时按配置判断 */
    RequisitionCreated create(RequisitionDraft draft);
}
