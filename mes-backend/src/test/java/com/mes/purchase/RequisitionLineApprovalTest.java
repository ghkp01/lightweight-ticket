package com.mes.purchase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 行级回写：一组行审完要把行标通过，只有全部行都过了才推进整单。
 *
 * <p>这里只测回写这一段（不启动 Flowable）：行级分流最容易出错的地方是
 * 「一半行批了就把整单放行」，那会让人拿着没批的行去转采购单。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RequisitionLineApprovalTest {

    @Autowired
    private RequisitionApprovalWriter writer;

    @Autowired
    private RequisitionService requisitionService;

    @Autowired
    private JdbcTemplate jdbc;

    private long reqId;
    private long line1;
    private long line2;
    private long line3;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        reqId = jdbc.queryForObject("""
                insert into purchase_requisition (req_no, source_type, requester, department, need_date,
                                                  purpose, status, total_amount, category)
                values (?, 'MANUAL', 'admin', '设备部', current_date, '行级回写测试', 'SUBMITTED', 1000, 'MATERIAL')
                returning id
                """, Long.class, "PRL" + suffix);
        line1 = line("OUTSOURCE", "PENDING");
        line2 = line("OUTSOURCE", "PENDING");
        line3 = line("MATERIAL", "PENDING");
    }

    private long line(String category, String status) {
        Long productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', 'kg', 10) returning id
                """, Long.class, "LP" + System.nanoTime(), "行级测试料");
        return jdbc.queryForObject("""
                insert into purchase_requisition_item (req_id, product_id, qty, target_price, category,
                                                       approval_status)
                values (?, ?, 2, 100, ?, ?) returning id
                """, Long.class, reqId, productId, category, status);
    }

    private String headerStatus() {
        return jdbc.queryForObject("select status from purchase_requisition where id = ?", String.class, reqId);
    }

    private String lineStatus(long id) {
        return jdbc.queryForObject(
                "select approval_status from purchase_requisition_item where id = ?", String.class, id);
    }

    @Test
    @DisplayName("一组行通过后整单还停在待审批，全部行过了才推进到已批准")
    void headerAdvancesOnlyWhenAllLinesPass() {
        writer.applyApproval(reqId, line1 + "," + line2, "APPROVED");

        assertEquals("APPROVED", lineStatus(line1));
        assertEquals("APPROVED", lineStatus(line2));
        assertEquals("PENDING", lineStatus(line3));
        assertEquals("SUBMITTED", headerStatus(), "还有行没批，整单不能放行");

        writer.applyApproval(reqId, String.valueOf(line3), "APPROVED");
        assertEquals("APPROVED", headerStatus());
    }

    @Test
    @DisplayName("任何一行被驳回，整单退回，不会因为其他行批了就放行")
    void anyRejectedLineRejectsHeader() {
        writer.applyApproval(reqId, line1 + "," + line2, "APPROVED");
        writer.applyApproval(reqId, String.valueOf(line3), "REJECTED");

        assertEquals("REJECTED", lineStatus(line3));
        assertEquals("REJECTED", headerStatus());
    }

    @Test
    @DisplayName("未命中链的行按 SKIPPED 处理，不挡住整单推进")
    void skippedLinesDoNotBlock() {
        jdbc.update("update purchase_requisition_item set approval_status = 'SKIPPED' where id = ?", line3);
        writer.applyApproval(reqId, line1 + "," + line2, "APPROVED");

        assertEquals("APPROVED", headerStatus());
    }

    @Test
    @DisplayName("阈值审批通过时把行状态一起写上，否则这些行转不了采购单")
    void thresholdApproveMarksLinesApproved() {
        requisitionService.approve(reqId);

        assertEquals("APPROVED", headerStatus());
        assertEquals("APPROVED", lineStatus(line1));
        assertEquals("APPROVED", lineStatus(line3));
    }

    @Test
    @DisplayName("整单级审批（没有行 id）仍然按原来那套走")
    void headerLevelStillWorks() {
        jdbc.update("update purchase_requisition_item set approval_status = 'SKIPPED' where req_id = ?", reqId);
        writer.applyApproval(reqId, null, "APPROVED");

        assertEquals("APPROVED", headerStatus());
        Map<String, Object> row = jdbc.queryForMap(
                "select approved_by from purchase_requisition where id = ?", reqId);
        assertEquals("审批流", row.get("approved_by"));
    }
}
