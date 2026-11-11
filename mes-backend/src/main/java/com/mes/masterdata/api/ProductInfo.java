package com.mes.masterdata.api;

import java.math.BigDecimal;

/**
 * 产品（物料）对外只读视图。
 *
 * <p>只带跨模块真正用得到的字段：单号里要展示的编码 / 名称 / 规格 / 单位、
 * 成本核算要用的档案成本与移动平均成本、库存预警要用的安全库存。</p>
 *
 * <p>{@code traceMode} 是默认追溯方式（NONE / SERIAL / LOT）：工单建单时会快照过去，
 * 之后判定都读工单上的值，所以产品这一栏改了下游在制工单不受影响。</p>
 *
 * <p>{@code defaultSupplierId} 是采购下单时自动带出的默认供应商（可空）；
 * 只有采购模块在「没选供应商」时用它兜底，选过了就按选的来。</p>
 */
public record ProductInfo(long id, String code, String name, String spec, String unit, String category,
                          BigDecimal price, BigDecimal cost, BigDecimal avgCost,
                          BigDecimal safetyStock, BigDecimal lossRate,
                          String traceMode, boolean enabled, Long defaultSupplierId,
                          String source, int leadDays) {

    /** 兼容旧调用：没有来源/提前期 */
    public ProductInfo(long id, String code, String name, String spec, String unit, String category,
                       BigDecimal price, BigDecimal cost, BigDecimal avgCost,
                       BigDecimal safetyStock, BigDecimal lossRate, String traceMode,
                       boolean enabled, Long defaultSupplierId) {
        this(id, code, name, spec, unit, category, price, cost, avgCost, safetyStock, lossRate,
                traceMode, enabled, defaultSupplierId, null, 0);
    }

    /** 兼容旧调用：只有 SERIAL 才叫「单件管理」 */
    public boolean serialManaged() {
        return "SERIAL".equals(traceMode);
    }

    /** 默认来源：老数据没填时按物料类型兜底 */
    public boolean make() {
        if (source != null && !source.isBlank()) {
            return "MAKE".equalsIgnoreCase(source);
        }
        return !"RAW".equalsIgnoreCase(category) && !"PURCHASED".equalsIgnoreCase(category);
    }
}
