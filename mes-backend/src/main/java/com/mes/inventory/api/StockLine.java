package com.mes.inventory.api;

import java.math.BigDecimal;

/**
 * 某产品在某仓的库存快照，只读。
 *
 * <p>只带「量」这三件事：结存、锁定、可用。不允许把平均成本、金额这些派生字段塞进来 ——
 * 成本口径属于成本模块，库存服务只回答「有多少」。</p>
 */
public record StockLine(long productId, long warehouseId, BigDecimal qty, BigDecimal lockedQty) {

    /** 可发数量 = 结存 - 锁定 */
    public BigDecimal available() {
        BigDecimal locked = lockedQty == null ? BigDecimal.ZERO : lockedQty;
        return (qty == null ? BigDecimal.ZERO : qty).subtract(locked);
    }
}
