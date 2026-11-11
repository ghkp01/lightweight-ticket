package com.mes.production;

import com.mes.andon.AndonService;
import com.mes.common.PageResult;
import com.mes.inventory.InventoryService;
import com.mes.warehouse.WarehouseService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 齐套与缺料联动测试：
 * 1. 列表带齐套字段，支持只看缺料；
 * 2. 下达时料不齐自动报物料安灯（不重复报）；
 * 3. 到货入库后自动判断齐套、推送可领料提醒并关掉自动报的缺料安灯。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaterialReadinessTest {

    @Autowired
    private ProductionService productionService;

    @Autowired
    private AndonService andonService;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;
    private long productId;
    private long materialId;
    private long workOrderId;
    private long rawWarehouse;
    private String warehouseUser;

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 0) returning id
                """, Long.class, "RP" + suffix, "齐套测试成品" + suffix);
        materialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', 'kg', 0) returning id
                """, Long.class, "RM" + suffix, "齐套测试主料" + suffix);
        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 10, 'SCHEDULED') returning id
                """, Long.class, "WOR" + suffix, productId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty,
                                            operation_seq, source)
                values (?, ?, 10, 0, 1, 'STANDARD')
                """, workOrderId, materialId);
        // 下达要求工单至少有一道工序（空工序的工单不能下达），这里补一道不影响齐套判断
        jdbc.update("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, standard_minutes, unit_price)
                values (?, 1, '齐套测试工序', 10, 0, 0)
                """, workOrderId);
        rawWarehouse = inventoryService.warehouseOfType("RAW");
        warehouseUser = "wh" + suffix;
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, enabled)
                values (?, 'x', ?, 'WAREHOUSE', true)
                """, warehouseUser, "仓库" + suffix);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void setStock(long productId, long warehouseId, String qty) {
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, ?)
                on conflict (product_id, warehouse_id) do update set qty = excluded.qty
                """, productId, warehouseId, new BigDecimal(qty));
    }

    @Test
    @DisplayName("工单列表带齐套字段，onlyShortage 只返回待领量大于 0 的单")
    void listExposesKitFieldsAndOnlyShortage() {
        long kittedId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 5, 'RELEASED') returning id
                """, Long.class, "WOK" + suffix, productId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty,
                                            operation_seq, source)
                values (?, ?, 5, 5, 1, 'STANDARD')
                """, kittedId, materialId);

        PageResult all = productionService.list("WOR" + suffix, null, 1, 20, false);
        assertEquals(1, all.records().size());
        Map<String, Object> row = (Map<String, Object>) all.records().get(0);
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(String.valueOf(row.get("materialNeedQty")))));
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(String.valueOf(row.get("materialPendingQty")))));
        assertEquals(1, ((Number) row.get("shortageLines")).intValue());

        PageResult shortageOnly = productionService.list("齐套测试成品" + suffix, null, 1, 20, true);
        assertEquals(1, shortageOnly.total());
        PageResult kittedOnly = productionService.list("WOK" + suffix, null, 1, 20, true);
        assertEquals(0, kittedOnly.total());
    }

    @Test
    @DisplayName("下达时料不齐自动报物料安灯，并推给仓库")
    void releaseRaisesMaterialAndon() {
        productionService.release(workOrderId);

        assertEquals("RELEASED",
                jdbc.queryForObject("select status from work_order where id = ?", String.class, workOrderId));
        List<Map<String, Object>> calls = jdbc.queryForList("""
                select call_no as "callNo", type, status, caller, description
                from andon_call where work_order_id = ?
                """, workOrderId);
        assertEquals(1, calls.size());
        Map<String, Object> call = calls.get(0);
        assertEquals("MATERIAL", call.get("type"));
        assertEquals("OPEN", call.get("status"));
        assertEquals("系统", call.get("caller"));
        assertTrue(String.valueOf(call.get("description")).startsWith("下达时库存不足"));
        assertTrue(String.valueOf(call.get("description")).contains("齐套测试主料" + suffix));

        assertEquals(1, count("""
                select count(*) from sys_notice where receiver = ? and category = 'ALERT'
                """, warehouseUser));
    }

    @Test
    @DisplayName("料已齐套时下达不报安灯，重复报缺料也不会刷屏")
    void releaseSkipsAndonWhenKittedAndDeduplicates() {
        setStock(materialId, rawWarehouse, "50");
        productionService.release(workOrderId);
        assertEquals(0, count("select count(*) from andon_call where work_order_id = ?", workOrderId));

        // 换一张缺料工单（先把库存清掉），连报两次只留一条未关闭的物料安灯
        setStock(materialId, rawWarehouse, "0");
        long shortId = jdbc.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status)
                values (?, ?, 3, 'RELEASED') returning id
                """, Long.class, "WOS" + suffix, productId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty,
                                            operation_seq, source)
                values (?, ?, 3, 0, 1, 'STANDARD')
                """, shortId, materialId);
        assertTrue(andonService.raiseMaterialShortage(shortId));
        assertFalse(andonService.raiseMaterialShortage(shortId));
        assertEquals(1, count("select count(*) from andon_call where work_order_id = ?", shortId));
    }

    @Test
    @DisplayName("到货入库后自动提醒可领料，并关掉自动报的缺料安灯")
    void inboundNotifiesKittedWorkOrder() {
        productionService.release(workOrderId);
        assertEquals(1, count("""
                select count(*) from andon_call
                where work_order_id = ? and type = 'MATERIAL' and status = 'OPEN'
                """, workOrderId));

        long inboundId = jdbc.queryForObject("""
                insert into inbound_order (inbound_no, type, warehouse_id, supplier, status, need_inspection)
                values (?, 'PURCHASE', ?, '测试供应商', 'DRAFT', false) returning id
                """, Long.class, "INT" + suffix, rawWarehouse);
        jdbc.update("""
                insert into inbound_order_item (inbound_id, product_id, qty, batch_no)
                values (?, ?, 10, ?)
                """, inboundId, materialId, "B" + suffix);

        warehouseService.completeInbound(inboundId, null);

        assertEquals("COMPLETED",
                jdbc.queryForObject("select status from inbound_order where id = ?", String.class, inboundId));
        assertEquals(0, count("""
                select count(*) from andon_call
                where work_order_id = ? and type = 'MATERIAL' and status = 'OPEN'
                """, workOrderId));
        assertEquals(1, count("""
                select count(*) from andon_call
                where work_order_id = ? and type = 'MATERIAL'
                  and status = 'RESOLVED' and handler = '系统'
                """, workOrderId));
        assertEquals(1, count("""
                select count(*) from sys_notice
                where receiver = ? and title like '%可以领料%'
                  and ref_no = (select work_no from work_order where id = ?)
                """, warehouseUser, workOrderId));
    }
}
