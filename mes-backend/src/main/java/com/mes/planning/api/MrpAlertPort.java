package com.mes.planning.api;

/**
 * 物料缺口预警对外契约：定时任务不需要知道 MRP 怎么算，只要「扫一遍、该提醒的提醒」。
 */
public interface MrpAlertPort {

    /**
     * 把「已过建议下单日仍有缺口」的物料推给对应采购员。
     *
     * <p>每个物料按其建议供应商的 {@code buyer} 字段定位采购员，直接推到本人；
     * 供应商没指定采购员的，回退给计划角色（PLANNER）。</p>
     *
     * <p>同一天同一物料只推一次（按 {@code mrp_alert_log} 去重），手工重跑不会重复轰炸。</p>
     *
     * @return 本轮新增提醒的物料数
     */
    int alertOverdueShortages();
}
