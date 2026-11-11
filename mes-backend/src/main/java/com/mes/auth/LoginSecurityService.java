package com.mes.auth;

import com.mes.auth.api.LoginPolicyPort;
import com.mes.common.BizException;
import com.mes.platform.config.ConfigService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 登录安全策略：失败锁定、密码强度、刷新令牌。
 * 阈值都放在 sys_config 里（安全分组），改了即时生效，不用重启。
 */
@Service
public class LoginSecurityService implements LoginPolicyPort {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    public LoginSecurityService(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    public int maxFail() {
        return Math.max(0, configService.intValue("security.login.max-fail", 5));
    }

    public int lockMinutes() {
        return Math.max(1, configService.intValue("security.login.lock-minutes", 15));
    }

    public int minPasswordLength() {
        return Math.max(6, configService.intValue("security.password.min-length", 8));
    }

    public boolean requireComplex() {
        return configService.booleanValue("security.password.require-complex", true);
    }

    public int refreshDays() {
        return Math.max(1, configService.intValue("security.refresh.days", 7));
    }

    /** 账号被锁时给出还剩多少分钟，未锁定返回 0 */
    public long lockedMinutesLeft(Object lockedUntil) {
        if (lockedUntil == null) {
            return 0;
        }
        Instant until = lockedUntil instanceof Timestamp ts ? ts.toInstant()
                : lockedUntil instanceof Instant instant ? instant : null;
        if (until == null) {
            return 0;
        }
        long seconds = until.getEpochSecond() - Instant.now().getEpochSecond();
        return seconds <= 0 ? 0 : (seconds + 59) / 60;
    }

    /**
     * 记一次失败。达到上限就把账号锁起来，返回剩余可尝试次数（0 表示刚被锁）。
     */
    @Transactional
    public int recordFailure(long userId, int currentAttempts) {
        int attempts = currentAttempts + 1;
        int max = maxFail();
        if (max > 0 && attempts >= max) {
            jdbcTemplate.update("""
                    update sys_user set failed_attempts = 0, locked_until = now() + (? || ' minutes')::interval
                    where id = ?
                    """, lockMinutes(), userId);
            return 0;
        }
        jdbcTemplate.update("update sys_user set failed_attempts = ? where id = ?", attempts, userId);
        return Math.max(0, max - attempts);
    }

    @Transactional
    public void recordSuccess(long userId) {
        jdbcTemplate.update("""
                update sys_user set failed_attempts = 0, locked_until = null, last_login_at = now()
                where id = ?
                """, userId);
    }

    /** 管理员解锁：清掉失败次数与锁定时间 */
    @Transactional
    @Override
    public void unlock(String username) {
        jdbcTemplate.update("""
                update sys_user set failed_attempts = 0, locked_until = null where username = ?
                """, username);
    }

    /**
     * 密码强度校验：长度 + 复杂度。宽松模式下只校验长度。
     * 返回提示文案，校验通过返回 null。
     */
    public String passwordProblem(String password) {
        if (password == null || password.isBlank()) {
            return "密码不能为空";
        }
        int min = minPasswordLength();
        if (password.length() < min) {
            return "密码至少 " + min + " 位";
        }
        if (!requireComplex()) {
            return null;
        }
        int kinds = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) {
            kinds++;
        }
        if (password.chars().anyMatch(Character::isLowerCase)) {
            kinds++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            kinds++;
        }
        if (password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))) {
            kinds++;
        }
        if (kinds < 3) {
            return "密码需包含大写字母、小写字母、数字、符号中的至少三类";
        }
        return null;
    }

    @Override
    public void validatePassword(String password) {
        String problem = passwordProblem(password);
        if (problem != null) {
            throw new BizException(1002, problem);
        }
    }

    /** 发一张刷新令牌，返回明文（只在这一刻出现，库里存摘要） */
    @Transactional
    public String issueRefreshToken(String username, String client) {
        byte[] bytes = new byte[36];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbcTemplate.update("""
                insert into sys_refresh_token (username, token_hash, expires_at, client)
                values (?, ?, now() + (? || ' days')::interval, ?)
                """, username, hash(token), refreshDays(),
                client == null ? null : client.substring(0, Math.min(client.length(), 255)));
        return token;
    }

    /** 换发新令牌（旧的立刻作废，避免同一张令牌被反复使用） */
    @Transactional
    public Map<String, Object> rotate(String token, String client) {
        String hash = hash(token == null ? "" : token.trim());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, username, revoked, (expires_at < now()) as expired
                from sys_refresh_token where token_hash = ?
                """, hash);
        if (rows.isEmpty()) {
            throw new BizException(4022, "刷新令牌无效，请重新登录");
        }
        Map<String, Object> row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("revoked")) || Boolean.TRUE.equals(row.get("expired"))) {
            throw new BizException(4022, "刷新令牌已失效，请重新登录");
        }
        long id = ((Number) row.get("id")).longValue();
        String username = String.valueOf(row.get("username"));
        jdbcTemplate.update("update sys_refresh_token set revoked = true, revoked_at = now() where id = ?", id);
        String next = issueRefreshToken(username, client);
        return Map.of("username", username, "refreshToken", next);
    }

    @Transactional
    public void revoke(String token) {
        jdbcTemplate.update("""
                update sys_refresh_token set revoked = true, revoked_at = now()
                where token_hash = ? and revoked = false
                """, hash(token == null ? "" : token.trim()));
    }

    /** 顺手清掉过期令牌，登录时调用，不需要单独任务 */
    @Transactional
    public void purgeExpired() {
        jdbcTemplate.update("delete from sys_refresh_token where expires_at < now() - interval '1 day'");
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
