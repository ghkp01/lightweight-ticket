package com.mes.masterdata;

import com.mes.approval.ApprovalService;
import com.mes.common.BizException;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOM 版本审批状态机：新建只能草稿、提交进审批、撤回回草稿、非草稿不可改。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BomServiceApprovalTest {

    @Autowired
    private BomService service;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private JdbcTemplate jdbc;

    private void login(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private long product(String code, String category) {
        return jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, ?, '件', 1) returning id
                """, Long.class, code, "BOM审批-" + code, category);
    }

    private String statusOf(long bomId) {
        return jdbc.queryForObject("select status from bom where id = ?", String.class, bomId);
    }

    @Test
    @DisplayName("提交审批进入 PENDING，撤回回到草稿")
    void submitAndWithdraw() {
        String tag = String.valueOf(System.nanoTime());
        long parentId = product("BAP-P-" + tag, "FINISHED");
        long materialId = product("BAP-M-" + tag, "RAW");
        // 即使创建时传 ACTIVE，也强制从草稿开始
        long bomId = service.create(parentId, new BomService.BomHeaderRequest("V1.0", BigDecimal.ONE,
                LocalDate.now(), null, "ACTIVE", "测试"));
        assertEquals("DRAFT", statusOf(bomId));
        service.saveLines(bomId, List.of(new BomService.BomLineRequest(materialId,
                BigDecimal.ONE, 1, null, false, null, null)));

        Map<String, Object> result = service.submit(bomId);

        assertFalse(Boolean.TRUE.equals(result.get("approved")));
        assertNotNull(result.get("instanceId"));
        assertEquals("PENDING", statusOf(bomId));
        assertNotNull(jdbc.queryForObject(
                "select approval_instance_id from bom where id = ?", Long.class, bomId));
        // 审批中的版本只读
        assertThrows(BizException.class, () -> service.saveLines(bomId, List.of()));

        service.withdraw(bomId);

        assertEquals("DRAFT", statusOf(bomId));
        assertNull(jdbc.queryForObject(
                "select approval_instance_id from bom where id = ?", Long.class, bomId));
        assertTrue(service.copyVersion(bomId, "V2.0") > 0);
    }

    @Test
    @DisplayName("审批通过后版本自动生效")
    void approveMakesVersionActive() {
        String tag = String.valueOf(System.nanoTime());
        long parentId = product("BAP-AP-" + tag, "FINISHED");
        long materialId = product("BAP-AM-" + tag, "RAW");
        long bomId = service.create(parentId, new BomService.BomHeaderRequest("V1.0", BigDecimal.ONE,
                LocalDate.now(), null, "DRAFT", "测试"));
        service.saveLines(bomId, List.of(new BomService.BomLineRequest(materialId,
                BigDecimal.ONE, 1, null, false, null, null)));
        service.submit(bomId);
        String bomNo = jdbc.queryForObject("select bom_no from bom where id = ?", String.class, bomId);

        login("admin", "ADMIN");
        Map<String, Object> task = approvalService.myTasks().stream()
                .filter(row -> "BOM".equals(row.get("bizType")) && bomNo.equals(row.get("bizNo")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("审批待办里没有 BOM 任务"));
        approvalService.approve(String.valueOf(task.get("id")), "同意");

        assertEquals("ACTIVE", statusOf(bomId));
        assertNull(jdbc.queryForObject(
                "select approval_instance_id from bom where id = ?", Long.class, bomId));
    }
}
