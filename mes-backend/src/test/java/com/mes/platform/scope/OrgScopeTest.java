package com.mes.platform.scope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多工厂数据范围：默认不过滤、按组织树向上找工厂、开关控制生效。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrgScopeTest {

    @Autowired
    private OrgScopeService service;

    @Autowired
    private JdbcTemplate jdbc;

    private String username;

    @BeforeEach
    void setUp() {
        username = "scope" + System.nanoTime();
        // 挂到「下料班组」（工厂 → 车间 → 班组 的第三层）
        Long teamId = jdbc.queryForObject("""
                select id from sys_org where type = 'TEAM' order by id limit 1
                """, Long.class);
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, org_id, enabled)
                values (?, 'x', '范围测试', 'PLANNER', ?, true)
                """, username, teamId);
    }

    private void setSwitch(boolean enabled) {
        jdbc.update("""
                update sys_config set config_value = ? where config_key = 'org.isolation.enabled'
                """, String.valueOf(enabled));
    }

    @Test
    @DisplayName("开关关闭时不过滤")
    void disabledByDefault() {
        setSwitch(false);
        assertTrue(!service.isolationEnabled());
        assertNull(service.listFilter());
    }

    @Test
    @DisplayName("班组账号沿组织树向上找到所属工厂")
    void factoryFromTeam() {
        setSwitch(true);
        Long factoryId = service.factoryOf(username);
        assertNotNull(factoryId, "班组应能向上找到工厂");
        Long plantId = jdbc.queryForObject("""
                select id from sys_org where type = 'PLANT' order by id limit 1
                """, Long.class);
        assertEquals(plantId, factoryId);
    }

    @Test
    @DisplayName("没有组织归属的账号不参与隔离")
    void userWithoutOrg() {
        setSwitch(true);
        String freeUser = "free" + System.nanoTime();
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, 'x', '无组织', 'PLANNER', true)
                """, freeUser);
        assertNull(service.factoryOf(freeUser));
        assertEquals(service.defaultFactoryOrgId(), jdbc.queryForObject(
                "select id from sys_org where type = 'PLANT' order by id limit 1", Long.class));
    }

    @Test
    @DisplayName("未登录或空账号返回空")
    void blankUser() {
        setSwitch(true);
        assertNull(service.factoryOf(null));
        assertNull(service.factoryOf(" "));
        assertNull(service.factoryOf("not-exists-user"));
    }
}
