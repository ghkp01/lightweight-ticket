package com.mes.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 绩效系数规则：效率与良率的分段、以及「没录工时就不奖惩」这条底线。
 *
 * <p>这些规则直接决定工资，所以逐段钉住；改分段时这里必须先改。</p>
 */
class PerformanceRuleTest {

    @Test
    @DisplayName("效率：标准工时 ÷ 实际工时，没录工时返回空而不是猜一个数")
    void efficiencyNeedsActualMinutes() {
        assertNull(PerformanceService.efficiency(new BigDecimal("4800"), null));
        assertNull(PerformanceService.efficiency(new BigDecimal("4800"), BigDecimal.ZERO));
        assertEquals(new BigDecimal("1.0000"),
                PerformanceService.efficiency(new BigDecimal("4800"), new BigDecimal("4800")));
        assertEquals(new BigDecimal("0.9000"),
                PerformanceService.efficiency(new BigDecimal("4320"), new BigDecimal("4800")));
    }

    @Test
    @DisplayName("效率系数：≥100% 奖 10%，90~100 不奖惩，80~90 罚 5%，<80 罚 10%")
    void efficiencyFactorSegments() {
        assertEquals(new BigDecimal("1.00"), PerformanceService.efficiencyFactor(null));
        assertEquals(new BigDecimal("1.10"), PerformanceService.efficiencyFactor(new BigDecimal("1.00")));
        assertEquals(new BigDecimal("1.10"), PerformanceService.efficiencyFactor(new BigDecimal("1.25")));
        assertEquals(new BigDecimal("1.00"), PerformanceService.efficiencyFactor(new BigDecimal("0.95")));
        assertEquals(new BigDecimal("0.95"), PerformanceService.efficiencyFactor(new BigDecimal("0.85")));
        assertEquals(new BigDecimal("0.90"), PerformanceService.efficiencyFactor(new BigDecimal("0.50")));
    }

    @Test
    @DisplayName("良率系数：≥99% 奖 5%，97~99 不奖惩，95~97 罚 5%，<95 罚 10%")
    void yieldFactorSegments() {
        // 刚好压线：99% 与 97% 都算进上一档
        assertEquals(new BigDecimal("1.05"), PerformanceService.yieldFactor(new BigDecimal("0.9900")));
        assertEquals(new BigDecimal("1.00"), PerformanceService.yieldFactor(new BigDecimal("0.9700")));
        assertEquals(new BigDecimal("0.95"), PerformanceService.yieldFactor(new BigDecimal("0.9500")));
        assertEquals(new BigDecimal("0.90"), PerformanceService.yieldFactor(new BigDecimal("0.9000")));
    }

    @Test
    @DisplayName("绩效系数 = 效率系数 × 良率系数；两项都缺省时为 1.0000")
    void scoreMultipliesBothFactors() {
        assertEquals(new BigDecimal("1.0000"), PerformanceService.score(null, null));
        // 效率 110% × 良率 99.5% = 1.10 × 1.05
        assertEquals(new BigDecimal("1.1550"),
                PerformanceService.score(new BigDecimal("1.10"), new BigDecimal("0.9950")));
        // 效率 85% × 良率 96% = 0.95 × 0.95
        assertEquals(new BigDecimal("0.9025"),
                PerformanceService.score(new BigDecimal("0.85"), new BigDecimal("0.9600")));
    }
}
