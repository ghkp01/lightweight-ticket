package com.mes.sales.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 报价评审提醒：定时任务按截止日期提醒评审人与报价人，不直接读销售表。
 */
public interface QuotationPort {

    record ReviewReminder(String quoteNo, String createdBy, LocalDate dueDate,
                          boolean overdue, List<String> pendingReviewers) {
    }

    /** 截止日在今天起 aheadDays 天内（含已逾期）的待评审报价 */
    List<ReviewReminder> reviewReminders(int aheadDays);
}
