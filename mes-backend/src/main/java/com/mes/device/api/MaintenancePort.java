package com.mes.device.api;

/**
 * 设备保养对外契约：定时任务每天问一次「今天该保养的通知发了没」。
 */
public interface MaintenancePort {

    /** 生成今日保养提醒，返回新增/更新的保养任务数 */
    int dailyUpkeep();
}
