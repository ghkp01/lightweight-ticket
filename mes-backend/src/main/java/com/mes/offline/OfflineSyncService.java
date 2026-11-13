package com.mes.offline;

import com.mes.common.PageResult;
import com.mes.offline.api.OfflineSyncPort;
import com.mes.platform.config.ConfigService;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动端离线队列的服务端一半：只做幂等键的存取与清理。
 * 队列本身存在手机本地（uni.storage），服务端不保存「待办」，避免两端状态打架：
 * 手机重传成功就删本地记录，服务端只负责「这个 key 我处理过没有」。
 */
@Service
public class OfflineSyncService implements OfflineSyncPort {

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    public OfflineSyncService(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    public boolean enabled() {
        return configService.booleanValue("offline.sync.enabled", true);
    }

    public int queueLimit() {
        return Math.max(20, configService.intValue("offline.sync.queue.limit", 200));
    }

    public int retentionDays() {
        return Math.max(1, configService.intValue("offline.sync.retention.days", 7));
    }

    /** 命中过就返回上次结果，没命中返回 null */
    public Map<String, Object> cached(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select status_code as "statusCode", response_body as "responseBody",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') as "createdAt"
                from sys_offline_key where idem_key = ?
                """, key.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 记住一次执行结果。同一个 key 并发到达时唯一约束会拒绝后写的那条，
     * 此时忽略即可，不能因为记录幂等键失败而把业务请求判成失败。
     */
    public void remember(String key, String method, String path, int statusCode, String body) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    insert into sys_offline_key (idem_key, user_name, method, path, status_code, response_body)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (idem_key) do nothing
                    """, key.trim(), CurrentUser.name(), method, trim(path, 255), statusCode, body);
        } catch (Exception ignored) {
            // 记录幂等键失败不影响业务：最坏情况是重传时再执行一次，由业务自身的幂等校验兜底
        }
    }

    /** 后台任务：清理超过保留天数的幂等键 */
    @Override
    public int cleanup() {
        return jdbcTemplate.update("""
                delete from sys_offline_key where created_at < now() - (? || ' days')::interval
                """, retentionDays());
    }

    private static final String SELECT_SQL = """
            select id, idem_key as "key", user_name as "userName", method, path,
                   status_code as "statusCode",
                   to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') as "createdAt"
            from sys_offline_key
            """;

    /** 运维排查用：最近的重传记录 */
    public List<Map<String, Object>> recent(int limit) {
        return recent(null, limit);
    }

    public List<Map<String, Object>> recent(String keyword, int limit) {
        int size = limit <= 0 ? 50 : Math.min(limit, 200);
        List<Object> args = new ArrayList<>();
        String where = where(keyword, args);
        args.add(size);
        return jdbcTemplate.queryForList(
                SELECT_SQL + where + " order by id desc limit ?", args.toArray());
    }

    /** 重传记录分页：幂等键按保留天数累积，排查页改为后端分页 */
    public PageResult page(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        List<Object> countArgs = new ArrayList<>();
        String countWhere = where(keyword, countArgs);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_offline_key " + countWhere, Long.class, countArgs.toArray());
        List<Object> args = new ArrayList<>();
        String pageWhere = where(keyword, args);
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                SELECT_SQL + pageWhere + " order by id desc limit ? offset ?", args.toArray());
        return new PageResult(records, total == null ? 0 : total, safePage, safeSize);
    }

    private String where(String keyword, List<Object> args) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String like = "%" + keyword.trim() + "%";
        args.add(like);
        args.add(like);
        args.add(like);
        return " where (idem_key ilike ? or user_name ilike ? or path ilike ?) ";
    }

    /** 移动端「离线待同步」页头用：开关、上限、保留天数、服务端时间 */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled());
        result.put("queueLimit", queueLimit());
        result.put("retentionDays", retentionDays());
        result.put("serverTime", jdbcTemplate.queryForObject(
                "select to_char(now(), 'YYYY-MM-DD HH24:MI:SS')", String.class));
        result.put("receivedKeys", jdbcTemplate.queryForObject(
                "select count(*) from sys_offline_key", Integer.class));
        return result;
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
