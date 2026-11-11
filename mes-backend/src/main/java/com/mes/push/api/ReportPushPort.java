package com.mes.push.api;

/**
 * 经营日报推送契约：定时任务只认这个端口，不关心具体推到飞书还是钉钉。
 */
public interface ReportPushPort {

    /** 按配置推一次经营日报；未启用或未配置 Webhook 时返回 false，不抛异常打断定时任务 */
    boolean dailyPush();
}
