package com.mes.cost.api;

/**
 * 绩效单的审批回写契约。
 *
 * <p>绩效单可以配一条 {@code PERFORMANCE} 审批流；审批在审批中心走完之后，
 * 结果要回到绩效单上（已通过 / 已驳回）。审批模块不认识绩效表，所以由成本侧发布这个窄接口。</p>
 *
 * <p>实现是薄的 {@code PerformanceApprovalWriter}（只带 JdbcTemplate）：
 * 成本本身依赖审批契约（提交审批），审批回头注入成本的业务类会成构造环。</p>
 */
public interface PerformanceApprovalPort {

    /**
     * 回写审批结果。
     *
     * @param outcome APPROVED（通过）/ REJECTED（驳回），其它值按撤回处理回草稿
     */
    void applyApproval(long sheetId, String outcome);
}
