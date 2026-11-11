package com.mes.quality;

import com.mes.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPC：均值与 σ、Cpk、越限点、单侧限、P 图控制限随样本量变化、能力排行。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SpcTest {

    @Autowired
    private SpcService service;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long itemId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = String.valueOf(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit) values (?, ?, 'FINISHED', '件') returning id
                """, Long.class, "SP" + tag, "SPC测试件");
        itemId = jdbc.queryForObject("""
                insert into inspection_item (product_id, item_code, item_name, standard, method,
                                             value_type, lower_limit, upper_limit, unit, required, sort_no)
                values (?, 'D1', '外径', '10±0.2', '测量', 'MEASURE', 9.8, 10.2, 'mm', true, 1)
                returning id
                """, Long.class, productId);
    }

    /** 造一张检验单 + 一条实测值 */
    private void inspection(String measured, BigDecimal inspectQty, BigDecimal defectQty) {
        long inspectionId = jdbc.queryForObject("""
                insert into inspection (inspection_no, type, product_id, inspect_qty, defect_qty, result,
                                        inspector, inspected_at)
                values (?, 'PROCESS', ?, ?, ?, 'PASS', 'SPC测试', now())
                returning id
                """, Long.class, "SPI" + System.nanoTime(), productId, inspectQty, defectQty);
        jdbc.update("""
                insert into inspection_item_result (inspection_id, item_id, item_name, standard, sample_qty,
                                                    defect_qty, measured, result)
                values (?, ?, '外径', '10±0.2', 1, 0, ?, 'PASS')
                """, inspectionId, itemId, measured);
    }

    @Test
    @DisplayName("实测值算均值、σ、控制限与 Cpk，越限点被标出")
    void chartWithCapability() {
        // 20 个稳定点 + 1 个明显离群点：样本足够时离群点才会突破 ±3σ
        for (int i = 0; i < 20; i++) {
            inspection(i % 2 == 0 ? "10.00" : "10.02", new BigDecimal("10"), BigDecimal.ZERO);
        }
        inspection("10.80", new BigDecimal("10"), new BigDecimal("1"));

        Map<String, Object> chart = service.chart(itemId, productId, null, null);
        Map<?, ?> stats = (Map<?, ?>) chart.get("stats");
        assertEquals(21, ((Number) stats.get("count")).intValue());
        double mean = ((Number) stats.get("mean")).doubleValue();
        assertTrue(mean > 10 && mean < 10.1, "均值应被离群值轻微抬高，实际 " + mean);
        assertTrue(((Number) stats.get("sigma")).doubleValue() > 0);
        assertTrue(((Number) stats.get("breaches")).intValue() >= 1, "10.80 应被判越限");
        assertNotNull(stats.get("cpk"), "有上下限时应给出 Cpk");
        assertTrue(((Number) stats.get("cpk")).doubleValue() < 1.33, "波动大，Cpk 应偏低");

        List<?> points = (List<?>) chart.get("points");
        Map<?, ?> last = (Map<?, ?>) points.get(points.size() - 1);
        assertTrue(Boolean.TRUE.equals(last.get("breach")), "最后一个点应标记越限");
    }

    @Test
    @DisplayName("没有实测值或只有 1 个点时不给 Cpk，也不报错")
    void chartWithoutEnoughData() {
        Map<String, Object> empty = service.chart(itemId, productId, null, null);
        assertEquals(0, ((Number) ((Map<?, ?>) empty.get("stats")).get("count")).intValue());

        inspection("10.00", new BigDecimal("10"), BigDecimal.ZERO);
        Map<String, Object> single = service.chart(itemId, productId, null, null);
        Map<?, ?> stats = (Map<?, ?>) single.get("stats");
        assertEquals(1, ((Number) stats.get("count")).intValue());
        assertTrue(stats.get("cpk") == null, "样本不足 2 个时不算 Cpk");
    }

    @Test
    @DisplayName("P 图中心线用合计不良率，越限点标出")
    void pChart() {
        inspection("10.00", new BigDecimal("100"), new BigDecimal("2"));
        inspection("10.00", new BigDecimal("100"), new BigDecimal("3"));
        inspection("10.00", new BigDecimal("100"), new BigDecimal("40"));

        Map<String, Object> chart = service.pChart(productId, null, null);
        Map<?, ?> stats = (Map<?, ?>) chart.get("stats");
        assertEquals(3, ((Number) stats.get("count")).intValue());
        assertEquals(0, new BigDecimal("45").compareTo(new BigDecimal(String.valueOf(stats.get("defectQty")))));
        double center = ((Number) stats.get("centerRate")).doubleValue();
        assertTrue(center > 14 && center < 16, "中心线应接近 15%，实际 " + center);
        assertTrue(((Number) stats.get("breaches")).intValue() >= 1, "40% 不良那一单应越限");

        List<?> points = (List<?>) chart.get("points");
        Map<?, ?> third = (Map<?, ?>) points.get(2);
        assertTrue(Boolean.TRUE.equals(third.get("breach")));
        assertTrue(((Number) third.get("ucl")).doubleValue() < ((Number) third.get("rate")).doubleValue());
    }

    @Test
    @DisplayName("能力排行按 Cpk 升序，能力不足会打标")
    void capabilityRanking() {
        for (int i = 0; i < 6; i++) {
            inspection(String.valueOf(9.9 + i * 0.06), new BigDecimal("10"), BigDecimal.ZERO);
        }
        List<Map<String, Object>> rows = service.capability(productId);
        assertTrue(rows.size() >= 1);
        Map<String, Object> row = rows.get(0);
        assertEquals(itemId, ((Number) row.get("id")).longValue());
        assertEquals("外径", row.get("itemName"));
        assertNotNull(row.get("cpk"));
        assertTrue(List.of("充足", "偏低", "不足").contains(String.valueOf(row.get("level"))),
                String.valueOf(row.get("level")));
        assertEquals(Boolean.TRUE, row.get("alert"), "Cpk 低于 1.33 应打预警标");
    }

    @Test
    @DisplayName("检验项清单与非法日期")
    void itemsAndInvalidDate() {
        List<Map<String, Object>> items = service.items(productId);
        assertTrue(items.stream().anyMatch(item -> ((Number) item.get("id")).longValue() == itemId));

        BizException badDate = assertThrows(BizException.class,
                () -> service.chart(itemId, productId, "2026-99-99", null));
        assertEquals(1002, badDate.getCode());

        BizException reversed = assertThrows(BizException.class,
                () -> service.pChart(productId, "2026-12-31", "2026-01-01"));
        assertEquals(1002, reversed.getCode());
    }
}
