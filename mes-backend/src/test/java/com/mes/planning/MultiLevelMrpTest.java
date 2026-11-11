package com.mes.planning;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多层物料需求：半成品继续展开到原料、层数可限、扣库存后给缺口、入参校验。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiLevelMrpTest {

    @Autowired
    private MultiLevelMrpService service;

    @Autowired
    private JdbcTemplate jdbc;

    private long finishedId;
    private long semiId;
    private long rawUnderSemiId;
    private long rawDirectId;
    private String tag;

    private long product(String code, String category) {
        return jdbc.queryForObject("""
                insert into product (code, name, category, unit, loss_rate)
                values (?, ?, ?, '件', 0) returning id
                """, Long.class, code, "多层MRP-" + code, category);
    }

    private long bom(String bomNo, long productId) {
        return jdbc.queryForObject("""
                insert into bom (bom_no, product_id, version, status, base_qty, effective_from)
                values (?, ?, 'V1.0', 'ACTIVE', 1, current_date) returning id
                """, Long.class, bomNo, productId);
    }

    @BeforeEach
    void setUp() {
        tag = String.valueOf(System.nanoTime());
        finishedId = product("MF" + tag, "FINISHED");
        semiId = product("MS" + tag, "SEMI");
        rawUnderSemiId = product("MR2" + tag, "RAW");
        rawDirectId = product("MR1" + tag, "RAW");

        // 成品：1 个半成品 + 1 个直接原料
        long finishedBomId = bom("BOMF" + tag, finishedId);
        jdbc.update("insert into bom_item (bom_id, material_id, qty) values (?, ?, 2)",
                finishedBomId, semiId);
        jdbc.update("insert into bom_item (bom_id, material_id, qty) values (?, ?, 1)",
                finishedBomId, rawDirectId);
        // 半成品：3 个下层原料
        long semiBomId = bom("BOMS" + tag, semiId);
        jdbc.update("insert into bom_item (bom_id, material_id, qty) values (?, ?, 3)",
                semiBomId, rawUnderSemiId);

        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'RELEASED') returning id
                """, Long.class, "MWO" + tag, finishedId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty, source)
                values (?, ?, 20, 0, 'STANDARD')
                """, workOrderId, semiId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty, source)
                values (?, ?, 10, 0, 'STANDARD')
                """, workOrderId, rawDirectId);
    }

    private Map<String, Object> findByCode(List<Map<String, Object>> rows, String code) {
        return rows.stream()
                .filter(row -> code.equals(row.get("productCode")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("结果里没有 " + code));
    }

    @Test
    @DisplayName("半成品按 BOM 拆到原料，三层用量相乘")
    void explodeThreeLevels() {
        Map<String, Object> result = service.explode(new MultiLevelMrpService.ExplodeQuery(false, null, 3));
        List<Map<String, Object>> make = castList(result.get("make"));
        List<Map<String, Object>> buy = castList(result.get("buy"));

        Map<String, Object> semi = findByCode(make, "MS" + tag);
        assertEquals(1, ((Number) semi.get("level")).intValue());
        assertEquals(0, new BigDecimal("20").compareTo(new BigDecimal(String.valueOf(semi.get("grossNeed")))));

        Map<String, Object> direct = findByCode(buy, "MR1" + tag);
        assertEquals(1, ((Number) direct.get("level")).intValue());
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(String.valueOf(direct.get("grossNeed")))));

        Map<String, Object> underSemi = findByCode(buy, "MR2" + tag);
        assertEquals(2, ((Number) underSemi.get("level")).intValue(), "半成品下层记到第 2 层");
        assertEquals(0, new BigDecimal("60").compareTo(new BigDecimal(String.valueOf(underSemi.get("grossNeed")))),
                "20 个半成品 × 3 = 60");
        assertTrue(String.valueOf(underSemi.get("parents")).contains("MS" + tag), "要标出上层来源");

        Map<String, Object> summary = castMap(result.get("summary"));
        // 测试库里还有别的在制工单，只校验本次构造的物料被正确分类
        assertTrue(((Number) summary.get("demandRows")).intValue() >= 3);
        assertTrue(((Number) summary.get("makeRows")).intValue() >= 1);
        assertTrue(((Number) summary.get("buyRows")).intValue() >= 2);
    }

    @Test
    @DisplayName("层数限制为 1 时不展开半成品下层")
    void maxLevelLimitsExplosion() {
        Map<String, Object> result = service.explode(new MultiLevelMrpService.ExplodeQuery(false, null, 1));
        List<Map<String, Object>> buy = castList(result.get("buy"));
        assertTrue(buy.stream().noneMatch(row -> ("MR2" + tag).equals(row.get("productCode"))),
                "只展开一层时不该出现半成品下层的原料");
        assertTrue(buy.stream().anyMatch(row -> ("MR1" + tag).equals(row.get("productCode"))));
    }

    @Test
    @DisplayName("扣掉库存后给缺口，库存覆盖时缺口为 0")
    void shortageUsesStock() {
        long warehouseId = jdbc.queryForObject(
                "select id from warehouse where code = 'WH-RAW'", Long.class);
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, 15)
                on conflict (product_id, warehouse_id) do update set qty = excluded.qty
                """, semiId, warehouseId);

        Map<String, Object> result = service.explode(new MultiLevelMrpService.ExplodeQuery(false, null, 2));
        Map<String, Object> semi = findByCode(castList(result.get("make")), "MS" + tag);
        assertEquals(0, new BigDecimal("5").compareTo(new BigDecimal(String.valueOf(semi.get("shortage")))),
                "需求 20 件、库存 15 件，缺口 5");
        assertEquals(0, new BigDecimal("15").compareTo(new BigDecimal(String.valueOf(semi.get("stock")))));
    }

    @Test
    @DisplayName("层数越界与查询参数缺省都能处理")
    void parameters() {
        BizException tooDeep = assertThrows(BizException.class,
                () -> service.explode(new MultiLevelMrpService.ExplodeQuery(false, null, 9)));
        assertEquals(1002, tooDeep.getCode());

        BizException zero = assertThrows(BizException.class,
                () -> service.explode(new MultiLevelMrpService.ExplodeQuery(false, null, 0)));
        assertEquals(1002, zero.getCode());

        Map<String, Object> defaults = service.explode(null);
        assertTrue(castMap(defaults.get("summary")).get("maxLevel") != null, "默认按 3 层展开");
    }

    @Test
    @DisplayName("含已排产时把已排产工单的 BOM 也算进来")
    void includeScheduled() {
        long workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 5, 'SCHEDULED') returning id
                """, Long.class, "MSCH" + tag, finishedId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty, source)
                values (?, ?, 5, 0, 'STANDARD')
                """, workOrderId, rawDirectId);

        Map<String, Object> without = service.explode(new MultiLevelMrpService.ExplodeQuery(false, null, 2));
        Map<String, Object> with = service.explode(new MultiLevelMrpService.ExplodeQuery(true, null, 2));
        BigDecimal before = new BigDecimal(String.valueOf(
                findByCode(castList(without.get("buy")), "MR1" + tag).get("grossNeed")));
        BigDecimal after = new BigDecimal(String.valueOf(
                findByCode(castList(with.get("buy")), "MR1" + tag).get("grossNeed")));
        assertTrue(after.subtract(before).compareTo(new BigDecimal("5")) == 0,
                "含已排产后直接原料需求应多 5，实际 " + before + " → " + after);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
