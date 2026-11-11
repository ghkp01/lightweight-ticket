package com.mes.task.api;

import java.time.LocalDate;

/**
 * 开一条任务需要的全部信息。
 *
 * <p>指派是两段式：{@code assigneeRole} 一定给，是兜底池；{@code assigneeUsername} 给了就指到人。
 * 业务侧往往只知道「该由谁这个岗位做」，指到具体某个人是管理动作（在任务页改派），
 * 所以这里不强制要求用户名。</p>
 *
 * @param taskType 任务类型，见 {@link TaskTypes}
 * @param refType  关联单据类型
 * @param refId    关联单据主键，可为空
 * @param refNo    关联单据号，同类型同单号只保留一条未完成任务
 */
public record TaskDraft(String taskType, String title, String content,
                        String refType, Long refId, String refNo,
                        String assigneeRole, String assigneeUsername,
                        String priority, LocalDate dueDate) {

    /** 默认指派到角色池，优先级普通，明天到期 */
    public static TaskDraft of(String taskType, String title, String content,
                               String refType, Long refId, String refNo, String assigneeRole) {
        return new TaskDraft(taskType, title, content, refType, refId, refNo,
                assigneeRole, null, TaskTypes.PRIORITY_NORMAL, LocalDate.now().plusDays(1));
    }

    public TaskDraft assignee(String username) {
        return new TaskDraft(taskType, title, content, refType, refId, refNo,
                assigneeRole, username, priority, dueDate);
    }

    public TaskDraft priority(String value) {
        return new TaskDraft(taskType, title, content, refType, refId, refNo,
                assigneeRole, assigneeUsername, value, dueDate);
    }

    public TaskDraft dueDate(LocalDate value) {
        return new TaskDraft(taskType, title, content, refType, refId, refNo,
                assigneeRole, assigneeUsername, priority, value);
    }
}
