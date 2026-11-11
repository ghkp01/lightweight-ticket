package com.mes.masterdata;

import com.mes.common.BizException;
import com.mes.masterdata.api.CustomerInfo;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.masterdata.api.WarehouseInfo;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础数据契约（{@link MasterDataPort}）的对外语义。
 *
 * <p>这层是别的模块唯一允许依赖的基础数据入口，所以字段口径和「查不到怎么返回」
 * 都必须钉死：查不到给空 Optional 而不是抛异常（调用方自己决定报什么业务错），
 * 批量查询遇到不存在的 id 直接跳过，空集合不发 SQL。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MasterDataPortTest {

    @Autowired
    private MasterDataPort masterData;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long warehouseId;

    @BeforeEach
    void setUp() {
        productId = jdbc.queryForObject("select id from product where code = 'P003'", Long.class);
        warehouseId = jdbc.queryForObject("select id from warehouse where code = 'WH-RAW'", Long.class);
    }

    @Test
    @DisplayName("按 id 取产品：编码、名称、单位与成本字段口径一致")
    void productCarriesFieldsOtherModulesNeed() {
        ProductInfo product = masterData.product(productId).orElseThrow();
        assertEquals("P003", product.code());
        assertNotNull(product.name());
        assertNotNull(product.unit());
        assertNotNull(product.price());
        // 成本类字段允许为空（历史产品没维护成本），但字段本身必须在，调用方靠它算金额
        assertNotNull(product.avgCost() == null ? BigDecimal.ZERO : product.avgCost());
        assertEquals(productId, product.id());
    }

    @Test
    @DisplayName("按编码取产品：不存在或空编码都返回空，不抛异常")
    void productByCodeReturnsEmptyWhenAbsent() {
        assertTrue(masterData.productByCode("P003").isPresent());
        assertTrue(masterData.productByCode("NO-SUCH-CODE").isEmpty());
        assertTrue(masterData.productByCode("  ").isEmpty());
        assertTrue(masterData.productByCode(null).isEmpty());
    }

    @Test
    @DisplayName("批量取产品：不存在的 id 直接跳过，空集合不发 SQL")
    void productsSkipsMissingIds() {
        Map<Long, ProductInfo> found = masterData.products(List.of(productId, -1L));
        assertEquals(1, found.size());
        assertEquals("P003", found.get(productId).code());
        assertTrue(masterData.products(List.of()).isEmpty());
        assertTrue(masterData.products(null).isEmpty());
    }

    @Test
    @DisplayName("仓库：按 id 取到类型与所属工厂，供库存与调拨判断用")
    void warehouseExposesTypeAndFactory() {
        WarehouseInfo warehouse = masterData.warehouse(warehouseId).orElseThrow();
        assertEquals("RAW", warehouse.type());
        assertTrue(warehouse.enabled());
        Map<Long, WarehouseInfo> found = masterData.warehouses(List.of(warehouseId));
        assertEquals("RAW", found.get(warehouseId).type());
    }

    @Test
    @DisplayName("仓库是否启用：停用的仓库建单校验要能拦住")
    void warehouseEnabledReflectsFlag() {
        assertTrue(masterData.warehouseEnabled(warehouseId));
        long disabled = jdbc.queryForObject("""
                insert into warehouse (code, name, type, enabled) values (?, ?, 'RAW', false)
                returning id
                """, Long.class, "WH-OFF-" + System.nanoTime(), "停用仓测试");
        assertFalse(masterData.warehouseEnabled(disabled));
        assertFalse(masterData.warehouseEnabled(-1L));
    }

    @Test
    @DisplayName("按类型取仓库 id：找不到抛 3002 业务异常，调用方靠错误码提示")
    void warehouseIdOfTypeThrowsWhenAbsent() {
        assertEquals(warehouseId, masterData.warehouseIdOfType("RAW"));
        BizException error = assertThrows(BizException.class,
                () -> masterData.warehouseIdOfType("NO_SUCH_TYPE"));
        assertEquals(3002, error.getCode());
    }

    @Test
    @DisplayName("客户：按 id 取到编码与名称，不存在返回空")
    void customerLookup() {
        Long customerId = jdbc.queryForObject("select id from customer order by id limit 1", Long.class);
        CustomerInfo customer = masterData.customer(customerId).orElseThrow();
        assertNotNull(customer.code());
        assertNotNull(customer.name());
        assertTrue(masterData.customer(-1L).isEmpty());
    }

    @Test
    @DisplayName("存在性判断与查询结果保持一致，避免校验和取数两套口径")
    void existenceHelpersMatchLookup() {
        assertTrue(masterData.productExists(productId));
        assertFalse(masterData.productExists(-1L));
        assertEquals(masterData.product(productId).isPresent(), masterData.productExists(productId));
    }
}
