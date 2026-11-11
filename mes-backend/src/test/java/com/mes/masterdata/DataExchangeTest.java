package com.mes.masterdata;

import com.mes.platform.customfield.CustomFieldService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础数据导入导出：模板表头、产品新增/更新、错误行回报、BOM/工艺路线/期初库存导入、导出回读。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DataExchangeTest {

    @Autowired
    private DataExchangeService service;

    @Autowired
    private CustomFieldService customFields;

    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("模板带表头与说明行")
    void templateHasHeaders() {
        String csv = service.template("product");
        assertTrue(csv.startsWith("产品编码,产品名称"));
        assertTrue(csv.contains("单件管理"));
        assertThrows(com.mes.common.BizException.class, () -> service.template("unknown"));
    }

    @Test
    @DisplayName("产品导入：新增、按编码更新、坏行只回报不影响其他行")
    void importProducts() {
        String code = "DX" + suffix;
        String csv = """
                产品编码,产品名称,规格,类别,单位,销售价,成本,安全库存,损耗率,单件管理
                %s,导入测试件,10x20,成品,件,12.5,8,3,0.02,否
                %s-RAW,导入测试原料,,原料,kg,5,4,100,0,否
                BAD%s,坏行, ,不存在的类别,件,1,1,0,0,否
                """.formatted(code, code, suffix);

        DataExchangeService.ImportResult preview = service.importCsv("product", csv, true);
        assertEquals(3, preview.total());
        assertEquals(1, preview.failed());
        assertEquals(0, count("select count(*) from product where code = ?", code));

        DataExchangeService.ImportResult result = service.importCsv("product", csv, false);
        assertEquals(2, result.success());
        assertEquals(2, result.created());
        assertEquals(1, result.errors().size());
        assertTrue(String.valueOf(result.errors().get(0).get("message")).contains("类别"));
        assertEquals("FINISHED",
                jdbc.queryForObject("select category from product where code = ?", String.class, code));
        assertEquals("RAW",
                jdbc.queryForObject("select category from product where code = ?", String.class, code + "-RAW"));
        assertTrue(jdbc.queryForObject("select price from product where code = ?",
                BigDecimal.class, code).compareTo(new BigDecimal("12.5")) == 0);

        // 再导一次同一份（去掉坏行）应走更新
        String update = """
                产品编码,产品名称,规格,类别,单位,销售价,成本,安全库存,损耗率,单件管理
                %s,改名后的件,10x20,成品,件,20,9,4,0.05,是
                """.formatted(code);
        DataExchangeService.ImportResult second = service.importCsv("product", update, false);
        assertEquals(1, second.updated());
        assertEquals("改名后的件",
                jdbc.queryForObject("select name from product where code = ?", String.class, code));
        assertEquals(true,
                jdbc.queryForObject("select serial_managed from product where code = ?", Boolean.class, code));
    }

    @Test
    @DisplayName("BOM 与工艺路线导入：父件子件都要存在，重复导入按更新处理")
    void importBomAndRouting() {
        String parent = "DXP" + suffix;
        String child = "DXC" + suffix;
        String products = """
                产品编码,产品名称,规格,类别,单位,销售价,成本,安全库存,损耗率,单件管理
                %s,父件,,成品,件,10,5,0,0,否
                %s,子件,,原料,个,1,1,0,0,否
                """.formatted(parent, child);
        service.importCsv("product", products, false);

        String bom = """
                父件编码,子件编码,用量,工序号,替代组,是否关键件,备注
                %s,%s,2.5,1,,是,装配用
                %s,缺少的编码,1,1,,否,
                """.formatted(parent, child, parent);
        DataExchangeService.ImportResult bomResult = service.importCsv("bom", bom, false);
        assertEquals(1, bomResult.success());
        assertEquals(1, bomResult.failed());
        assertTrue(String.valueOf(bomResult.errors().get(0).get("message")).contains("不存在"));

        Map<String, Object> line = jdbc.queryForMap("""
                select b.qty, b.is_key, b.remark from bom_item b
                join bom h on h.id = b.bom_id
                join product p on p.id = h.product_id
                join product m on m.id = b.material_id
                where p.code = ? and m.code = ?
                """, parent, child);
        assertTrue(new BigDecimal("2.5").compareTo((BigDecimal) line.get("qty")) == 0);
        assertEquals(true, line.get("is_key"));

        String processCode = jdbc.queryForObject("select code from process order by id limit 1", String.class);
        String routing = """
                产品编码,工序号,工序编码,单件工时,计件单价,是否检验,备注
                %s,1,%s,1.5,1.2,否,首道
                """.formatted(parent, processCode);
        DataExchangeService.ImportResult routingResult = service.importCsv("routing", routing, false);
        assertEquals(1, routingResult.success());
        assertEquals(1, count("""
                select count(*) from product_routing r join product p on p.id = r.product_id
                where p.code = ? and r.seq = 1
                """, parent));
    }

    @Test
    @DisplayName("期初库存导入后库存与流水一致")
    void importOpeningInventory() {
        String code = "DXI" + suffix;
        service.importCsv("product", """
                产品编码,产品名称,规格,类别,单位,销售价,成本,安全库存,损耗率,单件管理
                %s,期初测试料,,原料,kg,3,2,0,0,否
                """.formatted(code), false);
        String warehouseCode = jdbc.queryForObject("select code from warehouse order by id limit 1", String.class);
        long warehouseId = jdbc.queryForObject("select id from warehouse where code = ?",
                Long.class, warehouseCode);

        String csv = """
                物料编码,仓库编码,数量,批次号
                %s,%s,50,%s
                """.formatted(code, warehouseCode, "B" + suffix);
        DataExchangeService.ImportResult result = service.importCsv("inventory", csv, false);
        assertEquals(1, result.success());

        long productId = jdbc.queryForObject("select id from product where code = ?", Long.class, code);
        BigDecimal qty = jdbc.queryForObject("""
                select qty from inventory where product_id = ? and warehouse_id = ?
                """, BigDecimal.class, productId, warehouseId);
        assertTrue(new BigDecimal("50").compareTo(qty) == 0);
        assertEquals(1, count("""
                select count(*) from stock_txn where product_id = ? and ref_type = 'OPENING'
                """, productId));
        assertEquals(1, count("""
                select count(*) from inventory_batch where product_id = ? and batch_no = ?
                """, productId, "B" + suffix));
    }

    @Test
    @DisplayName("产品导入导出带出自定义字段列")
    void productCustomFieldsRoundTrip() {
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("tester", null,
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            customFields.create(new CustomFieldService.FieldRequest(
                    "product", "drawing_no", "图号", "TEXT", false, null, null, 10, true));
            String code = "DXF" + suffix;
            String csv = """
                    产品编码,产品名称,规格,类别,单位,销售价,成本,安全库存,损耗率,单件管理,图号（自定义）
                    %s,自定义字段件,,成品,件,10,6,0,0,否,D-%s
                    """.formatted(code, suffix);
            DataExchangeService.ImportResult result = service.importCsv("product", csv, false);
            assertEquals(1, result.success());
            assertEquals("D-" + suffix, jdbc.queryForObject(
                    "select ext_data->>'drawing_no' from product where code = ?", String.class, code));

            String exported = service.export("product");
            assertTrue(exported.contains("图号（自定义）"));
            assertTrue(exported.contains("D-" + suffix));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("导出能看到导入的数据，表头与模板一致")
    void exportRoundTrip() {
        String code = "DXE" + suffix;
        service.importCsv("customer", """
                客户编码,客户名称,联系人,电话,地址,等级
                %s,导出测试客户,张三,13800000000,某地,B
                """.formatted(code), false);
        String csv = service.export("customer");
        assertTrue(csv.startsWith("客户编码,客户名称"));
        assertTrue(csv.contains(code));
        assertTrue(csv.contains("导出测试客户"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select level from customer where code = ?", code);
        assertEquals("B", rows.get(0).get("level"));
    }
}
