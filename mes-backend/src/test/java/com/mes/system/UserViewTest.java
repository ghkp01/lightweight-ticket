package com.mes.system;

import com.mes.common.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表格视图：按用户保存列设置与默认筛选，默认互斥、重名拒绝、跨用户隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserViewTest {

    @Autowired
    private UserViewService service;

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> payload(String status) {
        return Map.of(
                "columns", List.of(
                        Map.of("key", "workNo", "visible", true, "width", 175, "fixed", "left"),
                        Map.of("key", "product", "visible", false, "width", 170, "fixed", "")),
                "filters", Map.of("status", status),
                "pageSize", 20);
    }

    private void login(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @BeforeEach
    void setUp() {
        login("admin");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("保存视图后可列出，列设置与筛选条件原样返回")
    void createAndList() {
        long id = service.create(new UserViewService.ViewRequest(
                "production.work-orders", "装配车间视图", payload("IN_PROGRESS"), false));
        List<Map<String, Object>> list = service.list("production.work-orders");
        assertEquals(1, list.size());
        Map<String, Object> view = list.get(0);
        assertEquals(id, ((Number) view.get("id")).longValue());
        assertEquals("装配车间视图", view.get("name"));
        assertFalse(Boolean.TRUE.equals(view.get("isDefault")));

        Map<String, Object> content = (Map<String, Object>) view.get("view");
        assertEquals(20, ((Number) content.get("pageSize")).intValue());
        assertEquals("IN_PROGRESS", ((Map<String, Object>) content.get("filters")).get("status"));
        List<Map<String, Object>> columns = (List<Map<String, Object>>) content.get("columns");
        assertEquals("workNo", columns.get(0).get("key"));
        assertEquals(false, columns.get(1).get("visible"));
    }

    @Test
    @DisplayName("同一页面的视图重名被拒")
    void duplicateNameRejected() {
        service.create(new UserViewService.ViewRequest(
                "production.work-orders", "我的视图", payload(""), false));
        BizException duplicate = assertThrows(BizException.class, () -> service.create(
                new UserViewService.ViewRequest("production.work-orders", "我的视图", payload(""), false)));
        assertEquals(2015, duplicate.getCode());
    }

    @Test
    @DisplayName("设为默认互斥：同页面只保留一个默认视图")
    void defaultIsExclusive() {
        long first = service.create(new UserViewService.ViewRequest(
                "production.work-orders", "视图 A", payload(""), false));
        long second = service.create(new UserViewService.ViewRequest(
                "production.work-orders", "视图 B", payload(""), false));
        service.setDefault(first);
        service.setDefault(second);

        List<Map<String, Object>> list = service.list("production.work-orders");
        Map<String, Object> a = list.stream().filter(row -> ((Number) row.get("id")).longValue() == first)
                .findFirst().orElseThrow();
        Map<String, Object> b = list.stream().filter(row -> ((Number) row.get("id")).longValue() == second)
                .findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(a.get("isDefault")));
        assertTrue(Boolean.TRUE.equals(b.get("isDefault")));
        // 默认视图排在最前，前端打开页面即可直接用
        assertEquals(second, ((Number) list.get(0).get("id")).longValue());
    }

    @Test
    @DisplayName("更新视图内容与名称，改成默认时清掉其它默认")
    void updateView() {
        long first = service.create(new UserViewService.ViewRequest(
                "warehouse.inventory", "全部库存", payload(""), true));
        long second = service.create(new UserViewService.ViewRequest(
                "warehouse.inventory", "只看预警", payload(""), false));
        service.update(second, new UserViewService.ViewRequest(
                "warehouse.inventory", "只看预警物料", payload("ONLY_WARNING"), true));

        List<Map<String, Object>> list = service.list("warehouse.inventory");
        Map<String, Object> updated = list.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == second).findFirst().orElseThrow();
        Map<String, Object> old = list.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == first).findFirst().orElseThrow();
        assertEquals("只看预警物料", updated.get("name"));
        assertTrue(Boolean.TRUE.equals(updated.get("isDefault")));
        assertFalse(Boolean.TRUE.equals(old.get("isDefault")));
    }

    @Test
    @DisplayName("视图按用户隔离，不能列出或删除别人的视图")
    void isolateByUser() {
        long id = service.create(new UserViewService.ViewRequest(
                "production.work-orders", "管理员视图", payload(""), false));

        login("planner");
        assertTrue(service.list("production.work-orders").isEmpty());
        BizException denied = assertThrows(BizException.class, () -> service.delete(id));
        assertEquals(2012, denied.getCode());

        login("admin");
        service.delete(id);
        assertTrue(service.list("production.work-orders").isEmpty());
        Integer left = jdbc.queryForObject("select count(*) from sys_user_view", Integer.class);
        assertEquals(0, left);
    }
}
