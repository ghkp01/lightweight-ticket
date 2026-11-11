package com.mes.notice;

import com.mes.andon.AndonService;
import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.notice.api.NoticeMessage;
import com.mes.push.PushService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站内消息与推送联动：消息带单据号能定位、按分类筛、安灯进待办、
 * 推送设备登记与「没接通道时静默跳过」。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NoticeCenterTest {

    @Autowired
    private NoticeService noticeService;

    @Autowired
    private PushService pushService;

    @Autowired
    private AndonService andonService;

    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;
    private String planner;
    private String admin;

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
        planner = "pl" + suffix;
        admin = "ad" + suffix;
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, 'x', ?, 'PLANNER', true)
                """, planner, "计划" + suffix);
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, 'x', ?, 'ADMIN', true)
                """, admin, "管理员" + suffix);
        login("tester" + suffix);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("通知带单据号与分类，可按分类筛出来")
    void noticeCarriesRefAndCategory() {
        String receiver = "rcv" + suffix;
        noticeService.push(receiver, NoticeMessage.of("TASK", "采购申请待审批", "金额 1200 元",
                "REQUISITION", "PR202601010001"));
        noticeService.push(receiver, NoticeMessage.of("ALERT", "不良待处置", "数量 3",
                "DEFECT", "DF202601010001"));

        List<Map<String, Object>> all = noticeService.mine(receiver, null, null, 50);
        assertEquals(2, all.size());
        Map<String, Object> requisition = all.stream()
                .filter(row -> "REQUISITION".equals(row.get("refType"))).findFirst().orElseThrow();
        assertEquals("PR202601010001", requisition.get("refNo"));
        assertEquals("TASK", requisition.get("category"));

        List<Map<String, Object>> alerts = noticeService.mine(receiver, "ALERT", null, 50);
        assertEquals(1, alerts.size());
        assertEquals("DEFECT", alerts.get(0).get("refType"));

        List<Map<String, Object>> tasks = noticeService.mine(receiver, "TASK", true, 50);
        assertEquals(1, tasks.size());
    }

    @Test
    @DisplayName("推送设备可登记、可注销，非法平台被拒")
    void pushDeviceRegisterAndUnregister() {
        pushService.register(new PushService.RegisterRequest("APP-ANDROID", "JPUSH", "cid-" + suffix));
        assertEquals(1, count("""
                select count(*) from sys_push_device where username = ? and provider = 'JPUSH'
                """, "tester" + suffix));

        // 重复登记同一 token 只留一条，且仍是启用状态
        pushService.register(new PushService.RegisterRequest("APP-ANDROID", "JPUSH", "cid-" + suffix));
        assertEquals(1, count("""
                select count(*) from sys_push_device where username = ?
                """, "tester" + suffix));

        List<Map<String, Object>> devices = pushService.myDevices();
        assertEquals(1, devices.size());
        assertTrue(String.valueOf(devices.get(0).get("token")).contains("****"));

        // 没有对应通道实现时只跳过，不能抛异常
        pushService.dispatch("tester" + suffix, new com.mes.push.api.PushMessage(
                "TASK", "标题", "内容", "WORK_ORDER", "WO1"));

        pushService.unregister("cid-" + suffix);
        assertEquals(0, count("""
                select count(*) from sys_push_device where username = ?
                """, "tester" + suffix));

        BizException invalid = assertThrows(BizException.class, () ->
                pushService.register(new PushService.RegisterRequest("PC", "JPUSH", "t")));
        assertEquals(1002, invalid.getCode());
    }

    @Test
    @DisplayName("推送配置默认未启用，说明文字提醒只到站内")
    void pushConfigDefaultsToDisabled() {
        Map<String, Object> config = pushService.config();
        assertEquals("NONE", config.get("provider"));
        assertEquals(false, config.get("enabled"));
        assertTrue(String.valueOf(config.get("hint")).contains("桌面通知"));
        // Web Push 不做了，配置里不再暴露相关字段
        assertTrue(!config.containsKey("webPushEnabled") && !config.containsKey("webPushPublicKey"));
    }

    @Test
    @DisplayName("管理端能查全量消息、按条件筛、删除与清理已读")
    void adminListAndCleanup() {
        String receiverA = "aa" + suffix;
        String receiverB = "bb" + suffix;
        noticeService.push(receiverA, NoticeMessage.of("TASK", "管理端待办" + suffix, "内容一",
                "WORK_ORDER", "WOA" + suffix));
        noticeService.push(receiverB, NoticeMessage.of("ALERT", "管理端预警" + suffix, "内容二",
                "DEFECT", "DFB" + suffix));

        PageResult onlyB = noticeService.adminList(null, receiverB, null, null, null, null, 1, 50);
        assertEquals(1, onlyB.records().size());
        Map<String, Object> row = (Map<String, Object>) onlyB.records().get(0);
        assertEquals("ALERT", row.get("category"));
        assertEquals("DFB" + suffix, row.get("refNo"));

        PageResult onlyTask = noticeService.adminList("管理端待办" + suffix, null, "TASK", null, null, null, 1, 50);
        assertEquals(1, onlyTask.total());

        PageResult unreadOnly = noticeService.adminList(null, receiverA, null, "UNREAD", null, null, 1, 50);
        assertEquals(1, unreadOnly.total());

        long noticeId = ((Number) ((Map<String, Object>) onlyTask.records().get(0)).get("id")).longValue();
        assertEquals(1, noticeService.delete(noticeId));
        assertEquals(0, noticeService.adminList("管理端待办" + suffix, null, null, null, null, null, 1, 50).total());

        // 已读之后才能被清理：30 天内不删，清空已读才删
        jdbc.update("update sys_notice set read_at = now() where receiver = ?", receiverB);
        assertEquals(0, noticeService.purgeRead(30));
        assertTrue(noticeService.purgeRead(0) >= 1);
        assertEquals(0, noticeService.adminList(null, receiverB, null, "READ", null, null, 1, 50).total());

        Map<String, Object> summary = noticeService.adminSummary();
        assertTrue(((Number) summary.get("total")).longValue() >= 0);
        assertTrue(summary.containsKey("unread") && summary.containsKey("task") && summary.containsKey("alert"));
    }

    @Test
    @DisplayName("桌面通知轮询只返回 since 之后的未读消息")
    void pendingReturnsOnlyNewUnread() {
        String receiver = "pnd" + suffix;
        noticeService.push(receiver, NoticeMessage.of("TASK", "第一条", "内容一", "WORK_ORDER", "WO1001"));
        noticeService.push(receiver, NoticeMessage.of("ALERT", "第二条", "内容二", "DEFECT", "DF1002"));

        List<Map<String, Object>> all = noticeService.pending(receiver, 0, 10);
        assertEquals(2, all.size());
        // 倒序返回，最新的在前
        assertTrue(Number.class.isAssignableFrom(all.get(0).get("id").getClass()));

        long firstId = ((Number) all.get(all.size() - 1).get("id")).longValue();
        List<Map<String, Object>> newer = noticeService.pending(receiver, firstId, 10);
        assertEquals(1, newer.size());
        assertEquals("DF1002", newer.get(0).get("refNo"));

        // 已读的不再推
        jdbc.update("update sys_notice set read_at = now() where receiver = ?", receiver);
        assertEquals(0, noticeService.pending(receiver, 0, 10).size());
    }

    @Test
    @DisplayName("安灯呼叫进计划与管理员的待办，带安灯号")
    void andonCallPushesTaskNotice() {
        long productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 0) returning id
                """, Long.class, "NP" + suffix, "安灯测试成品" + suffix);
        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'IN_PROGRESS') returning id
                """, Long.class, "WON" + suffix, productId);

        Map<String, Object> call = andonService.create(new AndonService.CallRequest(
                workOrderId, "装配", "EQUIPMENT", "折弯机异响" + suffix));
        String callNo = String.valueOf(call.get("callNo"));

        assertEquals(1, count("""
                select count(*) from sys_notice
                where receiver = ? and ref_type = 'ANDON' and ref_no = ? and category = 'TASK'
                """, planner, callNo));
        assertEquals(1, count("""
                select count(*) from sys_notice
                where receiver = ? and ref_type = 'ANDON' and ref_no = ? and category = 'TASK'
                """, admin, callNo));

        Map<String, Object> notice = jdbc.queryForMap("""
                select title, content from sys_notice where receiver = ? and ref_no = ?
                """, planner, callNo);
        assertTrue(String.valueOf(notice.get("title")).contains(callNo));
        assertTrue(String.valueOf(notice.get("content")).contains("折弯机异响"));
    }
}
