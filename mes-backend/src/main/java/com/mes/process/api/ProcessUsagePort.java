package com.mes.process.api;

import java.util.Map;
import java.util.Set;

/**
 * 工序被引用情况的只读契约（按工作中心汇总）。
 *
 * <p>组织模块删除工作中心前要判断「有没有工序挂在这儿」，列表页还要显示工序数与工序名。
 * 工序表归 process，所以由 process 发布这个只读端口。</p>
 *
 * <p>实现是一个只带 JdbcTemplate 的薄 reader，而不是 {@code ProcessService}：
 * 后者要读工作中心（{@code WorkCenterPort}），工作中心回头再注入它就成构造环。</p>
 */
public interface ProcessUsagePort {

    /** 某工作中心下的工序数与名称串 */
    record ProcessUsage(int processCount, String processNames) {
    }

    /** 工作中心 id → 工序汇总，没有工序的中心不在结果里 */
    Map<Long, ProcessUsage> usageByWorkCenter();

    /** 某工作中心下有哪些工序 */
    Set<Long> processIdsOf(long workCenterId);

    /** 某工作中心下是否还有工序 */
    boolean anyForWorkCenter(long workCenterId);
}
