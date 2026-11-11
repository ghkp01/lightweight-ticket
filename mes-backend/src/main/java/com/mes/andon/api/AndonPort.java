package com.mes.andon.api;

import java.util.Collection;

/**
 * 异常安灯对外契约：生产侧发现缺料时拉灯，仓储侧到货齐套后消除。
 */
public interface AndonPort {

    /**
     * 按工单目前缺什么料，拉起（或复用）一条缺料安灯。
     *
     * @return 是否新拉起了安灯；同一工单缺料已存在时返回 {@code false}，避免刷屏
     */
    boolean raiseMaterialShortage(long workOrderId);

    /**
     * 这些工单的缺料已经解决：把「下达时库存不足」的未关安灯置为已解决。
     *
     * <p>到货入库后由仓储触发 —— 判断齐套要算 BOM 待领量与结存，那是仓储与生产的活；
     * 关灯本身属于安灯自己的状态机，所以只发一条命令，不暴露 {@code andon_call} 表。</p>
     *
     * @return 关闭的安灯条数
     */
    int resolveMaterialShortage(Collection<Long> workOrderIds);
}
