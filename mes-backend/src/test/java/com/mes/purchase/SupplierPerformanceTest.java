package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.warehouse.InboundOrderService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 供应商绩效：准时率（按预计到货日）、来料合格率（按检验数量）、退货率、综合得分与价格趋势。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SupplierPerformanceTest {

    @Autowired
    private SupplierPerformanceService service;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private InboundOrderService inboundOrderService;

    @Autowired
    private JdbcTemplate jdbc;

    private long supplierId;
    private long warehouseId;
    private long productId;

    @BeforeEach
    void setUp() {
        supplierId = jdbc.queryForObject("select id from supplier where code = 'S001'", Long.class);
        warehouseId = jdbc.queryForObject("select id from warehouse where code = 'WH-RAW'", Long.class);
        productId = jdbc.queryForObject("select id from product where code = 'P003'", Long.class);
    }

    /** 建采购单 → 确认 → 收货 → 入库完成，返回入库单 id */
    private long receiveOrder(LocalDate expectedDate, BigDecimal qty, BigDecimal price) {
        return receiveOrder(LocalDate.now(), expectedDate, qty, price);
    }

    private long receiveOrder(LocalDate orderDate, LocalDate expectedDate, BigDecimal qty, BigDecimal price) {
        long orderId = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, warehouseId, orderDate, expectedDate, false, "绩效测试",
                List.of(new PurchaseOrderService.ItemRequest(productId, qty, price, null))));
        purchaseOrderService.confirm(orderId);
        Map<String, Object> receipt = purchaseOrderService.receive(orderId, null);
        long inboundId = ((Number) receipt.get("inboundId")).longValue();
        inboundOrderService.submit(inboundId);
        return inboundId;
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> rows) {
        return rows.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == supplierId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("绩效列表里没有该供应商"));
    }

    @Test
    @DisplayName("准时率按预计到货日判定，逾期批次不算准时")
    void onTimeRate() {
        receiveOrder(LocalDate.now(), LocalDate.now().plusDays(5), new BigDecimal("10"), new BigDecimal("10"));
        // 逾期批次：下单在 30 天前、预计 10 天前到货，实际今天才入库
        receiveOrder(LocalDate.now().minusDays(30), LocalDate.now().minusDays(10),
                new BigDecimal("10"), new BigDecimal("10"));

        Map<String, Object> row = rowOf(service.ranking(null, null, supplierId));
        assertEquals(2, ((Number) row.get("judgedBatches")).intValue(), "两批都有预计到货日");
        assertEquals(1, ((Number) row.get("onTimeBatches")).intValue(), "只有一批准时");
        assertEquals(0, new BigDecimal("50.00").compareTo(new BigDecimal(String.valueOf(row.get("onTimeRate")))));
    }

    @Test
    @DisplayName("来料合格率按检验数量算，综合得分与等级随之变化")
    void qualityAndScore() {
        long inboundId = receiveOrder(LocalDate.now().plusDays(3), new BigDecimal("10"), new BigDecimal("10"));
        jdbc.update("""
                insert into inspection (inspection_no, type, ref_type, ref_no, product_id, inspect_qty,
                                        defect_qty, result, inspector, inspected_at, inbound_order_id)
                values (?, 'INCOMING', 'INBOUND', ?, ?, 10, 2, 'PASS', '绩效测试', now(), ?)
                """, "QIP" + System.nanoTime(), "PERF", productId, inboundId);

        Map<String, Object> row = rowOf(service.ranking(null, null, supplierId));
        assertEquals(0, new BigDecimal("80.00").compareTo(new BigDecimal(String.valueOf(row.get("passRate")))));
        // 准时 × 0.4 + 合格 × 0.4 +（1−退货率）× 0.2，这里只校验落在合理区间
        BigDecimal score = new BigDecimal(String.valueOf(row.get("score")));
        assertTrue(score.compareTo(new BigDecimal("80")) >= 0 && score.compareTo(new BigDecimal("100")) <= 0,
                "综合得分应在 80~100，实际 " + score);
        assertTrue(List.of("A", "B").contains(String.valueOf(row.get("grade"))), "等级应为 A 或 B");
        assertTrue(Boolean.TRUE.equals(row.get("hasData")));
    }

    @Test
    @DisplayName("没有收货与检验时只按退货率算分，不把「没数据」当满分")
    void scoreWithoutData() {
        long freshId = jdbc.queryForObject("""
                insert into supplier (code, name, category) values (?, ?, 'MATERIAL') returning id
                """, Long.class, "SPF" + System.nanoTime(), "绩效空数据供应商");
        List<Map<String, Object>> rows = service.ranking(null, null, freshId);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(0, ((Number) row.get("judgedBatches")).intValue());
        assertEquals(null, row.get("score"), "没有往来数据时不给分，避免空供应商被排到前面");
        assertEquals("-", String.valueOf(row.get("grade")));
        assertTrue(!Boolean.TRUE.equals(row.get("hasData")));
    }

    @Test
    @DisplayName("明细带价格趋势、物料分布、收货与检验记录")
    void detailContainsTrend() {
        receiveOrder(LocalDate.now().plusDays(2), new BigDecimal("20"), new BigDecimal("12"));
        Map<String, Object> detail = service.detail(supplierId, null, null);
        Map<?, ?> summary = (Map<?, ?>) detail.get("summary");
        assertNotNull(summary.get("score"));

        List<?> trend = (List<?>) detail.get("priceTrend");
        List<?> materials = (List<?>) detail.get("materials");
        List<?> receipts = (List<?>) detail.get("receipts");
        assertTrue(trend.size() >= 1, "应有月度均价行");
        String month = String.format("%d-%02d", LocalDate.now().getYear(), LocalDate.now().getMonthValue());
        Map<?, ?> target = trend.stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> "P003".equals(item.get("productCode")) && month.equals(item.get("month")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("价格趋势里没有本月 P003"));
        assertTrue(new BigDecimal(String.valueOf(target.get("qty"))).compareTo(new BigDecimal("20")) >= 0,
                "本次收货的 20 件应计入本月数量");
        assertTrue(new BigDecimal(String.valueOf(target.get("avgPrice"))).signum() > 0);
        assertTrue(materials.size() >= 1);
        assertTrue(receipts.size() >= 1);
        assertNotNull(detail.get("inspections"));
    }

    @Test
    @DisplayName("非法日期与不存在的供应商被拒")
    void invalidInput() {
        BizException badDate = assertThrows(BizException.class,
                () -> service.ranking("2026-13-01", null, null));
        assertEquals(1002, badDate.getCode());

        BizException reversed = assertThrows(BizException.class,
                () -> service.ranking("2026-12-31", "2026-01-01", null));
        assertEquals(1002, reversed.getCode());

        BizException missing = assertThrows(BizException.class, () -> service.detail(999999L, null, null));
        assertEquals(4001, missing.getCode());
    }
}
