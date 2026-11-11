package com.mes.process.api;

import java.util.Collection;
import java.util.Map;

/**
 * 工序主数据的对外只读契约。
 *
 * <p>工序是「车间可执行的工序目录」，工艺路线与工单工序都引用它。别的模块拿到工序 id 之后
 * 要么只要一个名字（检验项挂在哪道工序、不良记录属于哪道工序），
 * 要么还要它的产能口径（有限产能排产按工序所属工作中心占用日产能）。
 * 以前这些跨模块字段都是直接 {@code join process}，工序表一改就要跟着改。</p>
 */
public interface ProcessPort {

    /** 工序视图：排产需要 日产能 + 所属工作中心，展示需要名称 */
    record ProcessInfo(long id, String code, String name, boolean enabled,
                       Integer dailyCapacityMinutes, Long workCenterId) {
    }

    /** 工序名称，不存在返回 null */
    String name(long processId);

    /** 批量取工序名称，返回 id → 名称；找不到的 id 不出现在结果里 */
    Map<Long, String> names(Collection<Long> processIds);

    /** 单个工序，不存在返回 null */
    ProcessInfo process(long processId);

    /** 全部工序，返回 id → 工序 */
    Map<Long, ProcessInfo> processes();
}
