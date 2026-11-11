package com.mes.system;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mes.common.BizException;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用户表格视图：列显示、冻结、列宽、默认查询条件按「用户 + 页面」保存。
 * 数据存 sys_user_view，view_json 用 jsonb，查看与修改都只允许本人。
 */
@Service
public class UserViewService {

    private static final int MAX_VIEWS_PER_PAGE = 20;
    private static final Pattern PAGE_KEY = Pattern.compile("^[a-z0-9][a-z0-9.\\-]{2,63}$");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public UserViewService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record ViewRequest(String pageKey, String name, Map<String, Object> view, Boolean isDefault) {
    }

    public List<Map<String, Object>> list(String pageKey) {
        String key = requirePageKey(pageKey);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, page_key as "pageKey", name, view_json::text as "viewJson",
                       is_default as "isDefault",
                       to_char(updated_at, 'YYYY-MM-DD HH24:MI') as "updatedAt"
                from sys_user_view
                where user_id = ? and page_key = ?
                order by is_default desc, id asc
                """, userId(), key);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("view", parseView(String.valueOf(row.get("viewJson"))));
            item.remove("viewJson");
            result.add(item);
        }
        return result;
    }

    @Transactional
    public long create(ViewRequest request) {
        long userId = userId();
        String pageKey = requirePageKey(request == null ? null : request.pageKey());
        String name = requireName(request.name());
        String json = writeView(request.view());
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from sys_user_view where user_id = ? and page_key = ?
                """, Integer.class, userId, pageKey);
        if (count != null && count >= MAX_VIEWS_PER_PAGE) {
            throw new BizException(1002, "每个页面最多保存 " + MAX_VIEWS_PER_PAGE + " 套视图，请先删除不用的");
        }
        Integer duplicate = jdbcTemplate.queryForObject("""
                select count(*) from sys_user_view where user_id = ? and page_key = ? and name = ?
                """, Integer.class, userId, pageKey, name);
        if (duplicate != null && duplicate > 0) {
            throw new BizException(2015, "视图名称已存在，请换一个名字");
        }
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());
        if (isDefault) {
            clearDefault(userId, pageKey, null);
        }
        Long id = jdbcTemplate.queryForObject("""
                insert into sys_user_view (user_id, page_key, name, view_json, is_default)
                values (?, ?, ?, ?::jsonb, ?)
                returning id
                """, Long.class, userId, pageKey, name, json, isDefault);
        return id == null ? 0 : id;
    }

    @Transactional
    public void update(long id, ViewRequest request) {
        long userId = userId();
        Map<String, Object> row = loadOwned(id, userId);
        String name = requireName(request == null ? null : request.name());
        String json = writeView(request.view());
        String pageKey = String.valueOf(row.get("page_key"));
        Integer duplicate = jdbcTemplate.queryForObject("""
                select count(*) from sys_user_view
                where user_id = ? and page_key = ? and name = ? and id <> ?
                """, Integer.class, userId, pageKey, name, id);
        if (duplicate != null && duplicate > 0) {
            throw new BizException(2015, "视图名称已存在，请换一个名字");
        }
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());
        if (isDefault) {
            clearDefault(userId, pageKey, id);
        }
        jdbcTemplate.update("""
                update sys_user_view
                set name = ?, view_json = ?::jsonb, is_default = ?, updated_at = now()
                where id = ? and user_id = ?
                """, name, json, isDefault, id, userId);
    }

    @Transactional
    public void setDefault(long id) {
        long userId = userId();
        Map<String, Object> row = loadOwned(id, userId);
        String pageKey = String.valueOf(row.get("page_key"));
        clearDefault(userId, pageKey, id);
        jdbcTemplate.update("""
                update sys_user_view set is_default = true, updated_at = now()
                where id = ? and user_id = ?
                """, id, userId);
    }

    @Transactional
    public void delete(long id) {
        long userId = userId();
        loadOwned(id, userId);
        jdbcTemplate.update("delete from sys_user_view where id = ? and user_id = ?", id, userId);
    }

    private void clearDefault(long userId, String pageKey, Long excludeId) {
        if (excludeId == null) {
            jdbcTemplate.update("""
                    update sys_user_view set is_default = false, updated_at = now()
                    where user_id = ? and page_key = ? and is_default = true
                    """, userId, pageKey);
        } else {
            jdbcTemplate.update("""
                    update sys_user_view set is_default = false, updated_at = now()
                    where user_id = ? and page_key = ? and is_default = true and id <> ?
                    """, userId, pageKey, excludeId);
        }
    }

    private Map<String, Object> loadOwned(long id, long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, page_key, name from sys_user_view where id = ? and user_id = ?
                """, id, userId);
        if (rows.isEmpty()) {
            throw new BizException(2012, "视图不存在或不属于当前用户");
        }
        return rows.get(0);
    }

    private long userId() {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from sys_user where username = ?", Long.class, CurrentUser.name());
        if (ids.isEmpty()) {
            throw new BizException(2012, "当前用户不存在");
        }
        return ids.get(0);
    }

    private String requirePageKey(String pageKey) {
        String key = pageKey == null ? "" : pageKey.trim().toLowerCase();
        if (!PAGE_KEY.matcher(key).matches()) {
            throw new BizException(1002, "页面标识不合法");
        }
        return key;
    }

    private String requireName(String name) {
        String text = name == null ? "" : name.trim();
        if (text.length() < 2 || text.length() > 40) {
            throw new BizException(1002, "视图名称需要 2-40 个字");
        }
        return text;
    }

    private String writeView(Map<String, Object> view) {
        if (view == null || view.isEmpty()) {
            throw new BizException(1002, "视图内容不能为空");
        }
        try {
            return objectMapper.writeValueAsString(view);
        } catch (Exception e) {
            throw new BizException(1002, "视图内容格式不正确");
        }
    }

    private Map<String, Object> parseView(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
