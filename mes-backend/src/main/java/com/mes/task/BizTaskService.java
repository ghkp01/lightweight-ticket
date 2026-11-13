package com.mes.task;

import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.security.CurrentUser;
import com.mes.task.api.TaskDraft;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务任务的落库、指派与关闭。
 *
 * <p>三件事：</p>
 * <ol>
 *   <li>幂等开单：同一张单据的同一类活只留一条未完成，业务侧重复触发不会刷屏；</li>
 *   <li>落到人：先按角色池建，管理岗（管理员/计划）再改派到具体某个人；</li>
 *   <li>开单即通知：指派到人就单人推送，落在角色池就按角色扇出，走站内信主通道。</li>
 * </ol>
 */
@Service
public class BizTaskService implements TaskPort {

    private static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final NoticePort notice;

    public BizTaskService(JdbcTemplate jdbcTemplate, NoticePort notice) {
        this.jdbcTemplate = jdbcTemplate;
        this.notice = notice;
    }

    // ================================================================ 写入

    @Override
    @Transactional
    public void open(TaskDraft draft) {
        if (draft == null || blank(draft.taskType()) || blank(draft.refNo())) {
            return;
        }
        String role = blank(draft.assigneeRole()) ? "ADMIN" : draft.assigneeRole().trim().toUpperCase();
        Assignee assignee = resolve(draft.assigneeUsername());
        int inserted = jdbcTemplate.update("""
                insert into biz_task (task_type, title, content, ref_type, ref_id, ref_no,
                                      assignee_role, assignee_username, assignee_user_id, assignee_name,
                                      priority, due_date, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (task_type, ref_no) where status = 'OPEN' do nothing
                """, draft.taskType(), draft.title(), draft.content(),
                blank(draft.refType()) ? "OTHER" : draft.refType(), draft.refId(), draft.refNo(),
                role, assignee == null ? null : assignee.username(),
                assignee == null ? null : assignee.id(), assignee == null ? null : assignee.realName(),
                blank(draft.priority()) ? TaskTypes.PRIORITY_NORMAL : draft.priority(),
                draft.dueDate(), CurrentUser.name());
        if (inserted == 0) {
            // 已经有同类未完成任务，不重复通知
            return;
        }
        String due = draft.dueDate() == null ? "" : "，要求 " + draft.dueDate() + " 前完成";
        NoticeMessage message = NoticeMessage.of("TASK", draft.title(),
                (draft.content() == null ? "" : draft.content()) + due,
                draft.refType(), draft.refNo());
        if (assignee != null) {
            notice.push(assignee.username(), message);
        } else {
            notice.pushToRoles(List.of(role), message);
        }
    }

    @Override
    @Transactional
    public void close(String taskType, String refNo, String doneBy, String note) {
        if (blank(taskType) || blank(refNo)) {
            return;
        }
        jdbcTemplate.update("""
                update biz_task set status = 'DONE', done_at = now(), done_by = ?, done_note = ?,
                                    updated_at = now()
                where task_type = ? and ref_no = ? and status = 'OPEN'
                """, blank(doneBy) ? CurrentUser.name() : doneBy, blank(note) ? "上游动作已完成，系统自动关闭" : note,
                taskType, refNo);
    }

    @Override
    @Transactional
    public int closeAll(String taskType, String doneBy, String note) {
        if (blank(taskType)) {
            return 0;
        }
        return jdbcTemplate.update("""
                update biz_task set status = 'DONE', done_at = now(), done_by = ?, done_note = ?,
                                    updated_at = now()
                where task_type = ? and status = 'OPEN'
                """, blank(doneBy) ? CurrentUser.name() : doneBy,
                blank(note) ? "批量完成" : note, taskType);
    }

