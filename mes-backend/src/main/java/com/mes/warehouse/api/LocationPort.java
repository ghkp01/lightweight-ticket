package com.mes.warehouse.api;

/**
 * 库位的对外契约。
 *
 * <p>入库时要把批次挂到库位上，而这个动作的源头不只在仓储：
 * 手工入库（库存模块的接口）也会上架。库位表的细节不该外泄。</p>
 */
public interface LocationPort {

    /**
     * 把某一批库存挂到库位。库位编码为空或找不到时静默跳过 —— 上架是增强能力，
     * 不能因为库位配错导致入库失败。
     */
    void assignBatch(long productId, long warehouseId, String batchNo, String locationCode);
}
