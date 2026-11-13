package com.mes.org;

import com.mes.common.BizException;
import com.mes.common.PageResult;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 组织架构：工厂 -> 车间 -> 班组，职能部门挂在工厂或车间下。
 * 路径用物化路径 /1/2/4/ 表示，数据范围与「含下级」查询都靠它。
 */
@Service
public class OrgService {

    private static final Set<String> TYPES = Set.of("PLANT", "WORKSHOP", "TEAM", "DEPT");

    private final JdbcTemplate jdbcTemplate;

    public OrgService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record OrgRequest(@NotBlank String code,
                             @NotBlank String name,
                             @NotBlank String type,
                             Long parentId,
                             Integer sort,
                             String leader,
                             String phone,
                             Boolean enabled,
                             String remark) {
    }

    public List<Map<String, Object>> list(String keyword, String type, Boolean enabled) {
        StringBuilder sql = new StringBuilder("""
                select o.id, o.parent_id as "parentId", o.code, o.name, o.type, o.path, o.level, o.sort,
                       o.leader, o.phone, o.enabled, o.remark,
                       coalesce(p.name, '-') as "parentName",
                       (select count(*) from sys_org c where c.parent_id = o.id) as "childCount",
                       (select count(*) from sys_user u where u.org_id = o.id) as "memberCount",
                       (select count(*) from work_center w where w.org_id = o.id) as "workCenterCount"
                from sys_org o
                left join sys_org p on p.id = o.parent_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (o.code ilike ? or o.name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" and o.type = ? ");
            args.add(type.trim().toUpperCase(Locale.ROOT));
        }
        if (enabled != null) {
            sql.append(" and o.enabled = ? ");
            args.add(enabled);
        }
        // 同一层级按 sort、id 排序；用 path 排序会让 /1/10/ 排在 /1/2/ 前面
        sql.append(" order by o.level, o.sort, o.id");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 组装成树，前端直接交给 el-tree 使用 */
    public List<Map<String, Object>> tree() {
        List<Map<String, Object>> flat = list(null, null, null);
        Map<Long, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> row : flat) {
            row.put("children", new ArrayList<Map<String, Object>>());
            index.put(((Number) row.get("id")).longValue(), row);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : flat) {
            Object parentId = row.get("parentId");
            if (parentId == null) {
                roots.add(row);
                continue;
            }
            Map<String, Object> parent = index.get(((Number) parentId).longValue());
            if (parent == null) {
                roots.add(row);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(row);
            }
        }
        return roots;
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> org = load(id);
        List<Map<String, Object>> members = members(id, false);
        Map<String, Object> result = new LinkedHashMap<>(org);
        result.put("memberCount", members.size());
        result.put("members", members);
        return result;
    }

    @Transactional
    public long create(OrgRequest request) {
        String code = normalizeCode(request.code());
        String type = normalizeType(request.type());
        String name = requireName(request.name());
        Map<String, Object> parent = resolveParent(type, request.parentId());
        assertCodeUnique(code, null);
        String path = (parent == null ? "/" : String.valueOf(parent.get("path"))) + "PLACEHOLDER/";
        Long id = jdbcTemplate.queryForObject("""
                insert into sys_org (parent_id, code, name, type, path, level, sort, leader, phone, enabled, remark)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, request.parentId(), code, name, type, path,
                parent == null ? 1 : ((Number) parent.get("level")).intValue() + 1,
                request.sort() == null ? 0 : request.sort(),
                trim(request.leader()), trim(request.phone()),
                request.enabled() == null || request.enabled(), trim(request.remark()));
        if (id == null) {
            throw new BizException(2001, "组织创建失败");
        }
        String finalPath = (parent == null ? "/" : String.valueOf(parent.get("path"))) + id + "/";
        jdbcTemplate.update("update sys_org set path = ? where id = ?", finalPath, id);
        return id;
    }

