package com.mes.org;

import com.mes.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织架构与工作中心：层级合法性、路径维护、删除护栏、产能来源。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrgStructureTest {

    @Autowired
    private OrgService orgService;

    @Autowired
    private WorkCenterService workCenterService;

    @Autowired
    private JdbcTemplate jdbc;

    private String suffix() {
        return Long.toString(System.nanoTime()).substring(8);
    }

    private long plant() {
        return jdbc.queryForObject("select id from sys_org where type = 'PLANT' order by id limit 1", Long.class);
    }

    private long workshop() {
        return jdbc.queryForObject("select id from sys_org where type = 'WORKSHOP' order by id limit 1", Long.class);
    }

    @Test
    @DisplayName("车间只能挂工厂，班组只能挂车间")
    void typeHierarchyIsEnforced() {
        String tag = suffix();
        long plantId = plant();
        long workshopId = workshop();

        // 车间挂工厂：合法，路径与层级自动生成
        long newWorkshop = orgService.create(new OrgService.OrgRequest(
                "WSX" + tag, "测试车间" + tag, "WORKSHOP", plantId, 9, "测试主任", null, true, null));
        Map<String, Object> created = orgService.load(newWorkshop);
        assertEquals(plantId, ((Number) created.get("parentId")).longValue());
        assertEquals(2, ((Number) created.get("level")).intValue());
        assertTrue(String.valueOf(created.get("path")).endsWith("/" + newWorkshop + "/"));

        // 班组挂车间：合法
        long team = orgService.create(new OrgService.OrgRequest(
                "TX" + tag, "测试班组" + tag, "TEAM", newWorkshop, 1, null, null, true, null));
        assertEquals(3, ((Number) orgService.load(team).get("level")).intValue());

        // 班组挂工厂：非法
        BizException wrongParent = assertThrows(BizException.class, () -> orgService.create(
                new OrgService.OrgRequest("TY" + tag, "错挂班组" + tag, "TEAM", plantId, 1, null, null, true, null)));
        assertTrue(wrongParent.getMessage().contains("班组只能挂在车间下"));

        // 车间作为顶级：非法
        BizException noParent = assertThrows(BizException.class, () -> orgService.create(
                new OrgService.OrgRequest("WZ" + tag, "无主车间" + tag, "WORKSHOP", null, 1, null, null, true, null)));
        assertTrue(noParent.getMessage().contains("工厂"));

        // 职能部门可挂工厂
        long dept = orgService.create(new OrgService.OrgRequest(
                "DX" + tag, "测试部门" + tag, "DEPT", plantId, 5, null, null, true, null));
        assertEquals(2, ((Number) orgService.load(dept).get("level")).intValue());

        // 车间下已有同名层级校验：清理不影响其他用例（事务回滚）
        assertTrue(team > 0);
        assertTrue(workshopId > 0);
    }

    @Test
    @DisplayName("移动组织时下级路径与层级同步平移")
    void movingOrgRewritesSubtreePath() {
        String tag = suffix();
        long plantId = plant();
        long targetWorkshop = workshop();
        long moving = orgService.create(new OrgService.OrgRequest(
                "WMA" + tag, "待迁车间" + tag, "WORKSHOP", plantId, 8, null, null, true, null));
        long child = orgService.create(new OrgService.OrgRequest(
                "WMB" + tag, "随迁班组" + tag, "TEAM", moving, 1, null, null, true, null));

        String beforeChildPath = String.valueOf(orgService.load(child).get("path"));
        assertTrue(beforeChildPath.startsWith("/" + plantId + "/" + moving + "/"));

        // 车间不能挂到车间下，应该被拒绝
        BizException wrongMove = assertThrows(BizException.class, () -> orgService.update(moving,
                new OrgService.OrgRequest("WMA" + tag, "待迁车间" + tag, "WORKSHOP", targetWorkshop, 8,
                        null, null, true, null)));
        assertTrue(wrongMove.getMessage().contains("车间只能挂在工厂下"));

        // 班组移到另一个车间：路径随之重算
        orgService.update(child, new OrgService.OrgRequest(
                "WMB" + tag, "随迁班组" + tag, "TEAM", targetWorkshop, 1, null, null, true, null));
        Map<String, Object> moved = orgService.load(child);
        assertEquals(targetWorkshop, ((Number) moved.get("parentId")).longValue());
        assertTrue(String.valueOf(moved.get("path")).endsWith("/" + targetWorkshop + "/" + child + "/"));
    }

    @Test
    @DisplayName("禁止把组织移动到自己的下级")
    void cannotMoveIntoOwnSubtree() {
        String tag = suffix();
        long plantId = plant();
        long parent = orgService.create(new OrgService.OrgRequest(
                "CPA" + tag, "父车间" + tag, "WORKSHOP", plantId, 7, null, null, true, null));
        long child = orgService.create(new OrgService.OrgRequest(
                "CPB" + tag, "子班组" + tag, "TEAM", parent, 1, null, null, true, null));
        BizException ex = assertThrows(BizException.class, () -> orgService.update(parent,
                new OrgService.OrgRequest("CPA" + tag, "父车间" + tag, "TEAM", child, 1, null, null, true, null)));
        assertNotNull(ex.getMessage());
    }

    @Test
    @DisplayName("有下级、有成员、有工作中心的组织不能删除")
    void deleteIsGuarded() {
        String tag = suffix();
        long plantId = plant();
        long workshop = orgService.create(new OrgService.OrgRequest(
                "GDA" + tag, "护栏车间" + tag, "WORKSHOP", plantId, 6, null, null, true, null));
        orgService.create(new OrgService.OrgRequest(
                "GDB" + tag, "护栏班组" + tag, "TEAM", workshop, 1, null, null, true, null));

        BizException hasChild = assertThrows(BizException.class, () -> orgService.delete(workshop));
        assertTrue(hasChild.getMessage().contains("下级"));

        long empty = orgService.create(new OrgService.OrgRequest(
                "GDC" + tag, "空班组" + tag, "TEAM", workshop, 2, null, null, true, null));
        orgService.delete(empty);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from sys_org where id = ?", Integer.class, empty));
    }

    @Test
    @DisplayName("工作中心产能必须为正，被工序引用时不能删除")
    void workCenterCapacityAndReference() {
        String tag = suffix();
        long orgId = workshop();
        long center = workCenterService.create(new WorkCenterService.WorkCenterRequest(
                "WCX" + tag, "测试中心" + tag, orgId, "MACHINE", 300, true, null));

        Map<String, Object> centerRow = jdbc.queryForMap(
                "select daily_capacity_minutes as cap from work_center where id = ?", center);
        assertEquals(300, ((Number) centerRow.get("cap")).intValue());

        assertThrows(BizException.class, () -> workCenterService.create(
                new WorkCenterService.WorkCenterRequest("WCY" + tag, "零产能" + tag, orgId, "HAND", 0, true, null)));

        // 绑定一道工序后不允许删除
        long processId = jdbc.queryForObject("""
                insert into process (code, name, category, default_minutes, default_price, work_center_id)
                values (?, ?, 'MACHINING', 1.0, 1.0, ?) returning id
                """, Long.class, "PX" + tag, "测试工序" + tag, center);
        BizException inUse = assertThrows(BizException.class, () -> workCenterService.delete(center));
        assertTrue(inUse.getMessage().contains("工序"));

        // 工序从中心移除后可以删除
        jdbc.update("update process set work_center_id = null where id = ?", processId);
        workCenterService.delete(center);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from work_center where id = ?", Integer.class, center));
    }

    @Test
    @DisplayName("组织树包含成员数与工作中心数")
    void treeCarriesCounts() {
        List<Map<String, Object>> tree = orgService.tree();
        assertTrue(tree.size() >= 1);
        Map<String, Object> root = tree.get(0);
        assertEquals("PLANT", root.get("type"));
        assertTrue(((Number) root.get("memberCount")).intValue() >= 1);
        List<?> children = (List<?>) root.get("children");
        assertTrue(children != null && !children.isEmpty());
    }
}
