package com.mes.task.api;

import java.util.Collection;

/**
 * 业务任务契约：别的模块只负责「该有人做这件事了」和「这件事做完了」，
 * 任务怎么建、指派给谁、怎么通知、怎么统计都在任务模块里。
 *
 * <p>做成契约而不是让大家直接写 biz_task，是为了让「谁负责」只有一处定义，
 * 将来加新环节只加一个任务类型，不用把通知逻辑抄第五遍。</p>
 */
public interface TaskPort {

    /** 开一条任务；同类型同单号已有未完成任务时静默跳过（幂等） */
    void open(TaskDraft draft);

    /** 关闭某张单据的某类任务，没有未完成任务时什么都不做 */
    void close(String taskType, String refNo, String doneBy, String note);

    /** 关闭某类任务的全部未完成记录，用于「一次动作覆盖全量」的场景 */
    int closeAll(String taskType, String doneBy, String note);

    /**
     * 按单据主键批量关闭：一次动作只覆盖它真正处理过的那几张单。
     *
     * <p>跑 MRP 会算到很多订单，但只有被 BOM 需求覆盖到的订单才算「算过了」；
     * 用 {@link #closeAll} 会把还没下达工单的订单也一并关掉。</p>
     */
    int closeByRef(String taskType, String refType, Collection<Long> refIds, String doneBy, String note);
}
