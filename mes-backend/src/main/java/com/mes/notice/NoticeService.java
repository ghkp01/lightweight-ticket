package com.mes.notice;

import com.mes.common.PageResult;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.push.api.PushMessage;
import com.mes.push.api.PushPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 站内通知：关键节点给相关人留一条消息。
 * 通知失败不能影响业务事务，所以写入异常一律吞掉（只记日志层面的静默降级）。
 * 落库后再交给推送通道，让手机也能收到同一条消息（没接推送时静默跳过）。
 */
@Service
public class NoticeService implements NoticePort {

    private final JdbcTemplate jdbcTemplate;
    private final PushPort pushPort;

    public NoticeService(JdbcTemplate jdbcTemplate, PushPort pushPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.pushPort = pushPort;
    }

    @Override
    public void push(String receiver, NoticeMessage message) {
        if (receiver == null || receiver.isBlank() || message == null) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    insert into sys_notice (receiver, category, title, content, ref_type, ref_no)
                    values (?, ?, ?, ?, ?, ?)
                    """, receiver, message.category(), message.title(), message.content(),
                    message.refType(), message.refNo());
        } catch (Exception ignored) {
            // 通知是辅助能力，不能拖垮主流程
            return;
        }
        pushPort.dispatch(receiver, new PushMessage(message.category(), message.title(),
                message.content(), message.refType(), message.refNo()));
    }

    @Override
    public void pushToRoles(List<String> roles, NoticeMessage message) {
        if (roles == null || roles.isEmpty()) {
            return;
        }
        List<String> users;
        try {
            String placeholders = String.join(",", roles.stream().map(r -> "?").toList());
            users = jdbcTemplate.queryForList(
                    "select username from sys_user where enabled = true and role in (" + placeholders + ")",
                    String.class, roles.toArray());
        } catch (Exception e) {
            return;
        }
        for (String user : users) {
            push(user, message);
        }
    }

    @Override
    public void pushToOrg(long orgId, NoticeMessage message) {
        List<String> users;
        try {
            users = jdbcTemplate.queryForList("""
                    select u.username from sys_user u join sys_org o on o.id = u.org_id
                    where u.enabled = true and o.path like (select path || '%' from sys_org where id = ?)
                    """, String.class, orgId);
        } catch (Exception e) {
            return;
        }
        for (String user : users) {
            push(user, message);
        }
    }

    public List<Map<String, Object>> mine(String username, Boolean unreadOnly, int limit) {
        return mine(username, null, unreadOnly, limit);
    }

    private static final String SELECT_SQL = """
            select id, category, title, content, ref_type as "refType", ref_no as "refNo",
                   read_at as "readAt", to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
            from sys_notice where receiver = ?
            """;

    /**
     * @param category TASK / ALERT / INFO，为空或 ALL 表示全部
     */
    public List<Map<String, Object>> mine(String username, String category, Boolean unreadOnly, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<Object> args = new ArrayList<>();
        args.add(username);
        String where = where(category, unreadOnly, args);
        args.add(safeLimit);
        return jdbcTemplate.queryForList(SELECT_SQL + where + " order by id desc limit ?", args.toArray());
    }

    /** 消息中心分页：每个人的消息会持续累积，列表页改为后端分页 */
    public PageResult page(String username, String category, Boolean unreadOnly, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(username);
        String countWhere = where(category, unreadOnly, countArgs);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_notice where receiver = ? " + countWhere,
                Long.class, countArgs.toArray());
        List<Object> args = new ArrayList<>();
        args.add(username);
        String pageWhere = where(category, unreadOnly, args);
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                SELECT_SQL + pageWhere + " order by id desc limit ? offset ?", args.toArray());
        return new PageResult(records, total == null ? 0 : total, safePage, safeSize);
    }

    private String where(String category, Boolean unreadOnly, List<Object> args) {
        StringBuilder where = new StringBuilder();
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.trim())) {
            where.append(" and category = ? ");
            args.add(category.trim().toUpperCase(Locale.ROOT));
        }
        if (Boolean.TRUE.equals(unreadOnly)) {
            where.append(" and read_at is null ");
        }
        return where.toString();
    }

    /**
     * 桌面通知轮询：只返回 id 大于 since 的未读消息。
     * 前端把上次见过的最大 id 存本地，只弹新来的，不重复弹历史。
     */
    public List<Map<String, Object>> pending(String username, long since, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        return jdbcTemplate.queryForList("""
                select id, category, title, content, ref_type as "refType", ref_no as "refNo",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from sys_notice
                where receiver = ? and id > ? and read_at is null
                order by id desc
                limit ?
                """, username, Math.max(since, 0), safeLimit);
    }

    public Map<String, Object> summary(String username) {
        Integer unread = jdbcTemplate.queryForObject(
                "select count(*) from sys_notice where receiver = ? and read_at is null",
                Integer.class, username);
        Integer total = jdbcTemplate.queryForObject(
                "select count(*) from sys_notice where receiver = ?", Integer.class, username);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unread", unread == null ? 0 : unread);
        result.put("total", total == null ? 0 : total);
        return result;
    }

    public void markRead(long id, String username) {
        jdbcTemplate.update("""
                update sys_notice set read_at = now()
                where id = ? and receiver = ? and read_at is null
                """, id, username);
    }

    public int markAllRead(String username) {
        return jdbcTemplate.update("""
                update sys_notice set read_at = now()
                where receiver = ? and read_at is null
                """, username);
    }

    // ============ 管理端：全量消息查询、统计、删除与清理 ============

    /** 管理员视角：所有人的消息，可按收件人 / 分类 / 已读状态 / 日期 / 关键字筛 */
    public PageResult adminList(String keyword, String receiver, String category, String readStatus,
                                String from, String to, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = adminWhere(keyword, receiver, category, readStatus, from, to, args);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_notice n left join sys_user u on u.username = n.receiver " + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select n.id, n.receiver, u.real_name as "receiverName",
                       n.category, n.title, n.content,
                       n.ref_type as "refType", n.ref_no as "refNo",
                       n.read_at as "readAt",
                       to_char(n.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from sys_notice n
                left join sys_user u on u.username = n.receiver
                """ + where + " order by n.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> adminSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", count("select count(*) from sys_notice"));
        summary.put("unread", count("select count(*) from sys_notice where read_at is null"));
        summary.put("today", count("select count(*) from sys_notice where created_at::date = current_date"));
        summary.put("task", count("select count(*) from sys_notice where category = 'TASK'"));
        summary.put("alert", count("select count(*) from sys_notice where category = 'ALERT'"));
        return summary;
    }

    @Transactional
    public int delete(long id) {
        return jdbcTemplate.update("delete from sys_notice where id = ?", id);
    }

    @Transactional
    public int deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> safe = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (safe.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(safe.size(), "?"));
        return jdbcTemplate.update("delete from sys_notice where id in (" + placeholders + ")",
                safe.toArray());
    }

    /**
     * 清理已读消息：days 为空或 0 表示清空全部已读，否则只清该天数之前的已读。
     * 未读消息永远不动，避免把没看过的待办清掉。
     */
    @Override
    @Transactional
    public int purgeRead(Integer days) {
        if (days == null || days <= 0) {
            return jdbcTemplate.update("delete from sys_notice where read_at is not null");
        }
        return jdbcTemplate.update("""
                delete from sys_notice
                where read_at is not null and created_at < now() - make_interval(days => ?)
                """, days);
    }

    private String adminWhere(String keyword, String receiver, String category, String readStatus,
                              String from, String to, List<Object> args) {
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (n.title ilike ? or coalesce(n.content, '') ilike ? or coalesce(n.ref_no, '') ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (receiver != null && !receiver.isBlank()) {
            where.append(" and (n.receiver ilike ? or coalesce(u.real_name, '') ilike ?) ");
            String like = "%" + receiver.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.trim())) {
            where.append(" and n.category = ? ");
            args.add(category.trim().toUpperCase(Locale.ROOT));
        }
        if ("UNREAD".equalsIgnoreCase(readStatus)) {
            where.append(" and n.read_at is null ");
        } else if ("READ".equalsIgnoreCase(readStatus)) {
            where.append(" and n.read_at is not null ");
        }
        if (from != null && !from.isBlank()) {
            where.append(" and n.created_at >= ?::date ");
            args.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            where.append(" and n.created_at < (?::date + 1) ");
            args.add(to.trim());
        }
        return where.toString();
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