    @Transactional
    public void update(long id, OrgRequest request) {
        Map<String, Object> exists = load(id);
        String code = normalizeCode(request.code());
        String type = normalizeType(request.type());
        String name = requireName(request.name());
        assertCodeUnique(code, id);

        long oldParentId = exists.get("parentId") == null ? 0L : ((Number) exists.get("parentId")).longValue();
        long newParentId = request.parentId() == null ? 0L : request.parentId();
        boolean parentChanged = oldParentId != newParentId;
        String oldPath = String.valueOf(exists.get("path"));
        int oldLevel = ((Number) exists.get("level")).intValue();

        String newPath = oldPath;
        int newLevel = oldLevel;
        if (parentChanged) {
            Map<String, Object> parent = resolveParent(type, request.parentId());
            if (parent != null) {
                String parentPath = String.valueOf(parent.get("path"));
                if (parentPath.startsWith(oldPath)) {
                    throw new BizException(2001, "不能把组织移动到自己的下级");
                }
                newPath = parentPath + id + "/";
                newLevel = ((Number) parent.get("level")).intValue() + 1;
            } else {
                newPath = "/" + id + "/";
                newLevel = 1;
            }
        }

        jdbcTemplate.update("""
                update sys_org set parent_id = ?, code = ?, name = ?, type = ?, path = ?, level = ?,
                       sort = ?, leader = ?, phone = ?, enabled = ?, remark = ?
                where id = ?
                """, request.parentId(), code, name, type, newPath, newLevel,
                request.sort() == null ? ((Number) exists.get("sort")).intValue() : request.sort(),
                trim(request.leader()), trim(request.phone()),
                request.enabled() == null ? Boolean.TRUE.equals(exists.get("enabled")) : request.enabled(),
                trim(request.remark()), id);

        if (!newPath.equals(oldPath)) {
            // 子树的路径前缀与层级整体平移
            jdbcTemplate.update("""
                    update sys_org
                    set path = ? || substring(path from ?), level = level + ?
                    where path like ? and id <> ?
                    """, newPath, oldPath.length() + 1, newLevel - oldLevel, oldPath + "%", id);
        }
    }

    @Transactional
    public void delete(long id) {
        load(id);
        Integer children = jdbcTemplate.queryForObject(
                "select count(*) from sys_org where parent_id = ?", Integer.class, id);
        if (children != null && children > 0) {
            throw new BizException(2002, "该组织下还有 " + children + " 个下级，请先移走下级组织");
        }
        Integer members = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where org_id = ?", Integer.class, id);
        if (members != null && members > 0) {
            throw new BizException(2002, "该组织下还有 " + members + " 名成员，不能删除，请改为停用");
        }
        Integer centers = jdbcTemplate.queryForObject(
                "select count(*) from work_center where org_id = ?", Integer.class, id);
        if (centers != null && centers > 0) {
            throw new BizException(2002, "该组织下还有 " + centers + " 个工作中心，不能删除");
        }
        jdbcTemplate.update("delete from sys_org where id = ?", id);
    }

    public List<Map<String, Object>> members(long orgId, boolean includeChildren) {
        Map<String, Object> org = load(orgId);
        String path = String.valueOf(org.get("path"));
        String sql = """
                select u.id, u.username, u.real_name as "realName", u.phone, u.employee_no as "employeeNo",
                       u.position, u.role, coalesce(r.name, u.role) as "roleName", u.enabled,
                       o.name as "orgName", o.path as "orgPath"
                from sys_user u
                left join sys_role r on r.code = u.role
                left join sys_org o on o.id = u.org_id
                where u.org_id in (select id from sys_org where %s)
                order by u.org_id, u.id
                """.formatted(includeChildren ? "path like ?" : "id = ?");
        Object arg = includeChildren ? path + "%" : orgId;
        return jdbcTemplate.queryForList(sql, arg);
    }

