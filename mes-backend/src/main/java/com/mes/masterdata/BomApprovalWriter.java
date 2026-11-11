package com.mes.masterdata;

import com.mes.masterdata.api.BomApprovalPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * BOM 审批结果回写：只依赖 JdbcTemplate，避免审批服务与 BOM 服务构造期互相依赖。
 */
@Service
public class BomApprovalWriter implements BomApprovalPort {

    private final JdbcTemplate jdbcTemplate;

    public BomApprovalWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void applyApproval(long bomId, String outcome) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, product_id from bom where id = ?", bomId);
        if (rows.isEmpty()) {
            return;
        }
        long productId = ((Number) rows.get(0).get("product_id")).longValue();
        if ("APPROVED".equalsIgnoreCase(outcome)) {
            // 同一物料只能有一套生效 BOM：先降级旧的，再启用审批通过的版本
            jdbcTemplate.update("""
                    update bom set status = 'INACTIVE', updated_at = now()
                    where product_id = ? and status = 'ACTIVE' and id <> ?
                    """, productId, bomId);
            jdbcTemplate.update("""
                    update bom set status = 'ACTIVE', approval_instance_id = null, updated_at = now()
                    where id = ?
                    """, bomId);
            return;
        }
        // 驳回或撤回：退回草稿，计划改完可以重新提交
        jdbcTemplate.update("""
                update bom set status = 'DRAFT', approval_instance_id = null, updated_at = now()
                where id = ?
                """, bomId);
    }
}
