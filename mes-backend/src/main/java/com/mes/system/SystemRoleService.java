package com.mes.system;

import com.mes.common.BizException;
import com.mes.common.PageResult;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SystemRoleService {

    private static final Set<String> DATA_SCOPES = Set.of("ALL", "SELF", "WAREHOUSE");

    private final JdbcTemplate jdbcTemplate;

    public SystemRoleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record RoleRequest(@NotBlank String code,
                              @NotBlank String name,
                              String description,
                              String dataScope,
                              List<String> permissions) {
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> roles = jdbcTemplate.queryForList("""
                select r.id, r.code, r.name, r.description, r.data_scope as "dataScope", r.builtin,
                       (select count(*) from sys_user u where u.role = r.code) as "userCount"
                from sys_role r order by r.id
                """);
        Map<Long, List<String>> permissions = new LinkedHashMap<>();
        jdbcTemplate.query("select role_id, permission from sys_role_permission order by permission", rs -> {
            long roleId = rs.getLong("role_id");
            permissions.computeIfAbsent(roleId, key -> new ArrayList<>()).add(rs.getString("permission"));
        });
        for (Map<String, Object> role : roles) {
            long id = ((Number) role.get("id")).longValue();
            role.put("permissions", permissions.getOrDefault(id, List.of()));
        }
        return roles;
    }

    /** 角色列表分页；不传 page 时仍走 list() 返回全量 */
    public PageResult page(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Long total = jdbcTemplate.queryForObject("select count(*) from sys_role", Long.class);
        List<Map<String, Object>> roles = jdbcTemplate.queryForList("""
                select r.id, r.code, r.name, r.description, r.data_scope as "dataScope", r.builtin,
                       (select count(*) from sys_user u where u.role = r.code) as "userCount"
                from sys_role r order by r.id limit ? offset ?
                """, safeSize, (long) (safePage - 1) * safeSize);
        if (!roles.isEmpty()) {
            List<Long> ids = roles.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
            String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
            Map<Long, List<String>> permissions = new LinkedHashMap<>();
            jdbcTemplate.query("select role_id, permission from sys_role_permission where role_id in ("
                    + marks + ") order by permission", rs -> {
                long roleId = rs.getLong("role_id");
                permissions.computeIfAbsent(roleId, key -> new ArrayList<>())
                        .add(rs.getString("permission"));
            }, ids.toArray());
            for (Map<String, Object> role : roles) {
                role.put("permissions", permissions.getOrDefault(
                        ((Number) role.get("id")).longValue(), List.of()));
            }
        }
        return new PageResult(roles, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional
    public long create(RoleRequest request) {
        String code = normalizeCode(request.code());
        String dataScope = normalizeScope(request.dataScope());
        checkCode(code, null);
        Long id = jdbcTemplate.queryForObject("""
                insert into sys_role (code, name, description, data_scope, builtin)
                values (?, ?, ?, ?, false)
                returning id
                """, Long.class, code, request.name().trim(), blankToNull(request.description()), dataScope);
        if (id == null) {
            throw new BizException(4005, "角色创建失败");
        }
        savePermissions(id, request.permissions());
        return id;
    }

    @Transactional
    public void update(long id, RoleRequest request) {
        Map<String, Object> role = load(id);
        String oldCode = String.valueOf(role.get("code"));
        String code = normalizeCode(request.code());
        String dataScope = normalizeScope(request.dataScope());
        if (!oldCode.equals(code)) {
            if ("ADMIN".equals(oldCode)) {
                throw new BizException(4006, "ADMIN 角色不允许修改角色编码");
            }
            Long used = jdbcTemplate.queryForObject(
                    "select count(*) from sys_user where role = ?", Long.class, oldCode);
            if (used != null && used > 0) {
                throw new BizException(4006, "该角色已有用户引用，请先调整用户角色再修改编码");
            }
            checkCode(code, id);
        }
        jdbcTemplate.update("""
                update sys_role set code = ?, name = ?, description = ?, data_scope = ? where id = ?
                """, code, request.name().trim(), blankToNull(request.description()), dataScope, id);
        jdbcTemplate.update("delete from sys_role_permission where role_id = ?", id);
        savePermissions(id, request.permissions());
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> role = load(id);
        if (Boolean.TRUE.equals(role.get("builtin"))) {
            throw new BizException(4007, "内置角色不允许删除");
        }
        Long used = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where role = ?", Long.class, String.valueOf(role.get("code")));
        if (used != null && used > 0) {
            throw new BizException(4007, "该角色下仍有用户，不能删除");
        }
        jdbcTemplate.update("delete from sys_role_permission where role_id = ?", id);
        jdbcTemplate.update("delete from sys_role where id = ?", id);
    }

    public List<Map<String, Object>> permissionCatalog() {
        return List.of(
                group("概览", item("dashboard", "工作台")),
                group("销售与履约",
                        item("sales", "销售订单"),
                        item("shipments", "发货管理"),
                        item("returns", "退货管理")),
                group("采购管理",
                        item("requisition", "采购申请"),
                        item("rfq", "询价比价"),
                        item("supplier", "供应商档案"),
                        item("purchase", "采购订单"),
                        item("ap", "发票与应付")),
                group("生产制造",
                        item("production", "生产工单"),
                        item("cost", "成本核算"),
                        item("perf", "绩效核算"),
                        item("perf:approve", "绩效审核")),
                group("仓储管理",
                        item("inventory", "库存管理"),
                        item("inbound", "入库管理"),
                        item("outbound", "出库管理"),
                        item("transfer", "调拨管理"),
                        item("stocktake", "库存盘点"),
                        item("trace", "批次追溯")),
                group("质量管理", item("quality", "质量检验")),
                group("设备管理", item("device", "设备点检保养")),
                group("基础数据",
                        item("customers", "客户管理"),
                        item("products", "产品与 BOM"),
                        item("warehouses", "仓库管理")),
                group("系统管理",
                        item("system_org", "组织架构"),
                        item("system_user", "用户管理"),
                        item("system_role", "角色权限"),
                        item("system_code", "编码规则"),
                        item("system_config", "系统配置"),
                        item("system_notice", "通知管理"),
                        item("system_data", "基础数据导入"),
                        item("system_job", "定时任务"),
                        item("system_api", "对外接口令牌"),
                        item("system_offline", "离线重传记录"),
                        item("system_approval", "审批流配置"),
                        item("system_file", "文件台账"),
                        item("system_log", "操作日志")),
                group("销售操作",
                        item("sales:create", "新建订单"),
                        item("sales:update", "编辑订单"),
                        item("sales:confirm", "审核订单"),
                        item("sales:cancel", "取消订单"),
                        item("sales:delete", "删除订单"),
                        item("sales:to_production", "转生产")),
                group("报价与 BOM 任务",
                        item("quotation", "报价管理"),
                        item("quotation:create", "新建报价"),
                        item("quotation:update", "编辑报价"),
                        item("quotation:submit", "提交评审"),
                        item("quotation:review", "技术评审"),
                        item("quotation:convert", "报价转订单"),
                        item("quotation:cancel", "取消报价"),
                        item("quotation:delete", "删除报价"),
                        item("bom_task", "BOM 任务"),
                        item("bom_task:assign", "派 BOM 任务"),
                        item("bom_task:complete", "完成 BOM 任务"),
                        item("bom_task:cancel", "取消 BOM 任务")),
                group("采购操作",
                        item("purchase:request", "采购申请提报"),
                        item("purchase:approve", "采购申请审批"),
                        item("purchase:rfq", "询价与报价"),
                        item("purchase:select_quote", "选定报价"),
                        item("supplier:save", "供应商维护"),
                        item("purchase:create", "新建采购单"),
                        item("purchase:edit", "编辑采购单"),
                        item("purchase:confirm", "确认采购单"),
                        item("purchase:cancel", "取消采购单"),
                        item("purchase:delete", "删除采购单"),
                        item("purchase:receive", "采购收货"),
                        item("ap:invoice", "发票登记"),
                        item("ap:match", "三单匹配"),
                        item("ap:payment", "付款登记")),
                group("生产操作",
                        item("production:create", "新建工单"),
                        item("production:schedule", "排产计划"),
                        item("production:release", "下达工单"),
                        item("production:issue", "生产领料"),
                        item("production:report", "工序报工"),
                        item("production:assign", "工序派工"),
                        item("production:return", "生产退料"),
                        item("production:outsource", "外协发出"),
                        item("production:outsource-receive", "外协收回"),
                        item("production:bom", "工单 BOM 调整"),
                        item("production:close", "关闭工单"),
                        item("production:cancel", "作废工单")),
                group("工艺操作",
                        item("process:save", "工序主数据维护"),
                        item("routing:save", "工艺路线维护"),
                        item("bom:save", "产品 BOM 维护")),
                group("发货操作",
                        item("shipping:create", "新建发货单"),
                        item("shipping:ship", "发货出库"),
                        item("shipping:sign", "签收"),
                        item("shipping:delete", "删除发货单")),
                group("质量与退货操作",
                        item("quality:create", "新建检验单"),
                        item("quality:item", "检验项维护"),
                        item("document:save", "SOP / SIP 维护"),
                        item("defect:handle", "不良处置"),
                        item("returns:create", "新建退货单"),
                        item("returns:process", "处理退货"),
                        item("returns:close", "关闭退货单")),
                group("附件与审计操作",
                        item("file:upload", "附件上传"),
                        item("file:delete", "附件删除")),
                group("仓储操作",
                        item("warehouse:inbound", "入库单操作"),
                        item("warehouse:outbound", "出库单操作"),
                        item("warehouse:transfer", "调拨操作"),
                        item("warehouse:stocktake", "盘点操作")),
                group("系统操作",
                        item("org:save", "组织架构维护"),
                        item("workcenter:save", "工作中心维护"),
                        item("device:save", "设备台账维护"),
                        item("system:user", "用户管理操作"),
                        item("system:role", "角色权限操作"),
                        item("system:code", "编码规则操作"),
                        item("config:save", "系统配置修改"))
        );
    }

    @SafeVarargs
    private Map<String, Object> group(String group, Map<String, Object>... items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", group);
        result.put("items", List.of(items));
        return result;
    }

    private Map<String, Object> item(String code, String label) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("label", label);
        return result;
    }

    private void savePermissions(long roleId, List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank()) {
                distinct.add(permission.trim());
            }
        }
        for (String permission : distinct) {
            jdbcTemplate.update(
                    "insert into sys_role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, builtin from sys_role where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(4005, "角色不存在");
        }
        return rows.get(0);
    }

    private void checkCode(String code, Long selfId) {
        Long count = selfId == null
                ? jdbcTemplate.queryForObject("select count(*) from sys_role where code = ?", Long.class, code)
                : jdbcTemplate.queryForObject(
                        "select count(*) from sys_role where code = ? and id <> ?", Long.class, code, selfId);
        if (count != null && count > 0) {
            throw new BizException(4005, "角色编码已存在");
        }
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(1002, "角色编码不能为空");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeScope(String dataScope) {
        String scope = dataScope == null || dataScope.isBlank()
                ? "ALL" : dataScope.trim().toUpperCase(Locale.ROOT);
        if (!DATA_SCOPES.contains(scope)) {
            throw new BizException(1002, "数据范围只能是 ALL、SELF 或 WAREHOUSE");
        }
        return scope;
    }

    private String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
