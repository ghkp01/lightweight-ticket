package com.mes.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class JwtService {

    /** 令牌里标记「还没改初始密码」，改了密码换新令牌后就没了 */
    public static final String CLAIM_MUST_CHANGE_PASSWORD = "mcp";

    private final SecretKey key;
    private final long expireHours;

    public JwtService(@Value("${mes.jwt.secret}") String secret,
                      @Value("${mes.jwt.expire-hours:12}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireHours = expireHours;
    }

    public String create(String username, String realName, String role) {
        return create(username, realName, role, false);
    }

    public String create(String username, String realName, String role, boolean mustChangePassword) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("name", realName);
        claims.put("role", role);
        // 只在需要改密时才写这个 claim，正常令牌保持最小
        if (mustChangePassword) {
            claims.put(CLAIM_MUST_CHANGE_PASSWORD, true);
        }
        return Jwts.builder()
                .subject(username)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 访问令牌有效期（秒），登录响应里带给前端 */
    public long expireSeconds() {
        return expireHours * 3600;
    }
}
