package com.mes.planning.api;

import java.time.LocalDate;
import java.util.List;

/**
 * BOM 任务提醒：定时任务按完成日期提醒技术员与派单人。
 */
public interface BomTaskReminderPort {

    record TaskReminder(String orderNo, String productCode, String assignedBy,
                        String assigneeUsername, LocalDate dueDate, boolean overdue) {
    }

    /** 截止日在今天起 aheadDays 天内（含已逾期）的待处理任务 */
    List<TaskReminder> taskReminders(int aheadDays);
}
