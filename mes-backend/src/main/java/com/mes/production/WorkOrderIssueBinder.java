package com.mes.production;

import com.mes.inventory.api.BatchMove;
import com.mes.production.api.WorkOrderIssuePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领料批次绑定的薄实现：只碰 {@code work_order} 与 {@code work_order_material_batch}。
 *
 * <p>刻意不复用 {@link ProductionService}：那边依赖库存契约（扣减、结存、发货），
 * 而库存这边要在扣减完成后回头绑定批次，直接互相注入会成构造环。
 * 这里只带 JdbcTemplate，谁都不用。</p>
 */
@Component
public class WorkOrderIssueBinder implements WorkOrderIssuePort {

    private final JdbcTemplate jdbcTemplate;

    public WorkOrderIssueBinder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Long idByWorkNo(String workNo) {
        if (workNo == null || workNo.isBlank()) {
            return null;
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from work_order where work_no = ?", Long.class, workNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    public Map<Long, String> workNos(Collection<Long> workOrderIds) {
        Map<Long, String> map = new LinkedHashMap<>();
        List<Long> ids = workOrderIds == null ? List.of()
                : workOrderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, work_no from work_order where id in (" + marks + ")", ids.toArray())) {
            map.put(((Number) row.get("id")).longValue(), String.valueOf(row.get("work_no")));
        }
        return map;
    }

    @Override
    public void bindIssuedBatches(String workNo, long productId, Collection<BatchMove> moves) {
        if (moves == null || moves.isEmpty()) {
            return;
        }
        Long workOrderId = idByWorkNo(workNo);
        if (workOrderId == null) {
            return;
        }
        for (BatchMove move : moves) {
            jdbcTemplate.update("""
                    insert into work_order_material_batch (work_order_id, product_id, batch_no, qty, issue_ref)
                    values (?, ?, ?, ?, ?)
                    """, workOrderId, productId, move.batchNo(), move.qty(), workNo);
        }
    }
}
