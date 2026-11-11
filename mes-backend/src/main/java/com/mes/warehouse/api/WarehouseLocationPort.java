package com.mes.warehouse.api;

import java.util.Collection;
import java.util.Map;

/**
 * 库位只读视图的契约（库位编码与名称）。
 *
 * <p>库存模块展示批次时要显示「在哪个库位」，库位表归仓储所有，因此由仓储发布一个只读端口。
 * 实现刻意是薄的一个 reader，而不是 {@code LocationService}：后者依赖库存契约（占用统计、移库），
 * 库存回头再注入它就成了构造环。</p>
 */
public interface WarehouseLocationPort {

    /** 库位只读视图 */
    record LocationRef(long id, long warehouseId, String code, String name) {
    }

    /** 批量取库位，返回 id → 库位；查不到的不在 map 里 */
    Map<Long, LocationRef> locations(Collection<Long> locationIds);
}
