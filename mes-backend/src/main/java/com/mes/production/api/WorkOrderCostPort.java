package com.mes.production.api;

import java.math.BigDecimal;

/**
 * 工单成本字段的写入契约。
 *
 * <p>料工费算完要落回工单上的 {@code material_cost / labor_cost / overhead_cost / outsource_cost /
 * total_cost / unit_cost / costed_at} 七个字段。这些字段属于工单，规则（递 version、动 updated_at）
 * 也属于工单，所以成本模块不直接 update，而是发一条命令。</p>
 *
 * <p>外协费与人工费并列：外协干的活不写报工，所以人工里本来就不含它，单独一列才看得清
 * 「这单多少钱花在厂内、多少钱花在外协」。</p>
 *
 * <p>实现是薄的 {@code WorkOrderCostWriter}（只带 JdbcTemplate）：成本模块在生产的依赖上游，
 * 直接注入 {@link WorkOrderPort} 的实现会成构造环。</p>
 */
public interface WorkOrderCostPort {

    /** 写入单张工单的成本快照字段（同时置 costed_at） */
    void writeCost(long workOrderId, BigDecimal materialCost, BigDecimal laborCost,
                   BigDecimal overheadCost, BigDecimal outsourceCost,
                   BigDecimal totalCost, BigDecimal unitCost);
}
