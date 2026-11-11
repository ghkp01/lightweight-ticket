package com.mes.inventory;

import com.mes.inventory.api.InventoryPort;
import com.mes.inventory.api.StockLine;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存契约的只读聚合（{@code totalStocks} / {@code availableStocks} / {@code stocksIn} / {@code allStocks}）。
 *
 * <p>这几个方法是「别的模块问库存够不够」的唯一入口，口径必须钉死：
 * 全仓合计、可用量要扣锁定、查不到的产品不进 map（调用方自己 getOrDefault 兜 0）、
 * 空集合不发 SQL。改这几条语义前，先想清楚每个调用方会不会跟着变。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryPortReadTest {

    @Autowired
    private InventoryPort inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long otherProductId;
    private long warehouseA;
    private long warehouseB;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        productId = createProduct("IP" + suffix, "契约测试料" + suffix);
        otherProductId = createProduct("IQ" + suffix, "契约测试料2" + suffix);
        warehouseA = createWarehouse("IWA" + suffix);
        warehouseB = createWarehouse("IWB" + suffix);
        setStock(productId, warehouseA, new BigDecimal("10"), new BigDecimal("2"));
        setStock(productId, warehouseB, new BigDecimal("5"), BigDecimal.ZERO);
        setStock(otherProductId, warehouseB, new BigDecimal("7"), new BigDecimal("7"));
    }

    private long createProduct(String code, String name) {
        return jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', 'kg', 10) returning id
                """, Long.class, code, name);
    }

    private long createWarehouse(String code) {
        return jdbc.queryForObject("""
                insert into warehouse (code, name, type) values (?, ?, 'RAW') returning id
                """, Long.class, code, "契约测试仓" + code);
    }

    private void setStock(long product, long warehouse, BigDecimal qty, BigDecimal locked) {
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty, locked_qty) values (?, ?, ?, ?)
                on conflict (product_id, warehouse_id) do update
                set qty = excluded.qty, locked_qty = excluded.locked_qty
                """, product, warehouse, qty, locked);
    }

    @Test
    @DisplayName("结存合计跨仓库相加，没库存记录的产品不出现在返回里")
    void totalStocksSumsAcrossWarehouses() {
        Map<Long, BigDecimal> totals = inventory.totalStocks(List.of(productId, otherProductId));
        assertEquals(0, new BigDecimal("15").compareTo(totals.get(productId)));
        assertEquals(0, new BigDecimal("7").compareTo(totals.get(otherProductId)));

        Map<Long, BigDecimal> missing = inventory.totalStocks(List.of(-1L));
        assertTrue(missing.isEmpty());
        assertTrue(inventory.totalStocks(List.of()).isEmpty());
        assertTrue(inventory.totalStocks(null).isEmpty());
    }

    @Test
    @DisplayName("可用量 = 结存 - 锁定，按仓库分别扣")
    void availableStocksSubtractsLocked() {
        Map<Long, BigDecimal> available = inventory.availableStocks(List.of(productId, otherProductId));
        // 10-2 + 5-0
        assertEquals(0, new BigDecimal("13").compareTo(available.get(productId)));
        // 7-7
        assertEquals(0, BigDecimal.ZERO.compareTo(available.get(otherProductId)));
    }

    @Test
    @DisplayName("某仓明细只含该仓的行，结存为 0 的行也要返回，由调用方自己筛")
    void stocksInReturnsRowsOfThatWarehouse() {
        setStock(otherProductId, warehouseA, BigDecimal.ZERO, BigDecimal.ZERO);
        List<StockLine> lines = inventory.stocksIn(warehouseA);
        assertEquals(2, lines.size(), "该仓两行都要返回，包括结存为 0 的");
        assertTrue(lines.stream().allMatch(line -> line.warehouseId() == warehouseA));
        assertTrue(lines.stream().anyMatch(line -> line.productId() == otherProductId
                && line.qty().signum() == 0));
    }

    @Test
    @DisplayName("全仓明細覆盖所有仓库，能直接算低库存这类跨仓指标")
    void allStocksCoversEveryWarehouse() {
        List<StockLine> all = inventory.allStocks();
        assertTrue(all.stream().anyMatch(line -> line.warehouseId() == warehouseA));
        assertTrue(all.stream().anyMatch(line -> line.warehouseId() == warehouseB));
        StockLine line = all.stream().filter(item -> item.productId() == productId
                && item.warehouseId() == warehouseA).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("8").compareTo(line.available()));
    }

    @Test
    @DisplayName("单仓单产品查询：没有记录返回 0，不返回 null")
    void stockReturnsZeroWhenAbsent() {
        assertEquals(0, new BigDecimal("10").compareTo(inventory.stock(productId, warehouseA)));
        assertEquals(0, BigDecimal.ZERO.compareTo(inventory.stock(productId, -1L)));
    }
}
