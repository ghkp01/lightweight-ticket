package com.mes.platform.config;

import com.mes.common.BizException;
import com.mes.platform.audit.OperLogService;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 系统配置：按 key 存，value_type 决定校验与前端控件。
 * TEXT / NUMBER / BOOLEAN 之外还有 SELECT（取 options 里的值）；
 * sensitive=true 的配置只写不读，列表里显示掩码。
 *
 * <p>属于平台能力：19 个业务模块都要读配置（水印、S3、排产阈值、安全锁定…），
 * 放在 {@code platform.config} 后业务模块依赖它不再算跨模块依赖，拆进程时可整体下沉。</p>
 */
@Service
public class ConfigService {

    /** 密文配置回传这个掩码表示「不修改」 */
    public static final String MASK = "******";

    private final JdbcTemplate jdbcTemplate;
    private final OperLogService operLogService;

    public ConfigService(JdbcTemplate jdbcTemplate, OperLogService operLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.operLogService = operLogService;
    }

    public record ConfigRequest(String value) {
    }

    /** 配置页用：带标题、分组与说明 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select config_key as "key", config_value as "value", value_type as "valueType",
                       title, group_name as "group", remark, options, sensitive,
                       updated_by as "updatedBy",
                       to_char(updated_at, 'YYYY-MM-DD HH24:MI') as "updatedAt"
                from sys_config
                order by group_name, config_key
                """);
        for (Map<String, Object> row : rows) {
            boolean sensitive = Boolean.TRUE.equals(row.get("sensitive"));
            String value = row.get("value") == null ? "" : String.valueOf(row.get("value"));
            row.put("hasValue", !value.isEmpty());
            if (sensitive) {
                row.put("value", value.isEmpty() ? "" : MASK);
            }
        }
        return rows;
    }

    /** 前端启动时读一次，只取 key → value，避免把说明文案带进每个页面；密文不下发 */
    public Map<String, String> map() {
        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.query("select config_key, config_value, sensitive from sys_config", rs -> {
            if (!rs.getBoolean("sensitive")) {
                result.put(rs.getString("config_key"), rs.getString("config_value"));
            }
        });
        return result;
    }

    public boolean booleanValue(String key, boolean defaultValue) {
        String value = stringValue(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value);
    }

    public String stringValue(String key, String defaultValue) {
        List<String> values = jdbcTemplate.queryForList(
                "select config_value from sys_config where config_key = ?", String.class, key);
        if (values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return defaultValue;
        }
        return values.get(0).trim();
    }

    public int intValue(String key, int defaultValue) {
        String value = stringValue(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional
    public void update(String key, String value) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select config_key, config_value, value_type, title, options, sensitive
                from sys_config where config_key = ?
                """, key);
        if (rows.isEmpty()) {
            throw new BizException(2019, "配置项不存在");
        }
        Map<String, Object> row = rows.get(0);
        String type = String.valueOf(row.get("value_type"));
        boolean sensitive = Boolean.TRUE.equals(row.get("sensitive"));
        String incoming = value == null ? "" : value.trim();
        String current = row.get("config_value") == null ? "" : String.valueOf(row.get("config_value"));

        String normalized;
        if (sensitive && (incoming.isEmpty() || MASK.equals(incoming))) {
            // 密文留空或仍是掩码：保持原值不动
            normalized = current;
        } else {
            normalized = normalize(type, incoming, row.get("options"));
        }

        jdbcTemplate.update("""
                update sys_config set config_value = ?, updated_by = ?, updated_at = now()
                where config_key = ?
                """, normalized, CurrentUser.name(), key);

        String title = String.valueOf(row.get("title"));
        String shown = sensitive ? MASK : normalized;
        operLogService.touch("CONFIG", null, key, "修改配置「" + title + "」为 " + shown);
    }

    private String normalize(String type, String value, Object options) {
        String text = value == null ? "" : value.trim();
        if ("BOOLEAN".equals(type)) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.equals("true") && !lower.equals("false")) {
                throw new BizException(1002, "该配置只能填 true 或 false");
            }
            return lower;
        }
        if ("NUMBER".equals(type)) {
            try {
                new java.math.BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new BizException(1002, "该配置只能填数字");
            }
        }
        if ("SELECT".equals(type)) {
            String allowed = options == null ? "" : String.valueOf(options);
            for (String option : allowed.split(",")) {
                if (option.trim().equalsIgnoreCase(text)) {
                    return option.trim();
                }
            }
            throw new BizException(1002, "该配置只能填：" + allowed);
        }
        return text;
    }
}
