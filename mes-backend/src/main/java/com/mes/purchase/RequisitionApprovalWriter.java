package com.mes.purchase;

import com.mes.purchase.api.RequisitionApprovalPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 审批结果回写采购申请状态。
 *
 * <p>只碰 purchase_requisition 与 purchase_requisition_item 两张表，
 * 所以可以被审批模块直接依赖而不成环。</p>
 *
 * <p>行级分流时一个审批实例覆盖一组明细行：通过就把这几行标成已通过，
 * 然后回头看整单是否已经全部通过；只要有一行被驳回，整单退回，
 * 免得「一半行批了、一半行还在审」的状态被人误当整单通过去转采购单。</p>
 */
@Component
public class RequisitionApprovalWriter implements RequisitionApprovalPort {

    private final JdbcTemplate jdbcTemplate;

    public RequisitionApprovalWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void applyApproval(long reqId, String lineIds, String outcome) {
        String result = outcome == null ? "" : outcome.trim().toUpperCase();
        List<Long> lines = parseIds(lineIds);
        if (lines.isEmpty()) {
            applyHeader(reqId, result);
            return;
        }
        if ("APPROVED".equals(result)) {
            updateLines(reqId, lines, "APPROVED");
            recomputeHeader(reqId);
        } else if ("REJECTED".equals(result)) {
            updateLines(reqId, lines, "REJECTED");
            jdbcTemplate.update("""
                    update purchase_requisition
                       set status = 'REJECTED', approved_at = now(),
                           reject_reason = coalesce(reject_reason, '审批流驳回，详见审批中心')
                     where id = ? and status = 'SUBMITTED'
                    """, reqId);
        } else {
            // 撤回：整单退回草稿，行状态重置，下次提交重新分流
            jdbcTemplate.update("""
                    update purchase_requisition_item set approval_status = 'PENDING',
                           approval_instance_id = null, flow_code = null
                     where req_id = ?
                    """, reqId);
            jdbcTemplate.update("""
                    update purchase_requisition
                       set status = 'DRAFT', approval_instance_id = null
                     where id = ? and status = 'SUBMITTED'
                    """, reqId);
        }
    }

    /** 整单级审批（存量单或未开行级分流的业务） */
    private void applyHeader(long reqId, String result) {
        if ("APPROVED".equals(result)) {
            jdbcTemplate.update("""
                    update purchase_requisition
                       set status = 'APPROVED', approved_by = '审批流', approved_at = now(),
                           reject_reason = null
                     where id = ? and status = 'SUBMITTED'
                    """, reqId);
        } else if ("REJECTED".equals(result)) {
            jdbcTemplate.update("""
                    update purchase_requisition
                       set status = 'REJECTED', approved_at = now(),
                           reject_reason = coalesce(reject_reason, '审批流驳回，详见审批中心')
                     where id = ? and status = 'SUBMITTED'
                    """, reqId);
        } else {
            jdbcTemplate.update("""
                    update purchase_requisition
                       set status = 'DRAFT', approval_instance_id = null
                     where id = ? and status = 'SUBMITTED'
                    """, reqId);
        }
    }

    private void updateLines(long reqId, List<Long> lines, String status) {
        String placeholders = String.join(",", Collections.nCopies(lines.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.add(reqId);
        args.addAll(lines);
        jdbcTemplate.update(
                "update purchase_requisition_item set approval_status = ? where req_id = ? and id in ("
                        + placeholders + ")", args.toArray());
    }

    /**
     * 全部行都过了（含未命中链的 SKIPPED）才把单据推进；
     * 审批期间可能已经把某些行转过采购单，所以推进时要看转单进度，不能一律写"已批准"。
     */
    private void recomputeHeader(long reqId) {
        Integer pending = jdbcTemplate.queryForObject("""
                select count(*) from purchase_requisition_item
                where req_id = ? and approval_status = 'PENDING'
                """, Integer.class, reqId);
        Integer rejected = jdbcTemplate.queryForObject("""
                select count(*) from purchase_requisition_item
                where req_id = ? and approval_status = 'REJECTED'
                """, Integer.class, reqId);
        if (rejected != null && rejected > 0) {
            return;
        }
        if (pending != null && pending > 0) {
            return;
        }
        jdbcTemplate.update("""
                update purchase_requisition r
                set status = case when s.converted >= s.approved then 'CONVERTED'
                                  when s.converted > 0 then 'PARTIAL'
                                  else 'APPROVED' end,
                    approved_by = '审批流', approved_at = now(), reject_reason = null
                from (select coalesce(sum(qty) filter (where approval_status in ('APPROVED', 'SKIPPED')), 0) as approved,
                             coalesce(sum(converted_qty), 0) as converted
                      from purchase_requisition_item where req_id = ?) s
                where r.id = ? and r.status = 'SUBMITTED'
                """, reqId, reqId);
    }

    private List<Long> parseIds(String lineIds) {
        List<Long> result = new ArrayList<>();
        if (lineIds == null || lineIds.isBlank()) {
            return result;
        }
        for (String part : lineIds.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                result.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // 脏值忽略，不影响其他行回写
            }
        }
        return result;
    }
}
