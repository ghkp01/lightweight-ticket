package com.mes.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 上线前的最后一道保险：prod 配置文件里不许出现默认口令与本地域名。
 *
 * <p>以前 application.yml 里 DB 密码默认 mes123、JWT 密钥默认一串明文字面量，
 * 忘了设环境变量也能启动，等于把门钥匙挂在门上。这里在启动阶段直接拦住。</p>
 *
 * <p>profiles.active 里带 prod 时才生效，本地开发和测试完全不受影响。</p>
 */
@Component
public class ProductionGuard implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProductionGuard.class);

    /** 开发期用的默认 JWT 密钥，出现即为事故 */
    private static final String DEFAULT_JWT_SECRET = "mes-workshop-secret-key-please-change-in-production-2026";
    /** 允许出现的历史默认库密码 */
    private static final List<String> WEAK_DB_PASSWORDS = List.of("mes123", "postgres", "123456", "mes");
    /** JWT 密钥最短长度，HS 系列密钥太短等于没签 */
    private static final int MIN_SECRET_LENGTH = 32;

    private final Environment environment;
    private final String jwtSecret;
    private final String dbPassword;
    private final String corsOrigins;

    public ProductionGuard(Environment environment,
                           @Value("${mes.jwt.secret:}") String jwtSecret,
                           @Value("${spring.datasource.password:}") String dbPassword,
                           @Value("${mes.cors.allowed-origins:}") String corsOrigins) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.dbPassword = dbPassword;
        this.corsOrigins = corsOrigins;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!isProduction()) {
            return;
        }
        List<String> problems = new java.util.ArrayList<>();

        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            problems.add("JWT_SECRET 还是代码里的默认值，必须换成随机密钥（openssl rand -base64 48）");
        } else if (jwtSecret == null || jwtSecret.length() < MIN_SECRET_LENGTH) {
            problems.add("JWT_SECRET 长度不足 " + MIN_SECRET_LENGTH + " 个字符");
        }

        if (dbPassword == null || dbPassword.isBlank()) {
            problems.add("DB_PASSWORD 为空");
        } else if (WEAK_DB_PASSWORDS.contains(dbPassword.toLowerCase())) {
            problems.add("DB_PASSWORD 是常见弱口令（" + dbPassword + "），请改成强密码");
        }

        if (corsOrigins != null && corsOrigins.contains("localhost")) {
            problems.add("MES_CORS_ORIGINS 还包含 localhost，生产请配真实域名");
        }

        if (!problems.isEmpty()) {
            String message = "生产环境配置检查未通过：\n  - " + String.join("\n  - ", problems);
            log.error(message);
            // 直接让进程退出：带着默认密钥上线比启动失败危险得多
            throw new IllegalStateException(message);
        }
        log.info("生产环境配置检查通过，日志目录：{}", logPathHint());
    }

    private String logPathHint() {
        String path = environment.getProperty("LOG_PATH");
        return path == null || path.isBlank() ? "logs（可通过 LOG_PATH 覆盖）" : path;
    }

    private boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile)
                        || "production".equalsIgnoreCase(profile));
    }
}
