package com.mes.cost.api;

import java.math.BigDecimal;

/**
 * 单张工单的成本快照（对外契约）。
 *
 * <p>原来这里是 {@code Map<String, Object>}，字段名靠 put 时手写，调用方 get 错了类型
 * 要到运行期才炸；换成 record 之后字段名、类型、顺序都在编译期定死，
 * 也才可能被序列化后跨进程传。</p>
 *
 * <p>{@code outsourceCost} 与料工费并列：外协费是「外协厂干的活」，既不是本厂材料消耗，
 * 也不进人工，混进任何一项都会让成本构成失真。</p>
 */
public record WorkOrderCost(long workOrderId, String workNo, BigDecimal qualifiedQty,
                            BigDecimal materialCost, BigDecimal laborCost, BigDecimal overheadCost,
                            BigDecimal outsourceCost, BigDecimal totalCost, BigDecimal unitCost) {
}
