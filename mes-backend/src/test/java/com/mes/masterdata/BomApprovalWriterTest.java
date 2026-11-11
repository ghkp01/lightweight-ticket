package com.mes.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BOM 审批回调：通过后自动启用并停用旧版本，驳回退回草稿。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BomApprovalWriterTest {

    @Autowired
    private BomApprovalWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    private long product(String tag) {
        return jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 0) returning id
                """, Long.class, "BAP" + tag, "审批回调测试" + tag);
    }

    private long bom(String bomNo, long productId, String version, String status) {
        return jdbc.queryForObject("""
                insert into bom (bom_no, product_id, version, status, base_qty, effective_from)
                values (?, ?, ?, ?, 1, current_date) returning id
                """, Long.class, bomNo, productId, version, status);
    }

    private String statusOf(long bomId) {
        return jdbc.queryForObject("select status from bom where id = ?", String.class, bomId);
    }

    @Test
    @DisplayName("审批通过：新版本生效，旧版本自动停用")
    void approveActivatesNewVersion() {
        String tag = String.valueOf(System.nanoTime());
        long productId = product(tag);
        long oldBom = bom("BAP-OLD-" + tag, productId, "V1.0", "ACTIVE");
        long newBom = bom("BAP-NEW-" + tag, productId, "V2.0", "PENDING");

        writer.applyApproval(newBom, "APPROVED");

        assertEquals("INACTIVE", statusOf(oldBom));
        assertEquals("ACTIVE", statusOf(newBom));
    }

    @Test
    @DisplayName("审批驳回：版本退回草稿，原生效版本不受影响")
    void rejectReturnsDraft() {
        String tag = String.valueOf(System.nanoTime());
        long productId = product(tag);
        long activeBom = bom("BAP-ACT-" + tag, productId, "V1.0", "ACTIVE");
        long pendingBom = bom("BAP-REJ-" + tag, productId, "V2.0", "PENDING");

        writer.applyApproval(pendingBom, "REJECTED");

        assertEquals("ACTIVE", statusOf(activeBom));
        assertEquals("DRAFT", statusOf(pendingBom));
    }
}
