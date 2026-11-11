package com.mes.quality.api;

import java.math.BigDecimal;

/**
 * 写一条不良记录的请求。
 *
 * <p>原来这里有 7~8 个位置参数（`null, null, null, productId, qty, "RETURN", reason, false`），
 * 谁在第几位全靠人记，传错了编译期也发现不了。改成 record 之后字段名就是文档。</p>
 *
 * @param workOrderId 关联工单，报工不良与完工检不合格有值
 * @param operationId 关联工序实例
 * @param processId   关联工序主数据
 * @param productId   不良产品
 * @param qty         不良数量
 * @param source      REPORT 报工不良 / FINAL_QC 完工检不合格 / INCOMING 来料不合格 / RETURN 退货报废
 * @param reason      原因描述
 * @param pushNotice   是否推待处置提醒；false 用于「写入即已处置」的场景（如退货报废）
 */
public record DefectRequest(Long workOrderId, Long operationId, Long processId, Long productId,
                            BigDecimal qty, String source, String reason, boolean pushNotice) {

    /** 默认需要推待处置提醒 */
    public static DefectRequest of(Long workOrderId, Long operationId, Long processId, Long productId,
                                   BigDecimal qty, String source, String reason) {
        return new DefectRequest(workOrderId, operationId, processId, productId, qty, source, reason, true);
    }

    /** 写入即已处置，不推提醒 */
    public static DefectRequest disposed(Long productId, BigDecimal qty, String source, String reason) {
        return new DefectRequest(null, null, null, productId, qty, source, reason, false);
    }
}
