package com.mes.production.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 建返修工单的请求。
 *
 * <p>返修单必须补齐工序与 BOM 快照，否则既排不了产也领不了料；这些约束留在生产模块内部，
 * 调用方只需要描述「哪个产品、返多少、为什么返、从第几道工序起」。</p>
 *
 * @param productId         返修产品
 * @param qty               返修数量
 * @param parentWorkOrderId 原工单，完工检/报工不良产生返修时有值
 * @param salesOrderId      销售订单，退货返修时有值
 * @param salesOrderItemId  销售订单行
 * @param planEnd           返修计划完工日，可空
 * @param reason            返修原因
 * @param fromSeq           返修起点工序序号；空表示整件从第一道返修
 */
public record ReworkRequest(long productId, BigDecimal qty, Long parentWorkOrderId,
                            Long salesOrderId, Long salesOrderItemId, LocalDate planEnd,
                            String reason, Integer fromSeq) {

    /** 只关心产品、数量与原因的最小构造（退货返修这类不挂原工单的场景） */
    public static ReworkRequest of(long productId, BigDecimal qty, String reason) {
        return new ReworkRequest(productId, qty, null, null, null, null, reason, null);
    }
}
