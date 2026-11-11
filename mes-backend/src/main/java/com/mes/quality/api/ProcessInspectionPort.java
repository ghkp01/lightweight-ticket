package com.mes.quality.api;

import java.math.BigDecimal;

/**
 * 工序报工附带检验的写入契约。
 *
 * <p>报工时如果这道工序配了「随工检验」，生产要落一条检验记录。
 * 检验单归质量所有，单号规则也在质量侧，所以生产不直接 insert，而是发这条命令。</p>
 *
 * <p>实现是薄的 {@code ProcessInspectionWriter}（只带 JdbcTemplate 与单号生成器）：
 * 质量本身依赖生产契约，生产再注入质量的业务类会成构造环。</p>
 */
public interface ProcessInspectionPort {

    /** 一道工序的一次随工检验 */
    record ProcessInspection(String refNo, long workOrderId, long productId, BigDecimal inspectQty,
                             BigDecimal defectQty, String result, String inspector, String remark) {
    }

    /** 落检验单，返回检验单号 */
    String record(ProcessInspection inspection);
}
