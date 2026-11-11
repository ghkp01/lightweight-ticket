package com.mes.cost;

import com.mes.cost.api.PerformanceApprovalPort;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 绩效单审批回写的薄实现：只碰 {@code performance_sheet}，不带任何依赖。
 *
 * <p>刻意不复用 {@link PerformanceService}：那个类要提交审批（依赖 ApprovalPort），
 * 审批回头再注入它就成构造环。</p>
 */
@Component
public class PerformanceApprovalWriter implements PerformanceApprovalPort {

    private final JdbcTemplate jdbcTemplate;

    public PerformanceApprovalWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void applyApproval(long sheetId, String outcome) {
        String status = switch (outcome == null ? "" : outcome.toUpperCase()) {
            case "APPROVED" -> "APPROVED";
            case "REJECTED" -> "REJECTED";
            default -> "DRAFT";
        };
        if ("APPROVED".equals(status)) {
            jdbcTemplate.update("""
                    update performance_sheet
                    set status = 'APPROVED', approved_by = ?, approved_at = now(),
                        updated_at = now(), version = version + 1
                    where id = ?
                    """, approverName(), sheetId);
            return;
        }
        if ("REJECTED".equals(status)) {
            jdbcTemplate.update("""
                    update performance_sheet
                    set status = 'REJECTED', reject_reason = coalesce(reject_reason, '审批驳回'),
                        approved_by = ?, updated_at = now(), version = version + 1
                    where id = ?
                    """, approverName(), sheetId);
            return;
        }
        // 撤回：回到草稿，让填报人改完再提交
        jdbcTemplate.update("""
                update performance_sheet
                set status = 'DRAFT', submitted_at = null, approved_by = null, approved_at = null,
                    updated_at = now(), version = version + 1
                where id = ?
                """, sheetId);
    }

    /** 审批人可能来自系统自动通过，取不到登录人时记成 system 而不是空 */
    private static String approverName() {
        try {
            String name = CurrentUser.name();
            return name == null || name.isBlank() ? "system" : name;
        } catch (RuntimeException error) {
            return "system";
        }
    }
}
