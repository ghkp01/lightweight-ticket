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
 * 采购与供应商：确认后才能收货、收货生成入库单、在途防重复收货、
 * 入库完成回写实收与状态、已到货不能取消、被引用的供应商不能删除。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PurchaseOrderTest {

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private InboundOrderService inboundOrderService;

    @Autowired
    private JdbcTemplate jdbc;

    private long supplierId;
    private long warehouseId;
    private long materialId;
    private long otherMaterialId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        supplierId = supplierService.create(new SupplierService.SupplierRequest(
                "PS" + suffix, "采购测试供应商" + suffix, "MATERIAL", "测试联系人",
                "13800009999", null, null, null, "月结 30 天", 5, "A", true, null));
        warehouseId = jdbc.queryForObject("select id from warehouse where code = 'WH-RAW'", Long.class);
        materialId = jdbc.queryForObject("select id from product where code = 'P003'", Long.class);
        otherMaterialId = jdbc.queryForObject("select id from product where code = 'P004'", Long.class);
    }

    @Test
    @DisplayName("建单 → 确认 → 收货 → 入库完成：实收回写、状态变为已到货、重复收货被挡")
    void fullReceiveFlow() {
        long id = purchaseOrderService.create(order(List.of(
                new PurchaseOrderService.ItemRequest(materialId, new BigDecimal("10"), new BigDecimal("5"), null),
                new PurchaseOrderService.ItemRequest(otherMaterialId, new BigDecimal("4"), new BigDecimal("2.5"), null))));
        Map<String, Object> draft = purchaseOrderService.detail(id);
        assertEquals("DRAFT", draft.get("status"));
        assertEquals(0, new BigDecimal(String.valueOf(draft.get("totalAmount"))).compareTo(new BigDecimal("60.00")));

        purchaseOrderService.confirm(id);
        assertEquals("CONFIRMED", purchaseOrderService.detail(id).get("status"));

        Map<String, Object> receipt = purchaseOrderService.receive(id, null);
        assertNotNull(receipt.get("inboundNo"));
        long inboundId = ((Number) receipt.get("inboundId")).longValue();
        Map<String, Object> inbound = inboundOrderService.detail(inboundId);
        assertEquals("PURCHASE", inbound.get("type"));
        assertEquals(purchaseOrderService.detail(id).get("orderNo"), inbound.get("purchaseOrderNo"));

        BizException duplicate = assertThrows(BizException.class,
                () -> purchaseOrderService.receive(id, null));
        assertEquals(1002, duplicate.getCode());
        assertTrue(duplicate.getMessage().contains("没有可收货"));

        inboundOrderService.submit(inboundId);

        Map<String, Object> received = purchaseOrderService.detail(id);
        assertEquals("RECEIVED", received.get("status"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) received.get("items");
        assertEquals(0, new BigDecimal(String.valueOf(items.get(0).get("receivedQty")))
                .compareTo(new BigDecimal("10")));
        assertEquals(0, new BigDecimal(String.valueOf(items.get(1).get("receivedQty")))
                .compareTo(new BigDecimal("4")));
        assertEquals(0, new BigDecimal(String.valueOf(items.get(0).get("availableQty"))).signum());

        Map<String, Object> price = purchaseOrderService.lastPrice(supplierId, materialId);
        assertEquals(0, new BigDecimal(String.valueOf(price.get("price"))).compareTo(new BigDecimal("5")));
    }

    @Test
    @DisplayName("部分收货：先到 4 件为部分到货，再收剩余 6 件转已到货")
    void partialThenRest() {
        long id = purchaseOrderService.create(order(List.of(
                new PurchaseOrderService.ItemRequest(materialId, new BigDecimal("10"), new BigDecimal("5"), null))));
        purchaseOrderService.confirm(id);
        Map<String, Object> detail = purchaseOrderService.detail(id);
        long itemId = ((Number) ((List<Map<String, Object>>) detail.get("items")).get(0).get("id")).longValue();

        Map<String, Object> first = purchaseOrderService.receive(id, new PurchaseOrderService.ReceiveRequest(
                List.of(new PurchaseOrderService.ReceiveLine(itemId, new BigDecimal("4"), "LOT-A")), false, null));
        inboundOrderService.submit(((Number) first.get("inboundId")).longValue());
        assertEquals("PARTIAL", purchaseOrderService.detail(id).get("status"));

        purchaseOrderService.receive(id, null);
        long inboundId = jdbc.queryForObject("""
                select id from inbound_order where purchase_order_id = ? and status = 'DRAFT'
                order by id desc limit 1
                """, Long.class, id);
        inboundOrderService.submit(inboundId);

        Map<String, Object> detailAfter = purchaseOrderService.detail(id);
        assertEquals("RECEIVED", detailAfter.get("status"));
        Map<String, Object> item = ((List<Map<String, Object>>) detailAfter.get("items")).get(0);
        assertEquals(0, new BigDecimal(String.valueOf(item.get("receivedQty"))).compareTo(new BigDecimal("10")));
    }

    @Test
    @DisplayName("草稿不能直接收货，收货数量不能超过待收")
    void receiveGuards() {
        long id = purchaseOrderService.create(order(List.of(
                new PurchaseOrderService.ItemRequest(materialId, new BigDecimal("5"), new BigDecimal("3"), null))));
        BizException notConfirmed = assertThrows(BizException.class,
                () -> purchaseOrderService.receive(id, null));
        assertEquals(2014, notConfirmed.getCode());

        purchaseOrderService.confirm(id);
        Map<String, Object> detail = purchaseOrderService.detail(id);
        long itemId = ((Number) ((List<Map<String, Object>>) detail.get("items")).get(0).get("id")).longValue();
        BizException tooMuch = assertThrows(BizException.class,
                () -> purchaseOrderService.receive(id, new PurchaseOrderService.ReceiveRequest(
                        List.of(new PurchaseOrderService.ReceiveLine(itemId, new BigDecimal("6"), null)), false, null)));
        assertEquals(1002, tooMuch.getCode());
        assertTrue(tooMuch.getMessage().contains("超过待收数量"));
    }

    @Test
    @DisplayName("已有到货或存在未完成入库单时不能取消采购单")
    void cancelGuards() {
        long id = purchaseOrderService.create(order(List.of(
                new PurchaseOrderService.ItemRequest(materialId, new BigDecimal("8"), new BigDecimal("4"), null))));
        purchaseOrderService.confirm(id);
        purchaseOrderService.receive(id, null);
        BizException openInbound = assertThrows(BizException.class, () -> purchaseOrderService.cancel(id));
        assertEquals(2014, openInbound.getCode());
        assertTrue(openInbound.getMessage().contains("未完成的入库单"));

        long inboundId = jdbc.queryForObject(
                "select id from inbound_order where purchase_order_id = ? order by id desc limit 1",
                Long.class, id);
        inboundOrderService.cancel(inboundId);
        purchaseOrderService.cancel(id);
        assertEquals("CANCELLED", purchaseOrderService.detail(id).get("status"));
    }

    @Test
    @DisplayName("供应商编码重复被拒，已被采购单引用的供应商不能删除")
    void supplierGuards() {
        String code = "PC" + System.nanoTime();
        supplierService.create(new SupplierService.SupplierRequest(code, "重复编码测试", "MATERIAL",
                null, null, null, null, null, null, 3, "B", true, null));
        BizException duplicate = assertThrows(BizException.class,
                () -> supplierService.create(new SupplierService.SupplierRequest(code, "重复编码测试2", "MATERIAL",
                        null, null, null, null, null, null, 3, "B", true, null)));
        assertEquals(2006, duplicate.getCode());

        purchaseOrderService.create(order(List.of(
                new PurchaseOrderService.ItemRequest(materialId, new BigDecimal("1"), new BigDecimal("1"), null))));
        BizException used = assertThrows(BizException.class, () -> supplierService.delete(supplierId));
        assertEquals(2004, used.getCode());
    }

    private PurchaseOrderService.OrderRequest order(List<PurchaseOrderService.ItemRequest> items) {
        return new PurchaseOrderService.OrderRequest(supplierId, warehouseId, LocalDate.now(),
                LocalDate.now().plusDays(5), false, "单元测试采购单", items);
    }
}
