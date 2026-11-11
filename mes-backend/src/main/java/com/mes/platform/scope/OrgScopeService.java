package com.mes.platform.scope;

import com.mes.platform.config.ConfigService;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多工厂数据范围。
 *
 * <p>组织树是「工厂 → 车间 → 班组」，单据上记 factory_org_id；
 * 开关打开后，带工厂归属的账号只看本工厂的单据（历史未归属的单据不隐藏，避免上线后老数据消失）。
 * 开关默认关闭，单工厂使用时不产生任何过滤。</p>
 *
 * <p>属于平台能力：13 个业务模块的列表查询都要拼这个条件，跟具体业务无关。
 * 原来放在 {@code system} 里、被各模块用全限定名内联引用，绕过了架构守卫的 import 规则；
 * 现在进 {@code platform.scope} 后，引用关系既合法又可见。</p>
 */
@Service
public class OrgScopeService {

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    public OrgScopeService(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    public boolean isolationEnabled() {
        return configService.booleanValue("org.isolation.enabled", false);
    }

    /** 当前登录人所属工厂；没有归属或开关关闭时返回 null */
    public Long currentFactoryOrgId() {
        if (!isolationEnabled()) {
            return null;
        }
        return factoryOf(CurrentUser.name());
    }

    /** 某个账号所属工厂：沿组织树向上找 type = PLANT 的节点 */
    public Long factoryOf(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        List<Long> orgIds = jdbcTemplate.queryForList(
                "select org_id from sys_user where username = ?", Long.class, username);
        if (orgIds.isEmpty() || orgIds.get(0) == null) {
            return null;
        }
        List<Long> plants = jdbcTemplate.queryForList("""
                with recursive up as (
                    select id, parent_id, type from sys_org where id = ?
                    union all
                    select o.id, o.parent_id, o.type from sys_org o join up on o.id = up.parent_id
                )
                select id from up where type = 'PLANT' limit 1
                """, Long.class, orgIds.get(0));
        return plants.isEmpty() ? null : plants.get(0);
    }

    /** 默认工厂（只有一个基地时用它兜住没归属的数据） */
    public Long defaultFactoryOrgId() {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from sys_org where type = 'PLANT' and enabled order by id limit 1", Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 列表过滤条件：返回 null 表示不过滤。
     * 调用方拿到值后自行把 `and (x.factory_org_id = ? or x.factory_org_id is null)` 拼进 SQL；
     * 也可以直接用 appendScope，省得每处手写。
     */
    public Long listFilter() {
        return currentFactoryOrgId();
    }

    /**
     * 把工厂条件拼到 where 与参数表上：`and (列 = ? or 列 is null)`。
     * 保留 is null 是刻意的——历史单据没有工厂归属，藏起来等于上线后数据消失。
     *
     * @return 是否真的拼了条件（false 表示开关关闭或账号无工厂归属）
     */
    public boolean appendScope(StringBuilder where, List<Object> args, String column) {
        Long factory = listFilter();
        if (factory == null) {
            return false;
        }
        where.append(" and (").append(column).append(" = ? or ").append(column).append(" is null) ");
        args.add(factory);
        return true;
    }

    /**
     * 新建单据时写入的工厂：优先当前账号所属工厂，没有归属就落到默认工厂。
     * 单工厂部署下等价于「都记到那唯一的工厂」，打开隔离后不会出现新旧单据归属不一致。
     */
    public Long writeFactoryOrgId() {
        Long current = currentFactoryOrgId();
        return current != null ? current : defaultFactoryOrgId();
    }

    /** 是否为指定工厂（含无归属），用于按 id 取详情时的越权兜底 */
    public boolean inScope(Long factoryOrgId) {
        Long factory = listFilter();
        return factory == null || factoryOrgId == null || factory.equals(factoryOrgId);
    }
}
