package com.mes.cost.api;

/**
 * 成本核算的对外契约。
 *
 * <p>生产模块在报工、成品入库、工单完工这些节点要「顺手重算一下成本」，
 * 定时任务要「批量补算未结算工单」；它们不该知道料工费怎么取、快照写哪张表。</p>
 */
public interface CostPort {

    /** 重算单张工单成本并写快照，返回成本明细 */
    WorkOrderCost recalc(long workOrderId);

    /**
     * 批量补算：status 为空时只补算还没有成本快照的工单，返回处理条数。
     */
    int recalcAll(String status);
}
