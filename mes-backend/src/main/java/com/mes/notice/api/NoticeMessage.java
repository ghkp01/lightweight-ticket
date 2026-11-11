package com.mes.notice.api;

/**
 * 一条站内通知的内容。
 *
 * <p>收件人不在这里 —— 「发给谁」由契约方法决定（单人 / 角色 / 组织），
 * 「发什么」才进这个 record。这样调用方不用再记六个位置参数谁先谁后，
 * 也不会把 refType 和 refNo 传反（这是原来最容易出的错，编译期发现不了）。</p>
 *
 * @param category 分类：TASK 待办 / ALERT 预警 / INFO 通知
 * @param title    标题
 * @param content  正文
 * @param refType  关联单据类型，例如 WORK_ORDER / APPROVAL / DEFECT，可为空
 * @param refNo    关联单据号，点击通知时跳转用，可为空
 */
public record NoticeMessage(String category, String title, String content,
                            String refType, String refNo) {

    public static NoticeMessage of(String category, String title, String content) {
        return new NoticeMessage(category, title, content, null, null);
    }

    public static NoticeMessage of(String category, String title, String content,
                                   String refType, String refNo) {
        return new NoticeMessage(category, title, content, refType, refNo);
    }
}
