package com.mes.masterdata.api;

import java.math.BigDecimal;

/**
 * 生效 BOM 的一行用料，供生产工单与 MRP 取料。
 *
 * <p>{@code qtyPerUnit} 已按 BOM 头的基础数量折算成「每个父件用量」，
 * 调用方直接乘以计划数量与损耗系数即可，不需要再关心 BOM 头。</p>
 */
public record BomLineInfo(long id, long materialId, BigDecimal qtyPerUnit, Integer operationSeq,
                          String substituteGroup, boolean key, String remark,
                          Long substituteMaterialId) {
}
