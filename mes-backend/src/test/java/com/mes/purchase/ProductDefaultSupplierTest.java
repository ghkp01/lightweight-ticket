package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.masterdata.MasterDataService;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产品默认供应商：采购单没指定供应商时按明细物料带出。
 *
 * <p>兜底只在「新单没选供应商」时生效；明细指向不同供应商、或者都没维护默认供应商时
 * 宁可报错也不替用户猜 —— 采购单只有一个供应商字段，猜错比报错更麻烦。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductDefaultSupplierTest {

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private MasterDataService masterData;

    @Autowired
    private JdbcTemplate jdbc;

    private long supplierA;
    private long supplierB;
    private long warehouseId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        supplierA = createSupplier("DSA" + suffix, "默认供应商甲" + suffix);
        supplierB = createSupplier("DSB" + suffix, "默认供应商乙" + suffix);
        warehouseId = jdbc.queryForObject("select id from warehouse where code = 'WH-RAW'", Long.class);
    }

    @Test
    @DisplayName("明细只有一个默认供应商：不手选供应商也能建单，按默认值落库")
    void fallsBackToProductDefaultSupplier() {
        long material = newProduct(supplierA);
        long id = purchaseOrderService.create(request(null, List.of(item(material))));

        assertEquals(supplierA, supplierIdOf(id));
    }

    @Test
    @DisplayName("显式选了供应商：以手工选的为准，不被产品默认值覆盖")
    void explicitSupplierWins() {
        long material = newProduct(supplierA);
        long id = purchaseOrderService.create(request(supplierB, List.of(item(material))));

        assertEquals(supplierB, supplierIdOf(id));
    }

    @Test
    @DisplayName("明细的默认供应商不一致：要求手工选，不替用户猜")
    void conflictingDefaultsRequireManualChoice() {
        long materialA = newProduct(supplierA);
        long materialB = newProduct(supplierB);

        BizException ex = assertThrows(BizException.class, () -> purchaseOrderService.create(
                request(null, List.of(item(materialA), item(materialB)))));
        assertTrue(ex.getMessage().contains("不一致"), "实际提示：" + ex.getMessage());
    }

    @Test
    @DisplayName("物料没维护默认供应商：提示手选，而不是建出一张没有供应商的单")
    void noDefaultSupplierAtAll() {
        long material = newProduct(null);

        BizException ex = assertThrows(BizException.class,
                () -> purchaseOrderService.create(request(null, List.of(item(material)))));
        assertTrue(ex.getMessage().contains("请选择供应商"), "实际提示：" + ex.getMessage());
    }

    @Test
    @DisplayName("产品列表接口要带上 defaultSupplierId，前端才能自动带出")
    void productListExposesDefaultSupplier() {
        long material = newProduct(supplierA);

        assertEquals(supplierA, masterData.product(material).orElseThrow().defaultSupplierId());
    }

    private long createSupplier(String code, String name) {
        return supplierService.create(new SupplierService.SupplierRequest(
                code, name, "MATERIAL", "测试联系人",
                "13800009999", null, null, null, "月结 30 天", 5, "A", true, null));
    }

    private long newProduct(Long defaultSupplierId) {
        return masterData.createProduct(new MasterDataService.ProductRequest(
                "DS" + ThreadLocalRandom.current().nextInt(100000, 999999),
                "默认供应商测试件", "DS-T", "RAW", "件",
                new BigDecimal("10"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, "NONE", defaultSupplierId));
    }

    private PurchaseOrderService.ItemRequest item(long productId) {
        return new PurchaseOrderService.ItemRequest(productId, new BigDecimal("2"), new BigDecimal("3"), null);
    }

    private PurchaseOrderService.OrderRequest request(Long supplierId,
                                                      List<PurchaseOrderService.ItemRequest> items) {
        return new PurchaseOrderService.OrderRequest(supplierId, warehouseId, LocalDate.now(),
                LocalDate.now().plusDays(5), false, "默认供应商测试", items, null);
    }

    private long supplierIdOf(long orderId) {
        return ((Number) purchaseOrderService.detail(orderId).get("supplierId")).longValue();
    }
}
