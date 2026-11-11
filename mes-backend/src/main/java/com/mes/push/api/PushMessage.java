package com.mes.push.api;

/**
 * 一条要推到手机的推送消息。
 *
 * <p>字段和 {@code notice.api.NoticeMessage} 刻意保持同构，但<b>不复用那个 record</b>：
 * 复用会让 {@code push} 反向依赖 {@code notice}，两个模块互相捏着就再也拆不开。
 * 同构字段在 {@code NoticeService} 里一行映射完成，代价远小于一个模块环。</p>
 */
public record PushMessage(String category, String title, String content, String refType, String refNo) {

    /** 不带业务单号的推送，例如「报表已就绪」 */
    public static PushMessage of(String category, String title, String content) {
        return new PushMessage(category, title, content, null, null);
    }
}