    @Override
    @Transactional
    public int closeByRef(String taskType, String refType, Collection<Long> refIds, String doneBy, String note) {
        if (blank(taskType) || blank(refType) || refIds == null || refIds.isEmpty()) {
            return 0;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(refIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(blank(doneBy) ? CurrentUser.name() : doneBy);
        args.add(blank(note) ? "上游动作已覆盖到这几张单" : note);
        args.add(taskType);
        args.add(refType);
        args.addAll(refIds);
        // 只有本类任务、本类单据、且还在未完成状态的才关，已关的不会被重复计数
        return jdbcTemplate.update("""
                update biz_task set status = 'DONE', done_at = now(), done_by = ?, done_note = ?,
                                    updated_at = now()
                where task_type = ? and ref_type = ? and status = 'OPEN'
                  and ref_id in (""" + marks + ")", args.toArray());
    }

    /** 改派：把角色池里的任务指到具体某个人，或传空用户名退回角色池 */
    @Transactional
    public Map<String, Object> assign(long id, String username) {
        Map<String, Object> task = load(id);
        if (!"OPEN".equals(String.valueOf(task.get("status")))) {
            throw new BizException(4001, "任务已完成，不能再改派");
        }
        if (blank(username)) {
            jdbcTemplate.update("""
                    update biz_task set assignee_username = null, assignee_user_id = null,
                                        assignee_name = null, updated_at = now()
                    where id = ?
                    """, id);
            return detail(id);
        }
        Assignee assignee = resolve(username);
        if (assignee == null) {
            throw new BizException(1002, "改派对象不存在或已停用");
        }
        jdbcTemplate.update("""
                update biz_task set assignee_username = ?, assignee_user_id = ?, assignee_name = ?,
                                    updated_at = now()
                where id = ?
                """, assignee.username(), assignee.id(), assignee.realName(), id);
        notice.push(assignee.username(), NoticeMessage.of("TASK", "任务已指派给你：" + task.get("title"),
                "单据 " + task.get("ref_no") + "，请在任务页处理。",
                String.valueOf(task.get("ref_type")), String.valueOf(task.get("ref_no"))));
        return detail(id);
    }

    /** 完成任务：只有被指派的人、同角色的同事或管理员能点 */
    @Transactional
    public Map<String, Object> done(long id, String note) {
        Map<String, Object> task = load(id);
        if (!"OPEN".equals(String.valueOf(task.get("status")))) {
            throw new BizException(4001, "该任务已经完成，不用重复提交");
        }
        String me = CurrentUser.name();
        String myRole = CurrentUser.role();
        String owner = task.get("assignee_username") == null ? null : String.valueOf(task.get("assignee_username"));
        String role = String.valueOf(task.get("assignee_role"));
        boolean allowed = "ADMIN".equals(myRole)
                || (owner != null && owner.equals(me))
                || (owner == null && role.equals(myRole));
        if (!allowed) {
            throw new BizException(4003, "这条任务不是你负责的，请联系管理员改派");
        }
        jdbcTemplate.update("""
                update biz_task set status = 'DONE', done_at = now(), done_by = ?, done_note = ?,
                                    updated_at = now()
                where id = ? and status = 'OPEN'
                """, me, blank(note) ? "已处理" : note, id);
        return detail(id);
    }

    // ================================================================ 查询

    /** 我的任务：点名给我的，加上我所处角色池里还没认领的 */
    public List<Map<String, Object>> mine(String username, String status, String taskType) {
        QueryParts parts = mineParts(username, status, taskType);
        parts.args().add(MAX_ROWS);
        return jdbcTemplate.queryForList(baseSelect() + parts.where() + orderBy() + " limit ?",
                parts.args().toArray());
    }

    /** 我的任务分页：任务会持续累积，列表页改为后端分页 */
    public PageResult minePage(String username, String status, String taskType, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        QueryParts countParts = mineParts(username, status, taskType);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from biz_task t " + countParts.where(),
                Long.class, countParts.args().toArray());
        QueryParts parts = mineParts(username, status, taskType);
        parts.args().add(safeSize);
        parts.args().add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                baseSelect() + parts.where() + orderBy() + " limit ? offset ?", parts.args().toArray());
        return new PageResult(records, total == null ? 0 : total, safePage, safeSize);
    }

    /** 全部任务：管理岗用来发现「没人认领」和「到期没做」的活 */
    public List<Map<String, Object>> all(String status, String taskType, String keyword) {
        QueryParts parts = allParts(status, taskType, keyword);
        parts.args().add(MAX_ROWS);
        return jdbcTemplate.queryForList(baseSelect() + parts.where() + orderBy() + " limit ?",
                parts.args().toArray());
    }

