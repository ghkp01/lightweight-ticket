package com.mes.quality;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 抽样方案：全检，或按批量分档抽检。
 * 抽检采用一条可解释的简化规则（不冒充 GB/T 2828.1 全表）：
 * 样本量按批量区间分档，接收数 Ac = floor(样本量 × AQL%)，拒收数 Re = Ac + 1。
 * 需要严格按国标执行时，把 aql 与企业抽样方案对齐即可。
 */
@Service
public class SamplingService {

    public record SamplePlan(String plan, BigDecimal sampleQty, BigDecimal aql, int acceptQty, int rejectQty) {
    }

    /** {批量上限, 样本量}：一般检验水平 II 的常用档位 */
    private static final int[][] LOT_BANDS = {
            {8, 2}, {15, 3}, {25, 5}, {50, 8}, {90, 13}, {150, 20}, {280, 32},
            {500, 50}, {1200, 80}, {3200, 125}, {10000, 200}, {35000, 315},
            {150000, 500}, {Integer.MAX_VALUE, 800}
    };

    private static final BigDecimal DEFAULT_AQL = new BigDecimal("2.5");

    public SamplePlan plan(BigDecimal lotQty, String plan, BigDecimal aql) {
        BigDecimal lot = lotQty == null || lotQty.signum() <= 0 ? BigDecimal.ZERO : lotQty;
        String mode = plan == null || plan.isBlank() ? "FULL" : plan.trim().toUpperCase();
        if (!"SAMPLE".equals(mode)) {
            return new SamplePlan("FULL", lot, null, 0, 1);
        }
        BigDecimal level = aql == null || aql.signum() <= 0 ? DEFAULT_AQL : aql;
        int sample = 0;
        for (int[] band : LOT_BANDS) {
            if (lot.compareTo(BigDecimal.valueOf(band[0])) <= 0) {
                sample = band[1];
                break;
            }
        }
        if (sample == 0) {
            sample = 800;
        }
        int lotInt = lot.setScale(0, RoundingMode.DOWN).intValue();
        BigDecimal sampleQty = BigDecimal.valueOf(Math.min(sample, Math.max(1, lotInt)));
        int accept = sampleQty.multiply(level)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                .intValue();
        return new SamplePlan("SAMPLE", sampleQty, level, accept, accept + 1);
    }
}
