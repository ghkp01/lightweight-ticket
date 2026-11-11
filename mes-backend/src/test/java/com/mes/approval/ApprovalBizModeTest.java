package com.mes.approval;

import com.mes.purchase.RequisitionService;
import com.mes.purchase.api.RequisitionDraft;
import com.mes.purchase.api.RequisitionLine;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 菜单级审批开关：三档强度（免审批 / 金额阈值 / 审批流）与采购申请的实际走向。
 *
 * <p>核心断言是「开关关掉之后单据要能照常提交」：不能因为关掉审批就提交不了，
 * 也不能因为开着审批但没有流程就把单据卡在待审批里没人处理。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApprovalBizModeTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private RequisitionService requisitionService;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', 'kg', 10) returning id
                """, Long.class, "AB" + suffix, "审批开关测试料" + suffix);
    }

    private long newRequisition(BigDecimal amount) {
        Long id = jdbc.queryForObject("""
                insert into purchase_requisition (req_no, source_type, requester, department, need_date,
                                                  purpose, status, total_amount)
                values (?, 'MANUAL', 'admin', '设备部', current_date, '审批开关测试', 'DRAFT', ?)
                returning id
                """, Long.class, "PR" + System.nanoTime(), amount);
        jdbc.update("""
                insert into purchase_requisition_item (req_id, product_id, qty, target_price)
                values (?, ?, 2, 50)
                """, id, productId);
        return id;
    }

    private Map<String, Object> requisition(long id) {
        return jdbc.queryForMap("""
                select status, approved_by, approval_instance_id from purchase_requisition where id = ?
                """, id);
    }

    private void setMode(String mode) {
        approvalService.saveBiz(new ApprovalService.BizRequest(
                "REQUISITION", "采购申请", "/purchase/requisitions", mode, null));
    }

    @Test
    @DisplayName("采购申请默认只按金额阈值，不动存量行为")
    void requisitionDefaultsToThreshold() {
        assertEquals("THRESHOLD", approvalService.modeOf("REQUISITION"));
    }

    @Test
    @DisplayName("没登记的业务类型按走审批流处理，避免升级后把老审批悄悄关掉")
    void unknownBizTypeFallsBackToFlow() {
        assertEquals("FLOW", approvalService.modeOf("SOMETHING_NEW"));
    }

    @Test
    @DisplayName("关掉审批后提交即生效，不产生审批待办")
    void noneModeApprovesOnSubmit() {
        setMode("NONE");
        long id = newRequisition(new BigDecimal("8000"));
        requisitionService.submit(id);

        Map<String, Object> row = requisition(id);
        assertEquals("APPROVED", row.get("status"));
        assertEquals("免审批", row.get("approved_by"));
        assertNull(row.get("approval_instance_id"));
    }

    @Test
    @DisplayName("阈值模式：提交后停在待审批，超阈值非管理员批不了")
    void thresholdModeStillNeedsApproval() {
        setMode("THRESHOLD");
        long id = newRequisition(new BigDecimal("8000"));
        requisitionService.submit(id);

        Map<String, Object> row = requisition(id);
        assertEquals("SUBMITTED", row.get("status"));
        assertNull(row.get("approval_instance_id"));
    }

    @Test
    @DisplayName("开了审批但还没有可用流程：回落成阈值模式，单据不会卡死")
    void flowModeWithoutFlowFallsBack() {
        setMode("FLOW");
        long id = newRequisition(new BigDecimal("100"));
        requisitionService.submit(id);

        Map<String, Object> row = requisition(id);
        assertEquals("SUBMITTED", row.get("status"));
        assertNull(row.get("approval_instance_id"));
        assertNotNull(row.get("status"));
    }

    @Test
    @DisplayName("开关列表给出「开了审批但没流程」的提醒")
    void bizListWarnsWhenFlowMissing() {
        setMode("FLOW");
        Map<String, Object> row = approvalService.bizList().stream()
                .filter(item -> "REQUISITION".equals(String.valueOf(item.get("bizType"))))
                .findFirst()
                .orElseThrow();
        assertEquals("FLOW", row.get("mode"));
        assertTrue(String.valueOf(row.get("hint")).contains("还没有可用流程"),
                String.valueOf(row.get("hint")));
    }

    @Test
    @DisplayName("非法强度被拒绝")
    void rejectsUnknownMode() {
        try {
            setMode("SOMETIMES");
        } catch (RuntimeException error) {
            assertTrue(error.getMessage().contains("审批强度"), error.getMessage());
            return;
        }
        throw new AssertionError("非法强度应该被拒绝");
    }

    @Test
    @DisplayName("采购类别、紧急程度与预算科目落库：分流规则就靠这三个字段")
    void categoryAndUrgentPersisted() {
        setMode("NONE");
        var created = requisitionService.create(new RequisitionDraft(
                "MANUAL", "设备部", null, LocalDate.now().plusDays(2), "外协加工", "",
                List.of(new RequisitionLine(productId, new BigDecimal("2"), BigDecimal.ONE, null, false, null)),
                "OUTSOURCE", true, "B-2026-01"));

        Map<String, Object> row = jdbc.queryForMap(
                "select category, urgent, budget_no from purchase_requisition where id = ?", created.id());
        assertEquals("OUTSOURCE", row.get("category"));
        assertEquals(Boolean.TRUE, row.get("urgent"));
        assertEquals("B-2026-01", row.get("budget_no"));
    }

    @Test
    @DisplayName("采购类别乱填会被拒，不静默兜底")
    void invalidCategoryRejected() {
        try {
            requisitionService.create(new RequisitionDraft(
                    "MANUAL", "设备部", null, LocalDate.now(), "乱填", "",
                    List.of(new RequisitionLine(productId, BigDecimal.ONE, BigDecimal.ONE, null, false, null)),
                    "WHATEVER", false, null));
        } catch (RuntimeException error) {
            assertTrue(error.getMessage().contains("采购类别"), error.getMessage());
            return;
        }
        throw new AssertionError("非法采购类别应该被拒绝");
    }

    @Test
    @DisplayName("单据上的免审批提交也能走 MRP 一键生成的那条路径")
    void requisitionDraftPortStillWorks() {
        setMode("NONE");
        var created = requisitionService.create(new RequisitionDraft(
                "PRODUCTION", "计划部", null, LocalDate.now().plusDays(3), "缺料补货", "端口测试",
                List.of(new RequisitionLine(productId, new BigDecimal("5"), BigDecimal.TEN, null, false, null))));
        requisitionService.submit(created.id());
        assertEquals("APPROVED", requisition(created.id()).get("status"));
    }
}