    /** 全部任务分页 */
    public PageResult allPage(String status, String taskType, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        QueryParts countParts = allParts(status, taskType, keyword);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from biz_task t " + countParts.where(),
                Long.class, countParts.args().toArray());
        QueryParts parts = allParts(status, taskType, keyword);
        parts.args().add(safeSize);
        parts.args().add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                baseSelect() + parts.where() + orderBy() + " limit ? offset ?", parts.args().toArray());
        return new PageResult(records, total == null ? 0 : total, safePage, safeSize);
    }

    private record QueryParts(StringBuilder where, List<Object> args) {
    }

    private QueryParts mineParts(String username, String status, String taskType) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (!blank(status) && !"ALL".equalsIgnoreCase(status)) {
            where.append(" and t.status = ? ");
            args.add(status.trim().toUpperCase());
        }
        where.append("""
                 and (t.assignee_username = ?
                      or (t.assignee_username is null
                          and t.assignee_role = (select role from sys_user where username = ?)))
                """);
        args.add(username);
        args.add(username);
        if (!blank(taskType) && !"ALL".equalsIgnoreCase(taskType)) {
            where.append(" and t.task_type = ? ");
            args.add(taskType.trim().toUpperCase());
        }
        return new QueryParts(where, args);
    }

    private QueryParts allParts(String status, String taskType, String keyword) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (!blank(status) && !"ALL".equalsIgnoreCase(status)) {
            where.append(" and t.status = ? ");
            args.add(status.trim().toUpperCase());
        } else {
            where.append(" and t.status = 'OPEN' ");
        }
        if (!blank(taskType) && !"ALL".equalsIgnoreCase(taskType)) {
            where.append(" and t.task_type = ? ");
            args.add(taskType.trim().toUpperCase());
        }
        if (!blank(keyword)) {
            where.append(" and (t.title ilike ? or t.ref_no ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        return new QueryParts(where, args);
    }

    /** 我的任务概况：未完成条数、逾期条数，工作台与菜单角标用 */
    public Map<String, Object> summary(String username) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select count(*) filter (where status = 'OPEN') as "open",
                       count(*) filter (where status = 'OPEN' and due_date < current_date) as "overdue",
                       count(*) filter (where status = 'OPEN' and assignee_username is null) as "unclaimed"
                from biz_task t
                where (assignee_username = ?
                       or (assignee_username is null
                           and assignee_role = (select role from sys_user where username = ?)))
                """, username, username);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("open", number(row.get("open")));
        result.put("overdue", number(row.get("overdue")));
        result.put("unclaimed", number(row.get("unclaimed")));
        return result;
    }

    public Map<String, Object> detail(long id) {
        return jdbcTemplate.queryForMap(baseSelect() + " where t.id = ?", id);
    }

    // ================================================================ 内部

    private String baseSelect() {
        return """
                select t.id,
                       'TK' || lpad(t.id::text, 6, '0') as "taskNo",
                       t.task_type as "taskType",
                       t.title, t.content,
                       t.ref_type as "refType", t.ref_id as "refId", t.ref_no as "refNo",
                       t.assignee_role as "assigneeRole",
                       t.assignee_username as "assigneeUsername",
                       coalesce(t.assignee_name, u.real_name) as "assigneeName",
                       t.priority, t.status,
                       to_char(t.due_date, 'YYYY-MM-DD') as "dueDate",
                       (t.status = 'OPEN' and t.due_date < current_date) as "overdue",
                       to_char(t.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(t.done_at, 'YYYY-MM-DD HH24:MI') as "doneAt",
                       t.done_by as "doneBy", t.done_note as "doneNote"
                from biz_task t
                left join sys_user u on u.username = t.assignee_username
                """;
    }

    private String orderBy() {
        return """
                 order by case t.priority when 'URGENT' then 0 when 'HIGH' then 1
                                          when 'NORMAL' then 2 else 3 end,
                          t.due_date nulls last, t.id desc
                """;
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, status, title, ref_type, ref_no, assignee_username, assignee_role from biz_task where id = ?",
                id);
        if (rows.isEmpty()) {
            throw new BizException(4004, "任务不存在");
        }
        return rows.get(0);
    }

    private Assignee resolve(String username) {
        if (blank(username)) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, username, real_name from sys_user where username = ? and enabled = true",
                username.trim());
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new Assignee(((Number) row.get("id")).longValue(),
                String.valueOf(row.get("username")),
                row.get("real_name") == null ? null : String.valueOf(row.get("real_name")));
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Assignee(long id, String username, String realName) {
    }
}
