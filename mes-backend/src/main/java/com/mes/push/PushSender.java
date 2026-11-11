package com.mes.push;

import java.util.List;

/**
 * 推送通道适配点：厂商推送（极光 / 个推）、微信公众号模板消息都实现这个接口，
 * 通过 Spring 注入即可替换，业务侧只认 NoticeService.push，不关心通道。
 */
public interface PushSender {

    /** 是否处理该 provider（JPUSH / GETUI / WECHAT / NONE） */
    boolean supports(String provider);

    /** 发一条推送；实现里自己保证异常不外抛，失败只记录 */
    void send(PushRequest request);

    /**
     * @param username 接收人账号
     * @param category TASK / ALERT / INFO
     * @param devices  该用户已登记且启用的设备
     */
    record PushRequest(String username, String category, String title, String content,
                       String refType, String refNo, List<PushDevice> devices) {
    }

    record PushDevice(long id, String platform, String provider, String token) {
    }
}
