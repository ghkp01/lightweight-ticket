package com.mes.device.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 设备综合效率（OEE）的对外契约。
 *
 * <p>车间大屏要有一屏看设备效率，而 OEE 的算法属于设备模块（可用率算停机、性能算标准工时、
 * 质量算合格率）。大屏只做展示，所以这里只给「算好的结果」，不暴露设备、故障、点检这些表。</p>
 *
 * <p>口径限制照旧：系统没有采集设备实时开动信号，性能项用「标准工时 ÷ 计划开动时间」近似，
 * 用来发现「停得多、做得慢、返工多」三类损失，不做秒级节拍分析。</p>
 */
public interface OeePort {

    /** 单个工作中心的 OEE 结果（百分比都是 0-100 的数） */
    record CenterOee(long id, String code, String name, String type,
                     BigDecimal plannedMinutes, BigDecimal downtimeMinutes,
                     BigDecimal availability, BigDecimal performance, BigDecimal quality,
                     BigDecimal oee, BigDecimal reportQty) {
    }

    /** 一次查询的整体结果：总数 + 分中心明细 */
    record OeeBoard(List<CenterOee> centers, BigDecimal availability, BigDecimal performance,
                    BigDecimal quality, BigDecimal oee, BigDecimal reportQty, int days) {
    }

    /**
     * 截至今天的近 N 天 OEE（含今天）；N 会被夹在 1~31。
     * 大屏传 1 就是「今日」，传 7 就是「近 7 天」。
     */
    OeeBoard recent(int days);
}
