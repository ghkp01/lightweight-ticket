package com.mes.production.api;

import java.util.Collection;
import java.util.Map;

/**
 * 工单工序的引用统计与改名命令。
 *
 * <p>工序主数据改名时要同步历史工单上的工序名；工序/工作中心删除前要判断「有没有被工单用过」。
 * 这些数据在 {@code work_order_operation} 里，归生产所有，所以由生产发布这个端口。</p>
 *
 * <p>实现是薄的 {@code WorkOrderOperationReader}（只带 JdbcTemplate）：
 * 工序模块与组织模块都在生产的依赖上游，直接注入 {@link WorkOrderPort} 的实现会成构造环。</p>
 */
public interface WorkOrderOperationPort {

    /** 指定工序 id → 已生成的工单工序条数，没被用过的不在结果里（分页列表只传当前页的工序） */
    Map<Long, Integer> operationCounts(Collection<Long> processIds);

    /** 这些工序是否已经用在工单上（删除前校验） */
    boolean anyOperationForProcesses(Collection<Long> processIds);

    /** 工序改名：把历史工单工序上的名称同步成最新的（快照字段，只改名字不改其它） */
    void renameOperationsOfProcess(long processId, String name);
}
