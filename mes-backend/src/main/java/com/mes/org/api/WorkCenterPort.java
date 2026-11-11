package com.mes.org.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 工作中心的对外只读契约。
 *
 * <p>工作中心是车间里真正占用产能的执行单元，排产要按它的日产能算负荷。
 * 以前排产/甘特/插单模拟都是直接 {@code join work_center c on c.id = pr.work_center_id}，
 * 等于把「产能怎么算」这件事复制到了计划模块里。</p>
 */
public interface WorkCenterPort {

    /** 工作中心视图 */
    record WorkCenterInfo(long id, String code, String name, String type,
                          int dailyCapacityMinutes, boolean enabled) {
    }

    /**
     * 某工作中心的设备产能：只统计启用且状态为运行 / 待机的设备。
     * 没维护设备的工作中心不出现在结果里（排产时回退到中心日产能）。
     */
    record DeviceCapacity(int deviceCount, int capacityMinutes) {
    }

    /** 全部工作中心；{@code onlyEnabled} 为 true 时只返回启用的 */
    List<WorkCenterInfo> centers(boolean onlyEnabled);

    /** 按 id 批量取，找不到的不在结果里 */
    Map<Long, WorkCenterInfo> centersByIds(Collection<Long> centerIds);

    /** 工作中心 id → 设备产能 */
    Map<Long, DeviceCapacity> deviceCapacity();
}
