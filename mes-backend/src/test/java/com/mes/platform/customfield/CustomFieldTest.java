package com.mes.platform.customfield;

import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.masterdata.MasterDataService;
import com.mes.production.ProductionService;
import com.mes.sales.SalesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 低代码自定义字段：字段定义、类型校验、销售订单值存取与停用字段保留。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomFieldTest {

    @Autowired
    private CustomFieldService customFields;

    @Autowired
    private SalesService sales;

    @Autowired
    private MasterDataService masterData;

    @Autowired
    private ProductionService production;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("销售订单自定义字段：定义、保存、读取、校验与停用保留")
    void salesOrderCustomFields() {
        customFields.create(new CustomFieldService.FieldRequest(
                "sales_order", "project_code", "项目编号", "TEXT", true, null, null, 10, true));
        customFields.create(new CustomFieldService.FieldRequest(
                "sales_order", "budget", "预算", "NUMBER", false, null, null, 20, true));
        customFields.create(new CustomFieldService.FieldRequest(
                "sales_order", "region", "区域", "SELECT", false,
                List.of("华东", "华南"), null, 30, true));

        long customerId = jdbc.queryForObject(
                "select id from customer order by id limit 1", Long.class);
        long productId = jdbc.queryForObject(
                "select id from product order by id limit 1", Long.class);

        long orderId = sales.create(new SalesService.OrderRequest(
                customerId, LocalDate.now(), LocalDate.now().plusDays(7), "自定义字段测试",
                List.of(new SalesService.OrderItem(productId, BigDecimal.ONE, BigDecimal.TEN)),
                "NORMAL", null, null, null, null,
                Map.of("project_code", "P-001", "budget", "1234.5", "region", "华东")));

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) sales.detail(orderId).get("customFields");
        assertEquals("P-001", saved.get("project_code"));
        assertEquals(0, new BigDecimal(String.valueOf(saved.get("budget")))
                .compareTo(new BigDecimal("1234.5")));
        assertEquals("华东", saved.get("region"));

        // 列表按自定义字段筛选：文本与数字都走白名单字段
        PageResult textMatch = sales.list("", "", 1, 20, "project_code", "P-001");
        assertEquals(1, textMatch.total());
        PageResult textMiss = sales.list("", "", 1, 20, "project_code", "P-999");
        assertEquals(0, textMiss.total());
        PageResult numberMatch = sales.list("", "", 1, 20, "budget", "1234.5");
        assertEquals(1, numberMatch.total());

        // 必填缺失、数字非法、下拉越界都在服务端拦住
        assertThrows(BizException.class, () -> customFields.mergeJson(
                "sales_order", "{}", Map.of("budget", "10"), true));
        assertThrows(BizException.class, () -> customFields.mergeJson(
                "sales_order", "{}", Map.of("project_code", "P-002", "budget", "abc"), false));
        assertThrows(BizException.class, () -> customFields.mergeJson(
                "sales_order", "{}", Map.of("project_code", "P-002", "region", "华北"), false));

        // 字段键重复不允许
        assertThrows(BizException.class, () -> customFields.create(new CustomFieldService.FieldRequest(
                "sales_order", "project_code", "重复键", "TEXT", false, null, null, 40, true)));

        // 停用字段后，修改时没提交的旧值保留
        long regionId = customFields.definitions("sales_order", true).stream()
                .filter(field -> "region".equals(field.fieldKey()))
                .findFirst().orElseThrow().id();
        customFields.disable(regionId);
        String merged = customFields.mergeJson("sales_order",
                "{\"project_code\":\"P-001\",\"region\":\"华东\"}",
                Map.of("project_code", "P-002"), false);
        Map<String, Object> updated = customFields.readJson(merged);
        assertEquals("P-002", updated.get("project_code"));
        assertEquals("华东", updated.get("region"));
    }

    @Test
    @DisplayName("自定义字段扩展到物料档案、生产工单与销售订单明细")
    void moreEntities() {
        customFields.create(new CustomFieldService.FieldRequest(
                "product", "drawing_no", "图号", "TEXT", false, null, null, 10, true));
        long productId = masterData.createProduct(new MasterDataService.ProductRequest(
                "PD-CF-1", "测试物料", "规格", "FINISHED", "件", BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, false, "NONE", null, "MAKE", 1,
                Map.of("drawing_no", "D-001")));
        List<Map<String, Object>> products = masterData.products("PD-CF-1", "", "drawing_no", "D-001");
        assertEquals(1, products.size());
        assertEquals("D-001", customFieldValue(products.get(0), "drawing_no"));

        customFields.create(new CustomFieldService.FieldRequest(
                "work_order", "line_code", "产线", "TEXT", false, null, null, 10, true));
        long workOrderId = production.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("2"), LocalDate.now(), LocalDate.now().plusDays(3),
                "NORMAL", null, "自定义字段测试", null, Map.of("line_code", "L-1")));
        assertEquals("L-1", customFieldValue(production.detail(workOrderId), "line_code"));
        PageResult workOrders = production.list("", "", 1, 20, false, "line_code", "L-1");
        assertEquals(1, workOrders.total());

        customFields.create(new CustomFieldService.FieldRequest(
                "sales_order_item", "item_note", "行备注", "TEXT", false, null, null, 10, true));
        long customerId = jdbc.queryForObject(
                "select id from customer order by id limit 1", Long.class);
        long orderId = sales.create(new SalesService.OrderRequest(
                customerId, LocalDate.now(), LocalDate.now().plusDays(7), "明细自定义字段测试",
                List.of(new SalesService.OrderItem(productId, BigDecimal.ONE, BigDecimal.TEN,
                        Map.of("item_note", "加急包装"))),
                "NORMAL", null, null, null, null, Map.of()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sales.detail(orderId).get("items");
        assertEquals("加急包装", customFieldValue(items.get(0), "item_note"));

        // 列表把明细字段合并进订单行，供前端做可配置列与导出
        Map<String, Object> listRecord = null;
        for (Object row : sales.list("", "", 1, 20, null, null).records()) {
            Map<String, Object> map = (Map<String, Object>) row;
            if (((Number) map.get("id")).longValue() == orderId) {
                listRecord = map;
                break;
            }
        }
        assertNotNull(listRecord);
        @SuppressWarnings("unchecked")
        Map<String, Object> itemSummary =
                (Map<String, Object>) listRecord.get("itemCustomFields");
        assertEquals("加急包装", itemSummary.get("item_note"));

        // 按明细字段筛选：item: 前缀表示「任意一行满足」
        assertTrue(sales.list("", "", 1, 20, "item:item_note", "加急包装").total() >= 1);
        assertEquals(0, sales.list("", "", 1, 20, "item:item_note", "不存在的备注").total());
    }

    @Test
    @DisplayName("字段级权限：按角色控制可见与可编辑")
    void roleScopedFields() {
        try {
            login("ADMIN");
            customFields.create(new CustomFieldService.FieldRequest(
                    "sales_order", "internal_note", "内部备注", "TEXT", true, null, null, 10, true,
                    null, List.of("SALES", "ADMIN"), List.of("ADMIN")));
            CustomFieldService.FormField admin =
                    customFields.formDefinition("sales_order", "internal_note");
            assertTrue(admin != null && admin.editable());
            long customerId = jdbc.queryForObject(
                    "select id from customer order by id limit 1", Long.class);
            long productId = jdbc.queryForObject(
                    "select id from product order by id limit 1", Long.class);
            long orderId = sales.create(new SalesService.OrderRequest(
                    customerId, LocalDate.now(), LocalDate.now().plusDays(3), "字段权限测试",
                    List.of(new SalesService.OrderItem(productId, BigDecimal.ONE, BigDecimal.TEN)),
                    "NORMAL", null, null, null, null,
                    Map.of("internal_note", "机密")));

            login("SALES");
            CustomFieldService.FormField salesField =
                    customFields.formDefinition("sales_order", "internal_note");
            assertTrue(salesField != null);
            assertFalse(salesField.editable());
            // 只读角色传了值也不落库，原值保留
            String merged = customFields.mergeJson("sales_order",
                    "{\"internal_note\":\"原值\"}", Map.of("internal_note", "改值"), false);
            assertEquals("原值", customFields.readJson(merged).get("internal_note"));
            // 可见角色能从详情读回字段值
            assertEquals("机密", customFieldValue(sales.detail(orderId), "internal_note"));

            login("WAREHOUSE");
            assertTrue(customFields.formDefinition("sales_order", "internal_note") == null);
            // 不可见字段不参与必填校验
            assertEquals("{}", customFields.mergeJson("sales_order", "{}", Map.of(), true));
            // 详情/列表也不能带出不可见字段的值
            assertNull(customFieldValue(sales.detail(orderId), "internal_note"));
            assertTrue(customFields.visibleJson("sales_order",
                    "{\"internal_note\":\"机密\"}").isEmpty());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void login(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @SuppressWarnings("unchecked")
    private String customFieldValue(Map<String, Object> row, String fieldKey) {
        Map<String, Object> values = (Map<String, Object>) row.get("customFields");
        Object value = values == null ? null : values.get(fieldKey);
        return value == null ? null : String.valueOf(value);
    }
}
