package com.mes.production.api;

import java.time.LocalDate;

/**
 * 生产历史数据保留：单件流转日志由生产模块自己清理，避免定时任务跨模块写表。
 */
public interface RetentionPort {

    /** 删除创建时间早于指定日期的单件流转日志，返回删除条数 */
    int purgeUnitLogsBefore(LocalDate date);
}
