package com.mes.purchase.api;

/**
 * 审批结果回写采购申请。
 *
 * <p>做成窄 writer 而不是让审批去依赖 RequisitionService：审批与采购是互相引用的两方，
 * 直接相互注入会在构造期成环。这里只带一个状态回写，实现方只碰采购申请自己的表。</p>
 */
public interface RequisitionApprovalPort {

    /**
     * @param lineIds 行级分流时这一组审批覆盖的明细行 id（逗号分隔）；为空表示整单级审批
     * @param outcome APPROVED / REJECTED / DRAFT（撤回）
     */
    void applyApproval(long reqId, String lineIds, String outcome);
}
