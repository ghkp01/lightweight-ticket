package com.mes.integration;

import com.mes.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对外接口令牌：创建只返回一次明文、哈希落库、范围校验、停用与过期、权限范围白名单。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApiTokenTest {

    @Autowired
    private ApiTokenService service;

    @Autowired
    private JdbcTemplate jdbc;

    private String name;

    @BeforeEach
    void setUp() {
        name = "对接测试" + System.nanoTime();
    }

    @Test
    @DisplayName("创建返回明文，库里只存摘要，可按令牌换回上下文")
    void createAndAuthenticate() {
        Map<String, Object> created = service.create(new ApiTokenService.CreateRequest(
                name, List.of("WORK_ORDER", "INVENTORY"), null, "单测"));
        String token = String.valueOf(created.get("token"));
        assertTrue(token.startsWith("mes_"));
        assertEquals(12, String.valueOf(created.get("prefix")).length());

        String stored = jdbc.queryForObject("""
                select token_hash from sys_api_token where id = ?
                """, String.class, ((Number) created.get("id")).longValue());
        assertTrue(!stored.contains(token), "库里不能存明文");

        ApiTokenService.TokenContext context = service.authenticate(token);
        assertEquals(name, context.name());
        assertTrue(context.has("WORK_ORDER"));
        assertTrue(!context.has("COST"));

        Object lastUsed = jdbc.queryForObject("""
                select last_used_at from sys_api_token where id = ?
                """, Object.class, ((Number) created.get("id")).longValue());
        assertNotNull(lastUsed, "调用后应记录最近使用时间");
    }

    @Test
    @DisplayName("停用后立即失效，重新启用后恢复")
    void toggleRevokes() {
        Map<String, Object> created = service.create(new ApiTokenService.CreateRequest(
                name, List.of("WORK_ORDER"), null, null));
        String token = String.valueOf(created.get("token"));
        long id = ((Number) created.get("id")).longValue();

        service.toggle(id, false);
        BizException disabled = assertThrows(BizException.class, () -> service.authenticate(token));
        assertEquals(4030, disabled.getCode());

        service.toggle(id, true);
        assertEquals(name, service.authenticate(token).name());
    }

    @Test
    @DisplayName("过期令牌与不存在的令牌都按 4030 拒绝")
    void expiredAndUnknown() {
        Map<String, Object> created = service.create(new ApiTokenService.CreateRequest(
                name, List.of("MATERIAL"), LocalDate.now().minusDays(1), null));
        BizException expired = assertThrows(BizException.class,
                () -> service.authenticate(String.valueOf(created.get("token"))));
        assertEquals(4030, expired.getCode());

        BizException unknown = assertThrows(BizException.class,
                () -> service.authenticate("mes_not_exists"));
        assertEquals(4030, unknown.getCode());

        BizException empty = assertThrows(BizException.class, () -> service.authenticate(" "));
        assertEquals(4030, empty.getCode());
    }

    @Test
    @DisplayName("权限范围只接受白名单，空范围与空名称被拒")
    void scopesValidation() {
        BizException badScope = assertThrows(BizException.class,
                () -> service.create(new ApiTokenService.CreateRequest(name, List.of("ADMIN"), null, null)));
        assertEquals(1002, badScope.getCode());

        BizException noScope = assertThrows(BizException.class,
                () -> service.create(new ApiTokenService.CreateRequest(name, List.of(), null, null)));
        assertEquals(1002, noScope.getCode());

        BizException noName = assertThrows(BizException.class,
                () -> service.create(new ApiTokenService.CreateRequest(" ", List.of("COST"), null, null)));
        assertEquals(1002, noName.getCode());

        Map<String, Object> created = service.create(new ApiTokenService.CreateRequest(
                name, List.of("cost", "COST"), null, null));
        assertEquals(List.of("COST"), created.get("scopes"), "重复范围应去重并转大写");
    }

    @Test
    @DisplayName("列表带掩码前缀、范围与状态，删除后不可用")
    void listAndDelete() {
        Map<String, Object> created = service.create(new ApiTokenService.CreateRequest(
                name, List.of("COST"), null, null));
        long id = ((Number) created.get("id")).longValue();
        String token = String.valueOf(created.get("token"));

        List<Map<String, Object>> rows = service.list();
        Map<String, Object> row = rows.stream()
                .filter(item -> ((Number) item.get("id")).longValue() == id)
                .findFirst()
                .orElseThrow();
        assertEquals("COST", row.get("scopes"));
        assertEquals(Boolean.TRUE, row.get("enabled"));
        assertTrue(!String.valueOf(row.get("tokenPrefix")).equals(token));

        service.delete(id);
        assertThrows(BizException.class, () -> service.authenticate(token));
        assertTrue(service.list().stream().noneMatch(item -> ((Number) item.get("id")).longValue() == id));
    }

    @Test
    @DisplayName("开关关闭时所有令牌都不可用")
    void globalSwitch() {
        Map<String, Object> created = service.create(new ApiTokenService.CreateRequest(
                name, List.of("WORK_ORDER"), null, null));
        String token = String.valueOf(created.get("token"));
        jdbc.update("""
                update sys_config set config_value = 'false' where config_key = 'open.api.enabled'
                """);
        BizException closed = assertThrows(BizException.class, () -> service.authenticate(token));
        assertEquals(4031, closed.getCode());
        jdbc.update("""
                update sys_config set config_value = 'true' where config_key = 'open.api.enabled'
                """);
        assertEquals(name, service.authenticate(token).name());
    }
}
