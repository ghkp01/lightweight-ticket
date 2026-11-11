package com.mes.job;

import com.mes.platform.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务的触发器：只负责按 cron 叫起 JobService，具体做什么放在 JobService 里，
 * 这样页面上的「立即执行」和定时触发走同一条代码路径。
 * 总开关 job.enabled=false 时定时器空转，不影响手工执行。
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final JobService jobService;
    private final ConfigService configService;

    public ScheduledJobs(JobService jobService, ConfigService configService) {
        this.jobService = jobService;
        this.configService = configService;
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void deliveryAlert() {
        trigger("DELIVERY_ALERT");
    }

    @Scheduled(cron = "0 10 8 * * *")
    public void shortageScan() {
        trigger("SHORTAGE_SCAN");
    }

    @Scheduled(cron = "0 30 1 * * *")
    public void costRefresh() {
        trigger("COST_REFRESH");
    }

    @Scheduled(cron = "0 0 7 * * *")
    public void deviceUpkeep() {
        trigger("DEVICE_UPKEEP");
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void noticeCleanup() {
        trigger("NOTICE_CLEANUP");
    }

    @Scheduled(cron = "0 20 2 * * *")
    public void offlineCleanup() {
        trigger("OFFLINE_CLEANUP");
    }

    @Scheduled(cron = "0 5 * * * *")
    public void approvalTimeout() {
        trigger("APPROVAL_TIMEOUT");
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void domainEventCleanup() {
        trigger("DOMAIN_EVENT_CLEANUP");
    }

    @Scheduled(cron = "0 20 8 * * 1-5")
    public void mrpAlert() {
        trigger("MRP_ALERT");
    }

    @Scheduled(cron = "0 30 8 * * *")
    public void dailyReport() {
        trigger("DAILY_REPORT");
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void retentionCleanup() {
        trigger("RETENTION_CLEANUP");
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void taskOverdue() {
        trigger("TASK_OVERDUE");
    }

    @Scheduled(cron = "0 15 9 * * *")
    public void quoteBomReminder() {
        trigger("QUOTE_BOM_REMINDER");
    }

    private void trigger(String code) {
        if (!configService.booleanValue("job.enabled", true)) {
            return;
        }
        try {
            jobService.run(code, "SCHEDULED");
        } catch (Exception e) {
            // JobService 内部已记录失败日志，这里只兜底，避免定时线程被打断
            log.warn("定时任务 {} 执行异常：{}", code, e.getMessage());
        }
    }
}
