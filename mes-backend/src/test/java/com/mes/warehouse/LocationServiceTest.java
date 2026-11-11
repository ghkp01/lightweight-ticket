package com.mes.warehouse;

import com.mes.common.BizException;
import com.mes.inventory.InventoryService;
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
 * 库位：库位唯一性、入库上架、整批与部分移库、跨仓与超量拦截、有库存不可删。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocationServiceTest {

    @Autowired
    private LocationService locationService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbc;

    private long warehouseId;
    private long productId;
    private String tag;
    private long locationA;
    private long locationB;

    @BeforeEach
    void setUp() {
        tag = String.valueOf(System.nanoTime());
        warehouseId = jdbc.queryForObject("select id from warehouse where code = 'WH-RAW'", Long.class);
        productId = jdbc.queryForObject("select id from product where code = 'P003'", Long.class);
        locationA = locationService.save(new LocationService.LocationRequest(
                null, warehouseId, "A-" + tag, "A 区货位", "A 区", true, null));
        locationB = locationService.save(new LocationService.LocationRequest(
                null, warehouseId, "B-" + tag, "B 区货位", "B 区", true, null));
    }

    private BigDecimal batchQty(String batchNo) {
        List<BigDecimal> values = jdbc.queryForList("""
                select qty from inventory_batch
                where product_id = ? and warehouse_id = ? and batch_no = ?
                """, BigDecimal.class, productId, warehouseId, batchNo);
        return values.isEmpty() ? BigDecimal.ZERO : values.get(0);
    }

    private Object batchLocation(String batchNo) {
        List<Object> values = jdbc.queryForList("""
                select location_id from inventory_batch
                where product_id = ? and warehouse_id = ? and batch_no = ?
                """, Object.class, productId, warehouseId, batchNo);
        return values.isEmpty() ? null : values.get(0);
    }

    @Test
    @DisplayName("库位编码在仓库内唯一，可列出在库批次")
    void locationUnique() {
        List<Map<String, Object>> rows = locationService.list(warehouseId, "A-" + tag);
        assertEquals(1, rows.size());
        assertEquals("A 区", rows.get(0).get("zone"));

        BizException dup = assertThrows(BizException.class, () -> locationService.save(
                new LocationService.LocationRequest(null, warehouseId, "A-" + tag, "重名", null, true, null)));
        assertEquals(4002, dup.getCode());

        BizException noCode = assertThrows(BizException.class, () -> locationService.save(
                new LocationService.LocationRequest(null, warehouseId, " ", "空编码", null, true, null)));
        assertEquals(1002, noCode.getCode());
    }

    @Test
    @DisplayName("入库指定库位后批次带库位")
    void inboundAssignsLocation() {
        String batchNo = "LOC-" + tag;
        String batch = inventoryService.inboundWithBatch(productId, warehouseId, new BigDecimal("10"),
                "INBOUND", batchNo, "库位测试", batchNo, "INBOUND", null, new BigDecimal("20"));
        locationService.assignBatch(productId, warehouseId, batch, "A-" + tag);
        assertEquals(locationA, ((Number) batchLocation(batchNo)).longValue());

        List<Map<String, Object>> batches = locationService.batchLocations(warehouseId, batchNo);
        assertTrue(batches.stream().anyMatch(row -> ("A-" + tag).equals(row.get("locationCode"))));

        BizException missing = assertThrows(BizException.class,
                () -> locationService.assignBatch(productId, warehouseId, batch, "NOT-EXIST"));
        assertEquals(4004, missing.getCode());
    }

    @Test
    @DisplayName("整批移库只换库位不动数量，并记一条移库流水")
    void moveWholeBatch() {
        String batchNo = "MOVE-" + tag;
        inventoryService.inboundWithBatch(productId, warehouseId, new BigDecimal("8"), "INBOUND",
                batchNo, "移库测试", batchNo, "INBOUND", null, new BigDecimal("20"));
        locationService.assignBatch(productId, warehouseId, batchNo, "A-" + tag);
        BigDecimal stockBefore = inventoryService.stock(productId, warehouseId);

        locationService.move(new LocationService.MoveRequest(productId, warehouseId, batchNo,
                new BigDecimal("8"), locationB, "整理货位"));

        assertEquals(locationB, ((Number) batchLocation(batchNo)).longValue());
        assertEquals(0, stockBefore.compareTo(inventoryService.stock(productId, warehouseId)), "总库存不变");
        assertEquals(0, new BigDecimal("8").compareTo(batchQty(batchNo)));
        assertEquals(1, locationService.moves(warehouseId, 10).stream()
                .filter(row -> batchNo.equals(row.get("batchNo"))).count());
    }

    @Test
    @DisplayName("部分移库拆出尾号批次，两批数量合计不变")
    void movePartially() {
        String batchNo = "PART-" + tag;
        inventoryService.inboundWithBatch(productId, warehouseId, new BigDecimal("10"), "INBOUND",
                batchNo, "部分移库", batchNo, "INBOUND", null, new BigDecimal("20"));
        locationService.assignBatch(productId, warehouseId, batchNo, "A-" + tag);
        BigDecimal stockBefore = inventoryService.stock(productId, warehouseId);

        locationService.move(new LocationService.MoveRequest(productId, warehouseId, batchNo,
                new BigDecimal("4"), locationB, "分一半到 B 区"));

        assertEquals(0, new BigDecimal("6").compareTo(batchQty(batchNo)));
        assertEquals(0, new BigDecimal("4").compareTo(batchQty(batchNo + "-" + locationB)));
        assertEquals(0, stockBefore.compareTo(inventoryService.stock(productId, warehouseId)));
        assertEquals(locationB, ((Number) batchLocation(batchNo + "-" + locationB)).longValue());
    }

    @Test
    @DisplayName("超量与跨仓库位被拒，有库存的库位不能删")
    void moveGuards() {
        String batchNo = "GUARD-" + tag;
        inventoryService.inboundWithBatch(productId, warehouseId, new BigDecimal("5"), "INBOUND",
                batchNo, "拦截测试", batchNo, "INBOUND", null, new BigDecimal("20"));
        locationService.assignBatch(productId, warehouseId, batchNo, "A-" + tag);

        BizException tooMany = assertThrows(BizException.class,
                () -> locationService.move(new LocationService.MoveRequest(productId, warehouseId, batchNo,
                        new BigDecimal("6"), locationB, null)));
        assertEquals(4005, tooMany.getCode());

        long otherWarehouse = jdbc.queryForObject("select id from warehouse where code = 'WH-FIN'", Long.class);
        long otherLocation = locationService.save(new LocationService.LocationRequest(
                null, otherWarehouse, "FIN-" + tag, "成品库位", null, true, null));
        BizException crossWarehouse = assertThrows(BizException.class,
                () -> locationService.move(new LocationService.MoveRequest(productId, warehouseId, batchNo,
                        new BigDecimal("1"), otherLocation, null)));
        assertEquals(1002, crossWarehouse.getCode());

        BizException inUse = assertThrows(BizException.class, () -> locationService.delete(locationA));
        assertEquals(4003, inUse.getCode());

        BizException unknownBatch = assertThrows(BizException.class,
                () -> locationService.move(new LocationService.MoveRequest(productId, warehouseId, "NO-" + tag,
                        BigDecimal.ONE, locationB, null)));
        assertEquals(4001, unknownBatch.getCode());
    }

    @Test
    @DisplayName("空库位可以删除，停用库位不能作移库目标")
    void deleteEmptyLocation() {
        long empty = locationService.save(new LocationService.LocationRequest(
                null, warehouseId, "EMPTY-" + tag, "空库位", null, true, null));
        locationService.delete(empty);
        assertTrue(locationService.options(warehouseId).stream()
                .noneMatch(row -> ((Number) row.get("id")).longValue() == empty));

        locationService.save(new LocationService.LocationRequest(
                locationB, warehouseId, "B-" + tag, "B 区货位", "B 区", false, null));
        String batchNo = "DISABLED-" + tag;
        inventoryService.inboundWithBatch(productId, warehouseId, new BigDecimal("3"), "INBOUND",
                batchNo, "停用库位测试", batchNo, "INBOUND", null, new BigDecimal("20"));
        BizException disabled = assertThrows(BizException.class,
                () -> locationService.move(new LocationService.MoveRequest(productId, warehouseId, batchNo,
                        BigDecimal.ONE, locationB, null)));
        assertEquals(4004, disabled.getCode());
    }
}
