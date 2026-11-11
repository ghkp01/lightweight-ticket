package com.mes.report;

import com.mes.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 月结：库存期初 / 入 / 出 / 期末自洽、金额与流水一致、往来汇总字段齐全、月份校验。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MonthCloseTest {

    @Autowired
    private MonthCloseService service;

    @Autowired
    private JdbcTemplate jdbc;

    private String currentMonth() {
        LocalDate now = LocalDate.now();
        return String.format("%d-%02d", now.getYear(), now.getMonthValue());
    }

    @Test
    @DisplayName("库存月结每行满足 期末 = 期初 + 入 − 出")
    void stockRowsBalanced() {
        Map<String, Object> result = service.stockClose(currentMonth());
        List<?> rows = (List<?>) result.get("rows");
        assertTrue(rows.size() >= 1, "演示库当月应有库存流水");
        for (Object item : rows) {
            Map<?, ?> row = (Map<?, ?>) item;
            BigDecimal open = new BigDecimal(String.valueOf(row.get("openQty")));
            BigDecimal in = new BigDecimal(String.valueOf(row.get("inQty")));
            BigDecimal out = new BigDecimal(String.valueOf(row.get("outQty")));
            BigDecimal close = new BigDecimal(String.valueOf(row.get("closeQty")));
            assertEquals(0, open.add(in).subtract(out).compareTo(close),
                    "行不守恒：" + row.get("productCode") + " " + open + "+" + in + "-" + out + "≠" + close);
        }
    }

    @Test
    @DisplayName("汇总金额等于各行期末金额之和")
    void summaryMatchesRows() {
        Map<String, Object> result = service.stockClose(currentMonth());
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        List<?> rows = (List<?>) result.get("rows");
        BigDecimal total = BigDecimal.ZERO;
        for (Object item : rows) {
            Map<?, ?> row = (Map<?, ?>) item;
            total = total.add(new BigDecimal(String.valueOf(row.get("closeAmount"))));
        }
        assertEquals(0, total.compareTo(new BigDecimal(String.valueOf(summary.get("closeAmount")))),
                "汇总金额应等于明细合计");
        assertTrue(((Number) summary.get("rows")).intValue() == rows.size());
    }

    @Test
    @DisplayName("往来月结给出采购净额与销售净额")
    void settlementSummary() {
        Map<String, Object> result = service.settlement(currentMonth());
        assertEquals(currentMonth(), result.get("month"));
        assertTrue(result.get("purchaseNet") != null);
        assertTrue(result.get("salesNet") != null);
        assertTrue(result.get("purchase") != null);
        assertTrue(result.get("shipment") != null);
        BigDecimal purchaseNet = new BigDecimal(String.valueOf(result.get("purchaseNet")));
        assertTrue(purchaseNet.signum() >= 0, "采购净额不应为负，实际 " + purchaseNet);
    }

    @Test
    @DisplayName("月份列表含本月且格式正确")
    void monthOptions() {
        List<Map<String, Object>> months = service.monthOptions(12);
        assertTrue(months.size() >= 1);
        assertTrue(months.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("current"))),
                "列表里应标出本月");
        assertTrue(String.valueOf(months.get(0).get("month")).matches("\\d{4}-\\d{2}"));
    }

    @Test
    @DisplayName("非法月份被拒，缺省取本月")
    void monthValidation() {
        BizException bad = assertThrows(BizException.class, () -> service.stockClose("2026-13"));
        assertEquals(1002, bad.getCode());
        BizException format = assertThrows(BizException.class, () -> service.settlement("2026/01"));
        assertEquals(1002, format.getCode());
        assertEquals(currentMonth(), service.stockClose(null).get("month"));
    }

    @Test
    @DisplayName("没有任何流水的月份返回空明细而不是报错")
    void emptyMonth() {
        Map<String, Object> result = service.stockClose("2000-01");
        assertTrue(((List<?>) result.get("rows")).isEmpty());
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(0, new BigDecimal("0").compareTo(new BigDecimal(String.valueOf(summary.get("closeQty")))));
    }
}
