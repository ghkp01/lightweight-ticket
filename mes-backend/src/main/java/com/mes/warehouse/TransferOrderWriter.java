package com.mes.warehouse;

import com.mes.common.BizException;
import com.mes.warehouse.api.TransferOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 调拨单审批相关写入的薄实现：只碰 {@code transfer_order} / {@code transfer_order_item}，
 * 仓库名称走 {@code rm_warehouse} 读模型视图。
 *
 * <p>刻意不复用 {@code TransferOrderService}：那边依赖审批契约（提交审批），
 * 审批回头再注入它就成构造环。</p>
 */
@Component
public class TransferOrderWriter implements TransferOrderPort {

    private final JdbcTemplate jdbcTemplate;

    public TransferOrderWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TransferSummary summary(long transferOrderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id, o.remark, o.planned_date,
                       fw.name as "fromWarehouse", tw.name as "toWarehouse",
                       (select coalesce(sum(i.qty), 0) from transfer_order_item i
                         where i.transfer_id = o.id) as "totalQty"
                from transfer_order o
                join rm_warehouse fw on fw.id = o.from_warehouse_id
                join rm_warehouse tw on tw.id = o.to_warehouse_id
                where o.id = ?
                """, transferOrderId);
        if (rows.isEmpty()) {
            throw new BizException(4047, "单据不存在");
        }
        Map<String, Object> row = rows.get(0);
        return new TransferSummary(transferOrderId,
                row.get("remark") == null ? null : String.valueOf(row.get("remark")),
                row.get("planned_date") == null ? null : ((Date) row.get("planned_date")).toLocalDate(),
                row.get("fromWarehouse") == null ? null : String.valueOf(row.get("fromWarehouse")),
                row.get("toWarehouse") == null ? null : String.valueOf(row.get("toWarehouse")),
                row.get("totalQty") == null ? BigDecimal.ZERO
                        : new BigDecimal(String.valueOf(row.get("totalQty"))));
    }

    @Override
    public void updateRemark(long transferOrderId, String remark) {
        jdbcTemplate.update("update transfer_order set remark = ? where id = ?", remark, transferOrderId);
    }

    @Override
    public void updatePlannedDate(long transferOrderId, LocalDate plannedDate) {
        jdbcTemplate.update("update transfer_order set planned_date = ? where id = ?",
                plannedDate == null ? null : Date.valueOf(plannedDate), transferOrderId);
    }

    @Override
    public void updateStatus(long transferOrderId, String status) {
        jdbcTemplate.update("update transfer_order set status = ? where id = ?", status, transferOrderId);
    }
}
