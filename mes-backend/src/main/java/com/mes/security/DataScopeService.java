package com.mes.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据范围判定：ALL 全量、SELF 只看本人、WAREHOUSE 按仓库、ORG 看本组织及下级。
 * 组织范围用 sys_org.path 前缀匹配，避免递归查询。
 */
@Service
public class DataScopeService {

    private final JdbcTemplate jdbcTemplate;

    public DataScopeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String scope() {
        List<String> scopes = jdbcTemplate.queryForList(
                "select data_scope from sys_role where code = ?", String.class, CurrentUser.role());
        return scopes.isEmpty() ? "ALL" : scopes.get(0);
    }

    public boolean selfOnly() {
        return "SELF".equals(scope());
    }

    public boolean warehouseScoped() {
        return "WAREHOUSE".equals(scope());
    }

    /** 本组织及下级：返回当前用户组织路径前缀，例如 /1/2/ */
    public String orgPathPrefix() {
        List<String> paths = jdbcTemplate.queryForList("""
                select o.path from sys_user u join sys_org o on o.id = u.org_id
                where u.username = ?
                """, String.class, CurrentUser.name());
        return paths.isEmpty() ? null : paths.get(0);
    }

    /** 当前用户所属组织，用于默认筛选车间 */
    public Long currentOrgId() {
        List<Long> ids = jdbcTemplate.queryForList(
                "select org_id from sys_user where username = ?", Long.class, CurrentUser.name());
        return ids.isEmpty() ? null : ids.get(0);
    }
}