    /** 组织成员分页；不传 page 时仍走 members(orgId, includeChildren) 返回全量 */
    public PageResult membersPage(long orgId, boolean includeChildren, int page, int size) {
        Map<String, Object> org = load(orgId);
        String path = String.valueOf(org.get("path"));
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String where = includeChildren
                ? " where u.org_id in (select id from sys_org where path like ?) "
                : " where u.org_id = ? ";
        Object arg = includeChildren ? path + "%" : orgId;
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_user u" + where, Long.class, arg);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select u.id, u.username, u.real_name as "realName", u.phone, u.employee_no as "employeeNo",
                       u.position, u.role, coalesce(r.name, u.role) as "roleName", u.enabled,
                       o.name as "orgName", o.path as "orgPath"
                from sys_user u
                left join sys_role r on r.code = u.role
                left join sys_org o on o.id = u.org_id
                """ + where + " order by u.org_id, u.id limit ? offset ?",
                arg, safeSize, (long) (safePage - 1) * safeSize);
        return new PageResult(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** 下拉选项：扁平列表，带层级缩进用的 level 与 path */
    public List<Map<String, Object>> options() {
        return options(null, null, null);
    }

    public List<Map<String, Object>> options(String keyword, Integer limit) {
        return options(keyword, null, limit);
    }

    /** 引用选择专用：关键字 + 组织类型过滤 + 条数上限；limit 为空时保持原行为（返回全部启用组织） */
    public List<Map<String, Object>> options(String keyword, String type, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                select id, parent_id as "parentId", code, name, type, path, level, enabled
                from sys_org where enabled = true
                """);
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (code ilike ? or name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" and type = ? ");
            args.add(type.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" order by level, sort, id ");
        if (limit != null && limit > 0) {
            sql.append(" limit ? ");
            args.add(Math.min(limit, 50));
        }
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, parent_id as "parentId", code, name, type, path, level, sort, leader, phone,
                       enabled, remark
                from sys_org where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2003, "组织不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> resolveParent(String type, Long parentId) {
        if (parentId == null) {
            if (!"PLANT".equals(type)) {
                throw new BizException(2001, "只有工厂可以作为顶级组织");
            }
            return null;
        }
        Map<String, Object> parent = load(parentId);
        String parentType = String.valueOf(parent.get("type"));
        boolean ok = switch (type) {
            case "PLANT" -> false;
            case "WORKSHOP" -> "PLANT".equals(parentType);
            case "TEAM" -> "WORKSHOP".equals(parentType);
            case "DEPT" -> "PLANT".equals(parentType) || "WORKSHOP".equals(parentType);
            default -> false;
        };
        if (!ok) {
            throw new BizException(2001, switch (type) {
                case "PLANT" -> "工厂只能是顶级组织，不能再挂上级";
                case "WORKSHOP" -> "车间只能挂在工厂下";
                case "TEAM" -> "班组只能挂在车间下";
                default -> "职能部门只能挂在工厂或车间下";
            });
        }
        if (!Boolean.TRUE.equals(parent.get("enabled"))) {
            throw new BizException(2001, "上级组织已停用，不能新增下级");
        }
        return parent;
    }

    private void assertCodeUnique(String code, Long excludeId) {
        Long count = excludeId == null
                ? jdbcTemplate.queryForObject("select count(*) from sys_org where code = ?", Long.class, code)
                : jdbcTemplate.queryForObject(
                        "select count(*) from sys_org where code = ? and id <> ?", Long.class, code, excludeId);
        if (count != null && count > 0) {
            throw new BizException(2001, "组织编码 " + code + " 已存在");
        }
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "请填写组织编码");
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (code.length() > 32) {
            throw new BizException(1002, "组织编码不能超过 32 个字符");
        }
        return code;
    }

    private String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "请填写组织名称");
        }
        String name = value.trim();
        if (name.length() > 64) {
            throw new BizException(1002, "组织名称不能超过 64 个字符");
        }
        return name;
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new BizException(1002, "组织类型只能是工厂、车间、班组或职能部门");
        }
        return type;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
