package com.mes.integration;

import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.platform.config.ConfigService;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对外接口令牌：给 ERP / 财务等外部系统用。
 * 令牌只存 SHA-256 摘要，明文仅在创建时返回一次；可设有效期与权限范围，停用后立即失效。
 */
@Service
public class ApiTokenService {

    public static final List<String> SCOPES = List.of("WORK_ORDER", "INVENTORY", "MATERIAL", "COST");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    public ApiTokenService(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    public record CreateRequest(String name, List<String> scopes, LocalDate expiresOn, String remark) {
    }

    /** 校验通过后返回的调用方信息 */
    public record TokenContext(long id, String name, List<String> scopes) {
        public boolean has(String scope) {
            return scopes.contains(scope);
        }
    }

    public boolean enabled() {
        return configService.booleanValue("open.api.enabled", true);
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, token_prefix as "tokenPrefix", scopes, enabled,
                       to_char(expires_at, 'YYYY-MM-DD') as "expiresOn",
                       to_char(last_used_at, 'YYYY-MM-DD HH24:MI') as "lastUsedAt",
                       created_by as "createdBy", remark,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       (expires_at is not null and expires_at < now()) as "expired"
                from sys_api_token order by id desc
                """);
        return rows;
    }

    /** 令牌列表分页；不传 page 时仍走 list() 返回全量 */
    public PageResult page(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Long total = jdbcTemplate.queryForObject("select count(*) from sys_api_token", Long.class);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, token_prefix as "tokenPrefix", scopes, enabled,
                       to_char(expires_at, 'YYYY-MM-DD') as "expiresOn",
                       to_char(last_used_at, 'YYYY-MM-DD HH24:MI') as "lastUsedAt",
                       created_by as "createdBy", remark,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       (expires_at is not null and expires_at < now()) as "expired"
                from sys_api_token order by id desc limit ? offset ?
                """, safeSize, (long) (safePage - 1) * safeSize);
        return new PageResult(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** 新建令牌：明文只在这里返回一次 */
    @Transactional
    public Map<String, Object> create(CreateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BizException(1002, "请填写令牌名称，例如「ERP 对接」");
        }
        List<String> scopes = normalizeScopes(request.scopes());
        if (scopes.isEmpty()) {
            throw new BizException(1002, "请至少选择一个权限范围");
        }
        byte[] bytes = new byte[30];
        RANDOM.nextBytes(bytes);
        String plain = "mes_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String prefix = plain.substring(0, 12);
        Long id = jdbcTemplate.queryForObject("""
                insert into sys_api_token (name, token_hash, token_prefix, scopes, enabled,
                                           expires_at, created_by, remark)
                values (?, ?, ?, ?, true, ?, ?, ?) returning id
                """, Long.class, request.name().trim(), hash(plain), prefix, String.join(",", scopes),
                request.expiresOn() == null ? null : Timestamp.valueOf(request.expiresOn().atStartOfDay()),
                CurrentUser.name(), request.remark());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", request.name().trim());
        result.put("token", plain);
        result.put("prefix", prefix);
        result.put("scopes", scopes);
        result.put("expiresOn", request.expiresOn() == null ? null : String.valueOf(request.expiresOn()));
        return result;
    }

    @Transactional
    public void toggle(long id, boolean enabled) {
        int updated = jdbcTemplate.update("update sys_api_token set enabled = ? where id = ?", enabled, id);
        if (updated == 0) {
            throw new BizException(4001, "令牌不存在");
        }
    }

    @Transactional
    public void delete(long id) {
        jdbcTemplate.update("delete from sys_api_token where id = ?", id);
    }

    /**
     * 校验令牌：存在、启用、未过期；用完更新最近使用时间。
     * 失败统一报 4030，不区分「不存在」与「过期」，避免被枚举。
     */
    @Transactional
    public TokenContext authenticate(String token) {
        if (!enabled()) {
            throw new BizException(4031, "对外接口已关闭，请联系管理员开启「启用对外接口」");
        }
        if (token == null || token.isBlank()) {
            throw new BizException(4030, "缺少 X-API-Token 请求头");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, scopes, enabled, (expires_at is not null and expires_at < now()) as expired
                from sys_api_token where token_hash = ?
                """, hash(token.trim()));
        if (rows.isEmpty()) {
            throw new BizException(4030, "令牌无效或已失效");
        }
        Map<String, Object> row = rows.get(0);
        if (!Boolean.TRUE.equals(row.get("enabled")) || Boolean.TRUE.equals(row.get("expired"))) {
            throw new BizException(4030, "令牌无效或已失效");
        }
        long id = ((Number) row.get("id")).longValue();
        jdbcTemplate.update("update sys_api_token set last_used_at = now() where id = ?", id);
        List<String> scopes = new ArrayList<>();
        for (String scope : String.valueOf(row.get("scopes")).split(",")) {
            if (!scope.isBlank()) {
                scopes.add(scope.trim().toUpperCase(Locale.ROOT));
            }
        }
        return new TokenContext(id, String.valueOf(row.get("name")), scopes);
    }

    private List<String> normalizeScopes(List<String> scopes) {
        List<String> result = new ArrayList<>();
        if (scopes == null) {
            return result;
        }
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) {
                continue;
            }
            String value = scope.trim().toUpperCase(Locale.ROOT);
            if (!SCOPES.contains(value)) {
                throw new BizException(1002, "不支持的权限范围：" + scope
                        + "，可选 " + String.join(" / ", SCOPES));
            }
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
