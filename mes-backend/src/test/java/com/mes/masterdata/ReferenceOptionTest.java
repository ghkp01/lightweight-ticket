package com.mes.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.mes.purchase.SupplierService;
import com.mes.process.ProcessService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用选择接口：关键字搜索 + 条数上限，避免整表返回。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReferenceOptionTest {

    @Autowired
    private MasterDataService service;
    @Autowired
    private SupplierService supplierService;
    @Autowired
    private ProcessService processService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("客户/物料选项按关键字过滤且不超过上限")
    void optionsRespectKeywordAndLimit() {
        List<Map<String, Object>> customers = service.customerOptions(null, 20);
        assertFalse(customers.isEmpty(), "测试库应有客户数据");
        assertTrue(customers.size() <= 20);
        String code = String.valueOf(customers.get(0).get("code"));
        List<Map<String, Object>> matched = service.customerOptions(code, 5);
        assertTrue(matched.stream().anyMatch(row -> code.equals(String.valueOf(row.get("code")))));

        List<Map<String, Object>> products = service.productOptions(null, 1, false);
        assertEquals(1, products.size());
        assertEquals(true, products.get(0).containsKey("price"));
        assertEquals(true, products.get(0).containsKey("defaultSupplierId"));
        assertEquals(true, products.get(0).containsKey("traceMode"));
        // routingOnly：返回的每个产品都必须确实配置了工艺路线
        List<Long> routedIds = jdbc.queryForList(
                "select distinct product_id from product_routing", Long.class);
        assertTrue(service.productOptions(null, 20, true).stream()
                .allMatch(row -> routedIds.contains(((Number) row.get("id")).longValue())));
    }

    @Test
    @DisplayName("工序选项带出默认工时/工价并按关键字过滤")
    void processOptionsRespectKeywordAndLimit() {
        List<Map<String, Object>> all = processService.options(null, 5);
        assertFalse(all.isEmpty(), "测试库应有工序数据");
        assertTrue(all.size() <= 5);
        Map<String, Object> first = all.get(0);
        assertEquals(true, first.containsKey("defaultMinutes"));
        assertEquals(true, first.containsKey("defaultPrice"));
        assertEquals(true, first.containsKey("needInspection"));

        String code = String.valueOf(first.get("code"));
        List<Map<String, Object>> matched = processService.options(code, 5);
        assertTrue(matched.stream().anyMatch(row -> code.equals(String.valueOf(row.get("code")))));
    }

    @Test
    @DisplayName("仓库选项按类型过滤且不超过上限")
    void warehouseOptionsRespectTypeAndLimit() {
        List<Map<String, Object>> all = service.warehouseOptions(null, null, 5);
        assertFalse(all.isEmpty(), "测试库应有仓库数据");
        assertTrue(all.size() <= 5);
        assertEquals(true, all.get(0).containsKey("type"));

        List<Map<String, Object>> raw = service.warehouseOptions(null, "RAW", 10);
        assertTrue(raw.stream().allMatch(row -> "RAW".equals(String.valueOf(row.get("type")))));
    }

    @Test
    @DisplayName("供应商选项按关键字过滤，limit 生效且带默认交期")
    void supplierOptionsRespectKeywordAndLimit() {
        long id = supplierService.create(new SupplierService.SupplierRequest(
                "SUP-OPT-001", "引用选项验证供应商", "MATERIAL", null, null, null, null,
                null, null, 5, null, true, null));
        try {
            List<Map<String, Object>> matched = supplierService.options("SUP-OPT-001", 5);
            assertEquals(1, matched.size());
            assertEquals(true, matched.get(0).containsKey("leadDays"));
            assertTrue(supplierService.options(null, 1).size() <= 1);
        } finally {
            supplierService.delete(id);
        }
    }
}
