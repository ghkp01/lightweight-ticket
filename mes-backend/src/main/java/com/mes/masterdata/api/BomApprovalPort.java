package com.mes.masterdata.api;

/**
 * BOM 审批结果回写契约。
 *
 * <p>审批模块只认识「哪张 BOM、通过还是驳回」，版本状态机仍归基础数据：
 * 通过后自动停用同物料旧版本并启用本版本，驳回则退回草稿。</p>
 */
public interface BomApprovalPort {

    /** @param outcome APPROVED / REJECTED / DRAFT（撤回） */
    void applyApproval(long bomId, String outcome);
}
