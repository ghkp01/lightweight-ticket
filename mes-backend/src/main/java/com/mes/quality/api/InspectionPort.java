package com.mes.quality.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 来料检验的对外契约。
 *
 * <p>供应商绩效要按「来料合格率」打分，本质是把检验单挂到供应商身上；
 * 但检验单只知道入库单，供应商是入库单上的字段。所以这里只按时间范围给原始检验行，
 * 由采购自己用入库单把供应商接起来 —— 质检不需要认识供应商。</p>
 */
public interface InspectionPort {

    /** 来料检验记录。日期与展示用的时间串都由质检侧统一格式，调用方直接透传 */
    record IncomingInspection(long id, String inspectionNo, Long inboundOrderId, long productId,
                              BigDecimal inspectQty, BigDecimal defectQty, String result,
                              LocalDate inspectedDate, String inspectedAt) {
    }

    /** 指定日期范围内（按检验日期）的全部来料检验，没有数据返回空列表 */
    List<IncomingInspection> incoming(LocalDate from, LocalDate to);
}
