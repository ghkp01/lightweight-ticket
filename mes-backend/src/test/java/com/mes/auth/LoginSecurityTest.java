package com.mes.auth;

import com.mes.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录安全：密码强度、连续失败锁定与管理员解锁、刷新令牌轮换与撤销。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LoginSecurityTest {

    @Autowired
    private LoginSecurityService loginSecurityService;

    @Autowired
    private AuthController authController;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String username;
    private static final String PASSWORD = "Test@1234";

    @BeforeEach
    void setUp() {
        username = "lsec" + System.nanoTime();
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, ?, '安全测试', 'QC', true)
                """, username, passwordEncoder.encode(PASSWORD));
    }

    private AuthController.LoginRequest login(String password) {
        return new AuthController.LoginRequest(username, password, null, null);
    }

    @Test
    @DisplayName("密码强度：长度与三类字符")
    void passwordPolicy() {
        assertTrue(loginSecurityService.passwordProblem("Ab1@").contains("至少"));
        assertTrue(loginSecurityService.passwordProblem("abcdefgh").contains("三类"));
        assertTrue(loginSecurityService.passwordProblem("abcdefg1").contains("三类"));
        assertNull(loginSecurityService.passwordProblem("Test@1234"));
        assertThrows(BizException.class, () -> loginSecurityService.validatePassword("123456"));
    }

    @Test
    @DisplayName("连续失败达到上限后锁定，正确密码也进不去，解锁后恢复")
    void lockAfterFailures() {
        int max = loginSecurityService.maxFail();
        assertTrue(max > 0, "测试环境应开启锁定");
        for (int i = 1; i < max; i++) {
            BizException ex = assertThrows(BizException.class,
                    () -> authController.login(login("wrong-pass")));
            assertEquals(4001, ex.getCode());
            assertTrue(ex.getMessage().contains("还可尝试"));
        }
        BizException locked = assertThrows(BizException.class,
                () -> authController.login(login("wrong-pass")));
        assertEquals(4021, locked.getCode(), "第 " + max + " 次失败应触发锁定");

        BizException still = assertThrows(BizException.class, () -> authController.login(login(PASSWORD)));
        assertEquals(4021, still.getCode(), "锁定期间正确密码也不放行");
        assertTrue(still.getMessage().contains("锁定"));

        Map<String, Object> row = jdbc.queryForMap(
                "select failed_attempts, locked_until from sys_user where username = ?", username);
        assertNotNull(row.get("locked_until"), "锁定时间要落库");

        loginSecurityService.unlock(username);
        Object token = authController.login(login(PASSWORD)).data();
        assertNotNull(((Map<?, ?>) token).get("token"), "解锁后可以正常登录");
        Integer attempts = jdbc.queryForObject(
                "select failed_attempts from sys_user where username = ?", Integer.class, username);
        assertEquals(0, attempts, "登录成功清零失败次数");
    }

    @Test
    @DisplayName("刷新令牌：登录发放、轮换后旧令牌失效、退出撤销")
    void refreshTokenFlow() {
        Map<?, ?> data = (Map<?, ?>) authController.login(login(PASSWORD)).data();
        String refreshToken = String.valueOf(data.get("refreshToken"));
        assertNotNull(refreshToken);
        assertTrue(((Number) data.get("expiresIn")).longValue() > 0);

        Map<String, Object> rotated = loginSecurityService.rotate(refreshToken, "junit");
        String next = String.valueOf(rotated.get("refreshToken"));
        assertTrue(!next.equals(refreshToken), "轮换后应换一张新令牌");

        BizException reused = assertThrows(BizException.class,
                () -> loginSecurityService.rotate(refreshToken, "junit"));
        assertEquals(4022, reused.getCode(), "旧令牌不能重复使用");

        loginSecurityService.revoke(next);
        BizException revoked = assertThrows(BizException.class,
                () -> loginSecurityService.rotate(next, "junit"));
        assertEquals(4022, revoked.getCode());

        BizException unknown = assertThrows(BizException.class,
                () -> loginSecurityService.rotate("not-a-token", "junit"));
        assertEquals(4022, unknown.getCode());
    }

    @Test
    @DisplayName("按配置计算锁定剩余分钟")
    void lockMinutesLeft() {
        assertEquals(0, loginSecurityService.lockedMinutesLeft(null));
        long left = loginSecurityService.lockedMinutesLeft(
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(120)));
        assertTrue(left == 2 || left == 3, "两分钟应算成 2~3 分钟，实际 " + left);
    }
}
