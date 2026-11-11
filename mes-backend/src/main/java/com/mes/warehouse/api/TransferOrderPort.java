package com.mes.warehouse.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 调拨单在审批场景下的对外契约。
 *
 * <p>审批流要展示调拨单摘要、允许审批人改备注与计划日期、把审批结果回写单据状态。
 * 这些都作用在 {@code transfer_order} 上，所以由仓储发布端口，审批侧不再直接 update。</p>
 *
 * <p>实现是薄的 {@code TransferOrderWriter}（只带 JdbcTemplate）：仓储本身依赖审批契约，
 * 审批再注入仓储的业务类会成构造环。</p>
 */
public interface TransferOrderPort {

    /** 审批页展示的调拨单摘要 */
    record TransferSummary(long id, String remark, LocalDate plannedDate, String fromWarehouse,
                           String toWarehouse, BigDecimal totalQty) {
    }

    /** 取摘要；单据不存在时抛业务异常 */
    TransferSummary summary(long transferOrderId);

    /** 改备注 */
    void updateRemark(long transferOrderId, String remark);

    /** 改计划日期，null 表示清空 */
    void updatePlannedDate(long transferOrderId, LocalDate plannedDate);

    /** 回写审批结果对应的单据状态（APPROVED / REJECTED / DRAFT） */
    void updateStatus(long transferOrderId, String status);
}
