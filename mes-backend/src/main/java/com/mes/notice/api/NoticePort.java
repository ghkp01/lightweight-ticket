package com.mes.notice.api;

import java.util.List;

/**
 * 站内通知对外契约。
 *
 * <p>审批、安灯、设备、生产、质检、退货、仓储这些模块都要「给相关人留一条消息」，
 * 但它们不该知道通知落在哪张表、推送怎么发。原来它们直接注入 {@code NoticeService}，
 * 等于把通知的存储细节焊死在九个模块里；现在只依赖这个接口。</p>
 *
 * <p><b>失败语义</b>：所有方法都不抛异常 —— 通知是辅助能力，发不出去不能拖垮主流程
 * （落库失败与推送失败都静默降级，与改造前一致）。</p>
 */
public interface NoticePort {

    /** 发给单个用户 */
    void push(String receiver, NoticeMessage message);

    /** 发给若干角色下的所有启用用户 */
    void pushToRoles(List<String> roles, NoticeMessage message);

    /** 发给某个组织（含下级组织）的所有启用用户 */
    void pushToOrg(long orgId, NoticeMessage message);

    /**
     * 清理已读通知，返回清理条数（定时任务用）。days 为空或 0 表示清空全部已读。
     * 通知的保留策略属于通知模块自己，调用方只需要「顺手清一下」；未读通知永远不动。
     */
    int purgeRead(Integer days);
}
