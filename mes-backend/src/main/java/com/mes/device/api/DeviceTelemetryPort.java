package com.mes.device.api;

import java.time.LocalDate;

/**
 * 设备采集数据保留：定时清理任务只认这个端口，不直接操作设备表。
 */
public interface DeviceTelemetryPort {

    /** 删除采集时间早于指定日期的原始数据，返回删除条数 */
    int purgeBefore(LocalDate date);
}
