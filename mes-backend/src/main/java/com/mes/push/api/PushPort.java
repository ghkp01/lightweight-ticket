package com.mes.push.api;

/**
 * 推送通道对外契约：站内通知落库后，把同一条消息顺带发到已登记设备。
 *
 * <p>实现必须是「静默失败」语义 —— 推不出去不能影响业务主流程，
 * 所以这里不声明 checked 异常，调用方也不需要 try。</p>
 */
public interface PushPort {

    /** 按接收人找设备并分发；接收人没有登记设备时什么也不做 */
    void dispatch(String receiver, PushMessage message);
}
