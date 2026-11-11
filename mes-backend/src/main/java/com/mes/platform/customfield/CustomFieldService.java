package com.mes.platform.customfield;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mes.common.BizException;
import com.mes.platform.scope.OrgScopeService;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 低代码自定义字段。
 *
 * <p>字段定义只描述「叫什么、什么类型、是否必填、有哪些选项」，业务表的扩展值统一存到 JSONB 列。
 * 加字段不需要改表结构，也不会让核心业务字段变成用户可随意改的脏数据。</p>
 *
 * <p><b>作用域</b>：字段可以是全局的，也可以归属某个工厂；表单只取「全局 + 当前工厂」的字段。
 * <b>权限</b>：可见角色 / 可编辑角色为空表示不限制；ADMIN 始终可见可编辑。
 * 不可编辑字段即使前端传了值，服务端也会忽略，避免绕过界面改值。</p>
 *
 * <p>实体类型走白名单，表名和扩展列由业务模块自己映射；本服务只负责定义、校验与 JSON 合并，
 * 不直接读写业务表，避免平台层越界访问业务模块的表。</p>
 */
@Service
public class CustomFieldService {

    private static final Map<String, String> ENTITY_LABELS = new LinkedHashMap<>();
    private static final Set<String> FIELD_TYPES = Set.of(
            "TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "SWITCH");

    static {
        ENTITY_LABELS.put("sales_order", "销售订单");
        ENTITY_LABELS.put("sales_order_item", "销售订单明细");
        ENTITY_LABELS.put("work_order", "生产工单");
        ENTITY_LABELS.put("product", "物料档案");
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OrgScopeService orgScopeService;

    public CustomFieldService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              OrgScopeService orgScopeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.orgScopeService = orgScopeService;
    }

    public record FieldDefinition(long id, String entityType, String fieldKey, String label,
                                  String fieldType, boolean required, List<String> options,
                                  Object defaultValue, int sortNo, boolean enabled,
                                  Long factoryOrgId, List<String> visibleRoles,
                                  List<String> editableRoles) {
    }

    /** 表单拿到的字段：只包含当前用户可见的，editable 决定控件是否只读 */
    public record FormField(String fieldKey, String label, String fieldType, boolean required,
                            List<String> options, Object defaultValue, int sortNo, boolean editable) {
    }

    public record FieldRequest(String entityType, String fieldKey, String label, String fieldType,
                               Boolean required, List<String> options, Object defaultValue,
                               Integer sortNo, Boolean enabled, Long factoryOrgId,
                               List<String> visibleRoles, List<String> editableRoles) {

        /** 兼容老调用：不带工厂与角色范围 */
        public FieldRequest(String entityType, String fieldKey, String label, String fieldType,
                            Boolean required, List<String> options, Object defaultValue,
                            Integer sortNo, Boolean enabled) {
            this(entityType, fieldKey, label, fieldType, required, options, defaultValue,
                    sortNo, enabled, null, null, null);
        }
    }

    public List<Map<String, Object>> entities() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : ENTITY_LABELS.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityType", entry.getKey());
            row.put("label", entry.getValue());
            rows.add(row);
        }
        return rows;
    }

    /** 设计器用：返回全部字段（含停用、所有工厂），不做角色过滤 */
    public List<FieldDefinition> definitions(String entityType, boolean includeDisabled) {
        String entity = requireEntity(entityType);
        String sql = """
                select id, entity_type, field_key, label, field_type, required,
                       options::text as options, default_value::text as "defaultValue",
                       sort_no, enabled, factory_org_id as "factoryOrgId",
                       visible_roles::text as "visibleRoles",
                       editable_roles::text as "editableRoles"
                from sys_custom_field
                where entity_type = ?
                """ + (includeDisabled ? "" : " and enabled = true ") + """
                order by factory_org_id nulls first, sort_no, id
                """;
        List<FieldDefinition> definitions = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, entity)) {
            definitions.add(definitionOf(row));
        }
        return definitions;
    }

    public FieldDefinition definition(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, entity_type, field_key, label, field_type, required,
                       options::text as options, default_value::text as "defaultValue",
                       sort_no, enabled, factory_org_id as "factoryOrgId",
                       visible_roles::text as "visibleRoles",
                       editable_roles::text as "editableRoles"
                from sys_custom_field where id = ?
                """, id);
        return rows.isEmpty() ? null : definitionOf(rows.get(0));
    }

    /** 按实体 + 字段键取定义（设计器/内部使用，不做角色过滤） */
    public FieldDefinition definition(String entityType, String fieldKey) {
        String entity = requireEntity(entityType);
        if (fieldKey == null || fieldKey.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, entity_type, field_key, label, field_type, required,
                       options::text as options, default_value::text as "defaultValue",
                       sort_no, enabled, factory_org_id as "factoryOrgId",
                       visible_roles::text as "visibleRoles",
                       editable_roles::text as "editableRoles"
                from sys_custom_field where entity_type = ? and field_key = ?
                order by factory_org_id nulls first limit 1
                """, entity, fieldKey.trim().toLowerCase());
        return rows.isEmpty() ? null : definitionOf(rows.get(0));
    }

    /**
     * 表单用：只返回当前工厂、当前角色可见的启用字段，并标出是否可编辑。
     * 当前工厂取账号所属工厂；隔离关闭或账号没有归属时退回默认工厂，
     * 否则单工厂部署下配置了工厂的字段会谁都看不到。
     */
    public List<FormField> formDefinitions(String entityType) {
        String entity = requireEntity(entityType);
        Long factoryId = orgScopeService.currentFactoryOrgId();
        if (factoryId == null) {
            factoryId = orgScopeService.defaultFactoryOrgId();
        }
        String sql = """
                select id, entity_type, field_key, label, field_type, required,
                       options::text as options, default_value::text as "defaultValue",
                       sort_no, enabled, factory_org_id as "factoryOrgId",
                       visible_roles::text as "visibleRoles",
                       editable_roles::text as "editableRoles"
                from sys_custom_field
                where entity_type = ? and enabled = true
                """ + (factoryId == null
                ? " and factory_org_id is null "
                : " and (factory_org_id is null or factory_org_id = " + factoryId + ") ")
                + " order by factory_org_id nulls first, sort_no, id";
        String role = CurrentUser.role();
        boolean admin = "ADMIN".equals(role);
        List<FormField> fields = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, entity)) {
            FieldDefinition definition = definitionOf(row);
            if (!admin && !matchesRole(definition.visibleRoles(), role)) {
                continue;
            }
            boolean editable = admin || matchesRole(definition.editableRoles(), role);
            fields.add(new FormField(definition.fieldKey(), definition.label(), definition.fieldType(),
                    definition.required(), definition.options(), definition.defaultValue(),
                    definition.sortNo(), editable));
        }
        return fields;
    }

    /** 表单可见字段中按 key 取一个，列表筛选也用它，避免筛到用户看不见的字段 */
    public FormField formDefinition(String entityType, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            return null;
        }
        String key = fieldKey.trim().toLowerCase();
        return formDefinitions(entityType).stream()
                .filter(field -> field.fieldKey().equals(key))
                .findFirst().orElse(null);
    }

    public List<Map<String, Object>> factories() {
        return jdbcTemplate.queryForList("""
                select id, code, name from sys_org
                where type = 'PLANT' and enabled order by id
                """);
    }

    @Transactional
    public FieldDefinition create(FieldRequest request) {
        String entityType = requireEntity(request.entityType());
        String fieldKey = fieldKey(request.fieldKey());
        String fieldType = fieldType(request.fieldType());
        String label = label(request.label());
        List<String> options = options(fieldType, request.options());
        Object defaultValue = defaultValue(fieldType, options, request.defaultValue(), label);
        Long factoryOrgId = factory(request.factoryOrgId());
        List<String> visibleRoles = roles(request.visibleRoles());
        List<String> editableRoles = roles(request.editableRoles());
        requireNoKeyCollision(entityType, fieldKey, factoryOrgId, 0L);
        Long id = jdbcTemplate.queryForObject("""
                insert into sys_custom_field
                    (entity_type, field_key, label, field_type, required, options, default_value,
                     sort_no, enabled, factory_org_id, visible_roles, editable_roles)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb)
                returning id
                """, Long.class, entityType, fieldKey, label, fieldType,
                Boolean.TRUE.equals(request.required()), jsonOrNull(options),
                jsonOrNull(defaultValue), request.sortNo() == null ? 0 : request.sortNo(),
                request.enabled() == null || request.enabled(), factoryOrgId,
                jsonOrNull(visibleRoles), jsonOrNull(editableRoles));
        return definition(id);
    }

    /** 字段键、类型、作用工厂一旦保存就不能改，避免历史 JSON 值错位 */
    @Transactional
    public FieldDefinition update(long id, FieldRequest request) {
        FieldDefinition current = definition(id);
        if (current == null) {
            throw new BizException(1002, "自定义字段不存在");
        }
        String label = label(request.label());
        List<String> options = options(current.fieldType(), request.options());
        Object defaultValue = defaultValue(current.fieldType(), options, request.defaultValue(), label);
        List<String> visibleRoles = roles(request.visibleRoles());
        List<String> editableRoles = roles(request.editableRoles());
        jdbcTemplate.update("""
                update sys_custom_field
                   set label = ?, required = ?, options = ?::jsonb, default_value = ?::jsonb,
                       sort_no = ?, enabled = ?, visible_roles = ?::jsonb,
                       editable_roles = ?::jsonb, updated_at = now()
                 where id = ?
                """, label, Boolean.TRUE.equals(request.required()), jsonOrNull(options),
                jsonOrNull(defaultValue), request.sortNo() == null ? current.sortNo() : request.sortNo(),
                request.enabled() == null ? current.enabled() : request.enabled(),
                jsonOrNull(visibleRoles), jsonOrNull(editableRoles), id);
        return definition(id);
    }

    /** 停用而不是物理删除：历史订单里的值还要能读出来 */
    @Transactional
    public void disable(long id) {
        jdbcTemplate.update("""
                update sys_custom_field set enabled = false, updated_at = now() where id = ?
                """, id);
    }

    /**
     * 校验表单值并合并进已有 JSON。
     *
     * @param existingJson 业务记录当前的扩展值 JSON，新建时传 "{}"
     * @param values       前端提交的自定义字段值
     * @param applyDefaults 新建时是否补默认值；修改时 false，避免把用户清空的字段又填回去
     */
    public String mergeJson(String entityType, String existingJson, Map<String, Object> values,
                            boolean applyDefaults) {
        List<FormField> definitions = formDefinitions(entityType);
        Map<String, Object> merged = readJson(existingJson);
        Map<String, Object> input = values == null ? Map.of() : values;
        for (FormField definition : definitions) {
            if (!definition.editable()) {
                // 只读字段：前端传了也忽略，保留原值
                continue;
            }
            boolean provided = input.containsKey(definition.fieldKey());
            if (!provided && !applyDefaults) {
                // 修改时没提交的字段保持原值；停用/不可见字段也不会被覆盖
                continue;
            }
            Object raw = provided ? input.get(definition.fieldKey()) : definition.defaultValue();
            Object normalized = normalizeValue(definition.fieldType(), definition.options(),
                    definition.required(), definition.label(), raw);
            if (normalized == null) {
                merged.remove(definition.fieldKey());
            } else {
                merged.put(definition.fieldKey(), normalized);
            }
        }
        return writeJson(merged);
    }

    public Map<String, Object> readJson(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(text,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (JsonProcessingException ignored) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 读取业务记录的自定义字段值，并剔除当前账号不可见的字段。
     * 列表/详情接口必须走这里——只在前端隐藏控件不算权限控制。
     */
    public Map<String, Object> visibleJson(String entityType, String text) {
        return visibleJson(formDefinitions(entityType), text);
    }

    /** 列表场景先用 formDefinitions 取一次定义，再逐行过滤，避免每行都查一次字段表 */
    public Map<String, Object> visibleJson(List<FormField> definitions, String text) {
        Map<String, Object> values = readJson(text);
        if (values.isEmpty() || definitions.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Set<String> visibleKeys = new LinkedHashSet<>();
        for (FormField field : definitions) {
            visibleKeys.add(field.fieldKey());
        }
        Map<String, Object> visible = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (visibleKeys.contains(entry.getKey())) {
                visible.put(entry.getKey(), entry.getValue());
            }
        }
        return visible;
    }

    // ---------------------------------------------------------------- 校验

    private boolean matchesRole(List<String> roles, String role) {
        return roles == null || roles.isEmpty() || roles.contains(role);
    }

    private Object normalizeValue(String fieldType, List<String> options, boolean required,
                                  String label, Object raw) {
        if (raw == null) {
            if (required) {
                throw new BizException(1002, "请填写自定义字段：" + label);
            }
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            if (required) {
                throw new BizException(1002, "请填写自定义字段：" + label);
            }
            return null;
        }
        switch (fieldType) {
            case "TEXT", "TEXTAREA" -> {
                return text;
            }
            case "NUMBER" -> {
                try {
                    return new BigDecimal(text);
                } catch (NumberFormatException error) {
                    throw new BizException(1002, "自定义字段「" + label + "」必须是数字");
                }
            }
            case "DATE" -> {
                try {
                    return LocalDate.parse(text).toString();
                } catch (DateTimeParseException error) {
                    throw new BizException(1002, "自定义字段「" + label + "」日期格式应为 YYYY-MM-DD");
                }
            }
            case "SELECT" -> {
                if (options == null || !options.contains(text)) {
                    throw new BizException(1002, "自定义字段「" + label + "」的值不在可选项里");
                }
                return text;
            }
            case "SWITCH" -> {
                if (raw instanceof Boolean value) {
                    return value;
                }
                if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
                    return false;
                }
                throw new BizException(1002, "自定义字段「" + label + "」只能是是/否");
            }
            default -> throw new BizException(1002, "不支持的字段类型：" + fieldType);
        }
    }

    private String requireEntity(String entityType) {
        String key = entityType == null ? "" : entityType.trim();
        if (!ENTITY_LABELS.containsKey(key)) {
            throw new BizException(1002, "不支持的自定义字段实体：" + entityType);
        }
        return key;
    }

    private String fieldKey(String value) {
        String key = value == null ? "" : value.trim().toLowerCase();
        if (!key.matches("^[a-z][a-z0-9_]{0,40}$")) {
            throw new BizException(1002, "字段键只能用小写字母、数字、下划线，且以字母开头");
        }
        return key;
    }

    private String fieldType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase();
        if (!FIELD_TYPES.contains(type)) {
            throw new BizException(1002, "不支持的字段类型：" + value);
        }
        return type;
    }

    private String label(String value) {
        String label = value == null ? "" : value.trim();
        if (label.isEmpty()) {
            throw new BizException(1002, "请填写字段名称");
        }
        return label;
    }

    private List<String> options(String fieldType, List<String> raw) {
        if (!"SELECT".equals(fieldType)) {
            return null;
        }
        List<String> options = new ArrayList<>();
        if (raw != null) {
            for (String option : raw) {
                if (option != null && !option.isBlank()) {
                    options.add(option.trim());
                }
            }
        }
        if (options.isEmpty()) {
            throw new BizException(1002, "下拉字段至少要配置一个选项");
        }
        return options;
    }

    private Object defaultValue(String fieldType, List<String> options, Object raw, String label) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        return normalizeValue(fieldType, options, false, label, raw);
    }

    private Long factory(Long factoryOrgId) {
        if (factoryOrgId == null) {
            return null;
        }
        Integer exists = jdbcTemplate.queryForObject("""
                select count(*) from sys_org where id = ? and type = 'PLANT' and enabled
                """, Integer.class, factoryOrgId);
        if (exists == null || exists == 0) {
            throw new BizException(1002, "作用工厂不存在或已停用");
        }
        return factoryOrgId;
    }

    private List<String> roles(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : raw) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String code = role.trim().toUpperCase();
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from sys_role where code = ?", Integer.class, code);
            if (exists == null || exists == 0) {
                throw new BizException(1002, "角色不存在：" + role);
            }
            roles.add(code);
        }
        return roles.isEmpty() ? null : new ArrayList<>(roles);
    }

    private void requireNoKeyCollision(String entityType, String fieldKey, Long factoryOrgId,
                                       long excludeId) {
        String sql;
        List<Object> args = new ArrayList<>(List.of(entityType, fieldKey));
        if (factoryOrgId == null) {
            // 全局字段不允许和任何工厂的同名字段并存，避免表单里出现同名 key
            sql = """
                    select count(*) from sys_custom_field
                    where entity_type = ? and field_key = ? and id <> ?
                    """;
            args.add(excludeId);
        } else {
            sql = """
                    select count(*) from sys_custom_field
                    where entity_type = ? and field_key = ? and id <> ?
                      and (factory_org_id is null or factory_org_id = ?)
                    """;
            args.add(excludeId);
            args.add(factoryOrgId);
        }
        Integer exists = jdbcTemplate.queryForObject(sql, Integer.class, args.toArray());
        if (exists != null && exists > 0) {
            throw new BizException(1002, "字段键已存在：" + fieldKey);
        }
    }

    private FieldDefinition definitionOf(Map<String, Object> row) {
        return new FieldDefinition(((Number) row.get("id")).longValue(),
                String.valueOf(row.get("entity_type")), String.valueOf(row.get("field_key")),
                String.valueOf(row.get("label")), String.valueOf(row.get("field_type")),
                Boolean.TRUE.equals(row.get("required")), readStringList(row.get("options")),
                readObject(row.get("defaultValue")),
                ((Number) row.get("sort_no")).intValue(), Boolean.TRUE.equals(row.get("enabled")),
                row.get("factoryOrgId") == null ? null : ((Number) row.get("factoryOrgId")).longValue(),
                readStringList(row.get("visibleRoles")),
                readStringList(row.get("editableRoles")));
    }

    private List<String> readStringList(Object text) {
        if (text == null || String.valueOf(text).isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(String.valueOf(text),
                    new TypeReference<List<String>>() {
                    });
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private Object readObject(Object text) {
        if (text == null || String.valueOf(text).isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(String.valueOf(text), Object.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String jsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return writeJson(value);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new BizException(1002, "自定义字段值序列化失败");
        }
    }
}
