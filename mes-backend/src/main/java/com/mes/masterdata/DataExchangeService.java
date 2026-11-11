package com.mes.masterdata;

import com.mes.common.BizException;
import com.mes.inventory.api.InventoryPort;
import com.mes.platform.customfield.CustomFieldService;
import com.mes.purchase.api.SupplierDirectoryPort;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基础数据导入导出：产品、客户、供应商、BOM、工艺路线、期初库存。
 * 统一走 CSV（UTF-8 带 BOM，Excel 可直接打开），不引入 Excel 依赖。
 * 导入按行校验、逐行入库，失败行原样回报行号与原因，不影响其他行。
 */
@Service
public class DataExchangeService {

    public static final Set<String> TYPES = Set.of("product", "customer", "supplier", "bom", "routing", "inventory");

    private static final List<String> PRODUCT_HEADERS = List.of(
            "产品编码", "产品名称", "规格", "类别", "单位", "销售价", "成本", "安全库存", "损耗率",
            "单件管理");
    private static final List<String> CUSTOMER_HEADERS = List.of(
            "客户编码", "客户名称", "联系人", "电话", "地址", "等级");
    private static final List<String> SUPPLIER_HEADERS = List.of(
            "供应商编码", "供应商名称", "类型", "联系人", "电话", "邮箱", "地址", "税号",
            "账期", "信用天数", "交期天数", "评级");
    private static final List<String> BOM_HEADERS = List.of(
            "父件编码", "子件编码", "用量", "工序号", "替代组", "是否关键件", "备注");
    private static final List<String> ROUTING_HEADERS = List.of(
            "产品编码", "工序号", "工序编码", "单件工时", "计件单价", "是否检验", "备注");
    private static final List<String> INVENTORY_HEADERS = List.of(
            "物料编码", "仓库编码", "数量", "批次号");

    private final JdbcTemplate jdbcTemplate;
    private final InventoryPort inventoryService;
    private final SupplierDirectoryPort suppliers;
    private final BomService bomService;
    private final CustomFieldService customFieldService;

    public DataExchangeService(JdbcTemplate jdbcTemplate, InventoryPort inventoryService,
                               SupplierDirectoryPort suppliers, BomService bomService,
                               CustomFieldService customFieldService) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryService = inventoryService;
        this.suppliers = suppliers;
        this.bomService = bomService;
        this.customFieldService = customFieldService;
    }

    public record ImportResult(int total, int success, int failed, int created, int updated,
                               boolean preview, List<Map<String, Object>> errors) {
    }

    // ==================== 模板 ====================

    public String template(String type) {
        String normalized = normalizeType(type);
        List<String> headers = "product".equals(normalized)
                ? productHeaders()
                : headersOf(normalized);
        StringBuilder csv = new StringBuilder();
        csv.append(toCsvLine(headers)).append("\r\n");
        csv.append(sampleNote(normalized)).append("\r\n");
        return csv.toString();
    }

    /** 自定义字段的 CSV 列名：带后缀，避免和内置列同名 */
    private static final String CUSTOM_HEADER_SUFFIX = "（自定义）";

    /** 当前账号可见的产品自定义字段（按工厂/角色过滤） */
    private List<CustomFieldService.FormField> productCustomFields() {
        return customFieldService.formDefinitions("product");
    }

    private List<String> productHeaders() {
        List<String> headers = new ArrayList<>(PRODUCT_HEADERS);
        for (CustomFieldService.FormField field : productCustomFields()) {
            headers.add(field.label() + CUSTOM_HEADER_SUFFIX);
        }
        return headers;
    }

    private List<String> headersOf(String type) {
        return switch (type) {
            case "product" -> PRODUCT_HEADERS;
            case "customer" -> CUSTOMER_HEADERS;
            case "supplier" -> SUPPLIER_HEADERS;
            case "bom" -> BOM_HEADERS;
            case "routing" -> ROUTING_HEADERS;
            case "inventory" -> INVENTORY_HEADERS;
            default -> throw new BizException(1002, "不支持的数据类型：" + type);
        };
    }

    /** 模板里带一行以 # 开头的说明，导入时会被跳过 */
    private String sampleNote(String type) {
        return switch (type) {
            case "product" -> "# 类别填 RAW 原料 / SEMI 半成品 / FINISHED 成品；单件管理填 是 或 否；以 # 开头的行会被忽略";
            case "customer" -> "# 等级填 A / B / C；客户编码不能重复，重复时按编码更新";
            case "supplier" -> "# 类型填 MATERIAL 物料 / OUTSOURCE 外协 / SERVICE 服务；评级填 A / B / C";
            case "bom" -> "# 父件、子件都填产品编码；用量按 1 个父件用量填；工序号可空；是否关键件填 是 或 否";
            case "routing" -> "# 产品编码与工序编码必须已存在；工序号从 1 开始；单件工时单位是分钟";
            case "inventory" -> "# 物料编码与仓库编码必须已存在；数量填正数；期初导入是增量，同一文件请勿重复导入";
            default -> "#";
        };
    }

    // ==================== 行业模板 ====================

    /**
     * 一键初始化行业主数据：产品 → BOM → 工艺路线 → 客户。
     * 直接复用 CSV 导入的同一套校验与 upsert 逻辑，重复执行只更新不重复插入。
     * 工序编码沿用系统内置的 CUT / MACH / ASSY / QC / PACK，不额外造工序。
     */
    @Transactional
    public Map<String, Object> applyTemplate(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        String label;
        String products;
        String boms;
        String routings;
        String customers;
        switch (normalized) {
            case "electronics" -> {
                label = "电子组装";
                products = csv(PRODUCT_HEADERS,
                        "FG-ELEC-01,电控主板组件,ECB-200,FINISHED,套,899.00,520.00,0,0.02,否",
                        "SF-ELEC-01,主板半成品,ECB-100-S,SEMI,件,420.00,260.00,0,0.01,否",
                        "RM-ELEC-01,主控芯片,MCU-32BIT,RAW,个,35.00,28.00,200,0.005,否",
                        "RM-ELEC-02,PCB 基板,PCB-4L,RAW,片,18.00,12.00,300,0.01,否");
                boms = csv(BOM_HEADERS,
                        "FG-ELEC-01,SF-ELEC-01,1,30,,是,主板装配",
                        "FG-ELEC-01,RM-ELEC-02,1,10,,否,底板",
                        "SF-ELEC-01,RM-ELEC-01,2,20,,是,主控芯片",
                        "SF-ELEC-01,RM-ELEC-02,1,10,,否,PCB");
                routings = csv(ROUTING_HEADERS,
                        "FG-ELEC-01,1,ASSY,8.00,1.20,否,整机装配",
                        "FG-ELEC-01,2,QC,3.00,0.80,是,功能测试",
                        "FG-ELEC-01,3,PACK,2.00,0.50,否,包装",
                        "SF-ELEC-01,1,CUT,1.50,0.60,否,分板",
                        "SF-ELEC-01,2,MACH,4.00,1.10,否,贴片",
                        "SF-ELEC-01,3,QC,2.00,0.70,是,半成品检验");
                customers = csv(CUSTOMER_HEADERS, "C-ELEC-01,示例电子客户,张工,13800000001,深圳市南山区,A");
            }
            case "auto" -> {
                label = "汽车零部件";
                products = csv(PRODUCT_HEADERS,
                        "FG-AUTO-01,转向节总成,KNA-200,FINISHED,套,1260.00,780.00,0,0.02,否",
                        "SF-AUTO-01,转向节毛坯,KNA-100-S,SEMI,件,620.00,380.00,0,0.015,否",
                        "RM-AUTO-01,合金铸件,CAST-40CR,RAW,件,210.00,165.00,120,0.01,否",
                        "RM-AUTO-02,紧固螺栓,BLT-M12,RAW,个,1.20,0.80,2000,0.003,否");
                boms = csv(BOM_HEADERS,
                        "FG-AUTO-01,SF-AUTO-01,1,30,,是,毛坯",
                        "FG-AUTO-01,RM-AUTO-02,4,30,,否,紧固件",
                        "SF-AUTO-01,RM-AUTO-01,1,10,,是,铸件");
                routings = csv(ROUTING_HEADERS,
                        "FG-AUTO-01,1,ASSY,12.00,1.80,否,总成装配",
                        "FG-AUTO-01,2,QC,4.00,1.00,是,尺寸与力矩检验",
                        "FG-AUTO-01,3,PACK,3.00,0.60,否,包装",
                        "SF-AUTO-01,1,CUT,2.00,0.70,否,下料",
                        "SF-AUTO-01,2,MACH,9.00,2.20,否,数控加工",
                        "SF-AUTO-01,3,QC,3.00,0.90,是,毛坯检验");
                customers = csv(CUSTOMER_HEADERS, "C-AUTO-01,示例汽配客户,李工,13800000002,苏州市工业园区,A");
            }
            case "food" -> {
                label = "食品包装";
                products = csv(PRODUCT_HEADERS,
                        "FG-FOOD-01,每日坚果礼盒,NUT-BOX-30,FINISHED,盒,129.00,78.00,0,0.03,是",
                        "SF-FOOD-01,混合坚果半成品,NUT-MIX-25,SEMI,袋,42.00,26.00,0,0.02,否",
                        "RM-FOOD-01,巴旦木,ALMOND-KG,RAW,kg,52.00,38.00,80,0.01,否",
                        "RM-FOOD-02,腰果,CASHEW-KG,RAW,kg,68.00,50.00,80,0.01,否",
                        "RM-FOOD-03,礼盒包装,BOX-30,RAW,个,6.50,4.20,500,0.01,否");
                boms = csv(BOM_HEADERS,
                        "FG-FOOD-01,SF-FOOD-01,6,30,,是,混合坚果",
                        "FG-FOOD-01,RM-FOOD-03,1,30,,否,礼盒",
                        "SF-FOOD-01,RM-FOOD-01,0.5,10,,否,巴旦木",
                        "SF-FOOD-01,RM-FOOD-02,0.5,10,,否,腰果");
                routings = csv(ROUTING_HEADERS,
                        "FG-FOOD-01,1,PACK,1.50,0.40,否,装盒",
                        "FG-FOOD-01,2,QC,1.00,0.30,是,净含量与外观检验",
                        "SF-FOOD-01,1,ASSY,2.00,0.50,否,混合",
                        "SF-FOOD-01,2,QC,1.00,0.30,是,半成品检验");
                customers = csv(CUSTOMER_HEADERS, "C-FOOD-01,示例食品客户,王经理,13800000003,杭州市余杭区,A");
            }
            default -> throw new BizException(1002, "行业模板只能是 electronics / auto / food");
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(stepResult("产品", importCsv("product", products, false)));
        steps.add(stepResult("BOM", importCsv("bom", boms, false)));
        steps.add(stepResult("工艺路线", importCsv("routing", routings, false)));
        steps.add(stepResult("客户", importCsv("customer", customers, false)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template", normalized);
        result.put("label", label);
        result.put("steps", steps);
        result.put("failed", steps.stream().mapToInt(row -> ((Number) row.get("failed")).intValue()).sum());
        return result;
    }

    private Map<String, Object> stepResult(String name, ImportResult result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("total", result.total());
        row.put("success", result.success());
        row.put("failed", result.failed());
        row.put("created", result.created());
        row.put("updated", result.updated());
        row.put("errors", result.errors());
        return row;
    }

    private String csv(List<String> headers, String... rows) {
        StringBuilder content = new StringBuilder(String.join(",", headers)).append("\r\n");
        for (String row : rows) {
            content.append(row).append("\r\n");
        }
        return content.toString();
    }

    // ==================== 导出 ====================

    public String export(String type) {
        String normalized = normalizeType(type);
        return switch (normalized) {
            case "product" -> exportProducts();
            case "customer" -> exportCustomers();
            case "supplier" -> exportSuppliers();
            case "bom" -> exportBom();
            case "routing" -> exportRouting();
            case "inventory" -> exportInventory();
            default -> throw new BizException(1002, "不支持的数据类型：" + type);
        };
    }

    private String exportProducts() {
        List<CustomFieldService.FormField> customFields = productCustomFields();
        StringBuilder csv = new StringBuilder(toCsvLine(productHeaders())).append("\r\n");
        jdbcTemplate.query("""
                select code, name, spec, category, unit, price, cost, safety_stock, loss_rate,
                       serial_managed, ext_data::text as "extData"
                from product order by code
                """, rs -> {
            List<String> values = new ArrayList<>(List.of(
                    nullToEmpty(rs.getString("code")),
                    nullToEmpty(rs.getString("name")),
                    nullToEmpty(rs.getString("spec")),
                    nullToEmpty(rs.getString("category")),
                    nullToEmpty(rs.getString("unit")),
                    plain(rs.getBigDecimal("price")),
                    plain(rs.getBigDecimal("cost")),
                    plain(rs.getBigDecimal("safety_stock")),
                    plain(rs.getBigDecimal("loss_rate")),
                    rs.getBoolean("serial_managed") ? "是" : "否"));
            Map<String, Object> ext = customFieldService.readJson(rs.getString("extData"));
            for (CustomFieldService.FormField field : customFields) {
                values.add(extText(ext.get(field.fieldKey())));
            }
            csv.append(toCsvLine(values)).append("\r\n");
        });
        return csv.toString();
    }

    /** 扩展值写 CSV：布尔转是/否，空值留空 */
    private String extText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean flag) {
            return flag ? "是" : "否";
        }
        return String.valueOf(value);
    }

    private String exportCustomers() {
        StringBuilder csv = new StringBuilder(toCsvLine(CUSTOMER_HEADERS)).append("\r\n");
        jdbcTemplate.query("""
                select code, name, contact, phone, address, level from customer order by code
                """, rs -> {
            csv.append(toCsvLine(List.of(
                    nullToEmpty(rs.getString("code")),
                    nullToEmpty(rs.getString("name")),
                    nullToEmpty(rs.getString("contact")),
                    nullToEmpty(rs.getString("phone")),
                    nullToEmpty(rs.getString("address")),
                    nullToEmpty(rs.getString("level"))))).append("\r\n");
        });
        return csv.toString();
    }

    private String exportSuppliers() {
        StringBuilder csv = new StringBuilder(toCsvLine(SUPPLIER_HEADERS)).append("\r\n");
        // 供应商档案归采购：走采购的目录契约取全量，不直接读别人的表
        for (SupplierDirectoryPort.SupplierRow row : suppliers.all()) {
            csv.append(toCsvLine(List.of(
                    nullToEmpty(row.code()),
                    nullToEmpty(row.name()),
                    nullToEmpty(row.category()),
                    nullToEmpty(row.contact()),
                    nullToEmpty(row.phone()),
                    nullToEmpty(row.email()),
                    nullToEmpty(row.address()),
                    nullToEmpty(row.taxNo()),
                    nullToEmpty(row.paymentTerm()),
                    row.creditDays() == null ? "" : String.valueOf(row.creditDays()),
                    row.leadDays() == null ? "" : String.valueOf(row.leadDays()),
                    nullToEmpty(row.rating())))).append("\r\n");
        }
        return csv.toString();
    }

    private String exportBom() {
        StringBuilder csv = new StringBuilder(toCsvLine(BOM_HEADERS)).append("\r\n");
        jdbcTemplate.query("""
                select p.code as parent_code, m.code as material_code, b.qty, b.operation_seq,
                       b.substitute_group, b.is_key, b.remark
                from bom_item b
                join bom h on h.id = b.bom_id
                join product p on p.id = h.product_id
                join product m on m.id = b.material_id
                where h.status = 'ACTIVE'
                order by p.code, b.id
                """, rs -> {
            csv.append(toCsvLine(List.of(
                    nullToEmpty(rs.getString("parent_code")),
                    nullToEmpty(rs.getString("material_code")),
                    plain(rs.getBigDecimal("qty")),
                    rs.getObject("operation_seq") == null ? "" : String.valueOf(rs.getInt("operation_seq")),
                    nullToEmpty(rs.getString("substitute_group")),
                    rs.getBoolean("is_key") ? "是" : "否",
                    nullToEmpty(rs.getString("remark"))))).append("\r\n");
        });
        return csv.toString();
    }

    private String exportRouting() {
        StringBuilder csv = new StringBuilder(toCsvLine(ROUTING_HEADERS)).append("\r\n");
        jdbcTemplate.query("""
                select p.code as product_code, r.seq, pr.code as process_code,
                       r.minutes_per_piece, r.unit_price, r.need_inspection, r.remark
                from product_routing r
                join product p on p.id = r.product_id
                join rm_process pr on pr.id = r.process_id
                order by p.code, r.seq
                """, rs -> {
            csv.append(toCsvLine(List.of(
                    nullToEmpty(rs.getString("product_code")),
                    String.valueOf(rs.getInt("seq")),
                    nullToEmpty(rs.getString("process_code")),
                    plain(rs.getBigDecimal("minutes_per_piece")),
                    plain(rs.getBigDecimal("unit_price")),
                    rs.getBoolean("need_inspection") ? "是" : "否",
                    nullToEmpty(rs.getString("remark"))))).append("\r\n");
        });
        return csv.toString();
    }

    private String exportInventory() {
        StringBuilder csv = new StringBuilder(toCsvLine(INVENTORY_HEADERS)).append("\r\n");
        jdbcTemplate.query("""
                select p.code as product_code, w.code as warehouse_code, i.qty,
                       coalesce((select string_agg(distinct b.batch_no, ' / ')
                                 from rm_inventory_batch b
                                 where b.product_id = i.product_id and b.warehouse_id = i.warehouse_id), '') as batch_no
                from rm_inventory i
                join product p on p.id = i.product_id
                join warehouse w on w.id = i.warehouse_id
                where i.qty <> 0
                order by p.code, w.code
                """, rs -> {
            csv.append(toCsvLine(List.of(
                    nullToEmpty(rs.getString("product_code")),
                    nullToEmpty(rs.getString("warehouse_code")),
                    plain(rs.getBigDecimal("qty")),
                    nullToEmpty(rs.getString("batch_no"))))).append("\r\n");
        });
        return csv.toString();
    }

    // ==================== 导入 ====================

    public ImportResult importCsv(String type, String content, boolean preview) {
        String normalized = normalizeType(type);
        if (content == null || content.isBlank()) {
            throw new BizException(1002, "导入文件是空的，请先下载模板填写");
        }
        List<List<String>> rows = readCsv(content);
        if (rows.isEmpty()) {
            throw new BizException(1002, "导入文件没有表头，请先下载模板");
        }
        List<String> header = rows.get(0);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i).trim(), i);
        }
        for (String required : headersOf(normalized)) {
            if (!index.containsKey(required)) {
                throw new BizException(1002, "缺少必需列：" + required + "，请按模板表头填写");
            }
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        int total = 0;
        int success = 0;
        int created = 0;
        int updated = 0;
        for (int line = 1; line < rows.size(); line++) {
            List<String> row = rows.get(line);
            String first = row.isEmpty() ? "" : row.get(0).trim();
            if (row.isEmpty() || (first.isEmpty() && row.stream().allMatch(String::isBlank)) || first.startsWith("#")) {
                continue;
            }
            total++;
            try {
                int changed = switch (normalized) {
                    case "product" -> importProduct(row, index, preview);
                    case "customer" -> importCustomer(row, index, preview);
                    case "supplier" -> importSupplier(row, index, preview);
                    case "bom" -> importBom(row, index, preview);
                    case "routing" -> importRouting(row, index, preview);
                    case "inventory" -> importInventory(row, index, preview);
                    default -> 0;
                };
                success++;
                if (changed == 1) {
                    created++;
                } else if (changed == 2) {
                    updated++;
                }
            } catch (Exception e) {
                String message = e instanceof BizException biz ? biz.getMessage() : "第 " + (line + 1) + " 行数据有误：" + e.getMessage();
                errors.add(Map.of("line", line + 1, "message", message));
            }
        }
        return new ImportResult(total, success, total - success, created, updated, preview, errors);
    }

    /** @return 1 新增，2 更新 */
    private int importProduct(List<String> row, Map<String, Integer> index, boolean preview) {
        String code = required(row, index, "产品编码");
        String name = required(row, index, "产品名称");
        String category = normalizeCategory(cell(row, index, "类别"));
        String unit = required(row, index, "单位");
        BigDecimal price = decimal(row, index, "销售价", BigDecimal.ZERO);
        BigDecimal cost = decimal(row, index, "成本", BigDecimal.ZERO);
        BigDecimal safetyStock = decimal(row, index, "安全库存", BigDecimal.ZERO);
        BigDecimal lossRate = decimal(row, index, "损耗率", BigDecimal.ZERO);
        boolean serial = yesNo(cell(row, index, "单件管理"));
        String source = upperOr(cell(row, index, "默认来源"), "");
        if (source.isEmpty()) {
            source = switch (category) {
                case "RAW", "PURCHASED" -> "BUY";
                default -> "MAKE";
            };
        }
        if (!List.of("MAKE", "BUY").contains(source)) {
            throw new BizException(1002, "默认来源只能填 MAKE（自制）或 BUY（外购）");
        }
        Integer leadDays = integer(row, index, "提前期");
        // 自定义字段列是可选的：没填的字段保持原值，不触发必填校验
        Map<String, Object> customValues = new LinkedHashMap<>();
        for (CustomFieldService.FormField field : productCustomFields()) {
            if (!field.editable()) {
                continue;
            }
            String raw = cell(row, index, field.label() + CUSTOM_HEADER_SUFFIX);
            if (!raw.isBlank()) {
                customValues.put(field.fieldKey(), raw.trim());
            }
        }
        Long exists = jdbcTemplate.query("select id from product where code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, code);
        String existingExt = exists == null ? "{}"
                : jdbcTemplate.queryForObject(
                "select ext_data::text from product where id = ?", String.class, exists);
        String extJson = customFieldService.mergeJson("product",
                existingExt == null ? "{}" : existingExt, customValues, false);
        if (preview) {
            return exists == null ? 1 : 2;
        }
        jdbcTemplate.update("""
                insert into product (code, name, spec, category, unit, price, cost, safety_stock,
                                     loss_rate, serial_managed, trace_mode, source, lead_days, ext_data)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (code) do update set
                    name = excluded.name, spec = excluded.spec, category = excluded.category,
                    unit = excluded.unit, price = excluded.price, cost = excluded.cost,
                    safety_stock = excluded.safety_stock, loss_rate = excluded.loss_rate,
                    serial_managed = excluded.serial_managed, trace_mode = excluded.trace_mode,
                    source = excluded.source, lead_days = excluded.lead_days,
                    ext_data = excluded.ext_data
                """, code, name, blankToNull(cell(row, index, "规格")), category, unit, price, cost,
                safetyStock, lossRate, serial, serial ? "SERIAL" : "NONE", source,
                leadDays == null ? 0 : leadDays, extJson);
        return exists == null ? 1 : 2;
    }

    private int importCustomer(List<String> row, Map<String, Integer> index, boolean preview) {
        String code = required(row, index, "客户编码");
        String name = required(row, index, "客户名称");
        String level = upperOr(cell(row, index, "等级"), "A");
        if (!List.of("A", "B", "C").contains(level)) {
            throw new BizException(1002, "等级只能填 A / B / C");
        }
        Long exists = jdbcTemplate.query("select id from customer where code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, code);
        if (!preview) {
            jdbcTemplate.update("""
                    insert into customer (code, name, contact, phone, address, level)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (code) do update set
                        name = excluded.name, contact = excluded.contact, phone = excluded.phone,
                        address = excluded.address, level = excluded.level
                    """, code, name, blankToNull(cell(row, index, "联系人")),
                    blankToNull(cell(row, index, "电话")), blankToNull(cell(row, index, "地址")), level);
        }
        return exists == null ? 1 : 2;
    }

    private int importSupplier(List<String> row, Map<String, Integer> index, boolean preview) {
        String code = required(row, index, "供应商编码");
        String name = required(row, index, "供应商名称");
        String category = upperOr(cell(row, index, "类型"), "MATERIAL");
        if (!List.of("MATERIAL", "OUTSOURCE", "SERVICE").contains(category)) {
            throw new BizException(1002, "供应商类型只能填 MATERIAL / OUTSOURCE / SERVICE");
        }
        String rating = upperOr(cell(row, index, "评级"), "A");
        if (!List.of("A", "B", "C").contains(rating)) {
            throw new BizException(1002, "评级只能填 A / B / C");
        }
        Integer leadDays = integer(row, index, "交期天数");
        Integer creditDays = integer(row, index, "信用天数");
        Long exists = suppliers.idByCode(code);
        if (!preview) {
            // 写供应商走采购的目录契约：编码唯一，已存在就覆盖业务字段
            suppliers.upsert(new SupplierDirectoryPort.SupplierRow(code, name, category,
                    blankToNull(cell(row, index, "联系人")), blankToNull(cell(row, index, "电话")),
                    blankToNull(cell(row, index, "邮箱")), blankToNull(cell(row, index, "地址")),
                    blankToNull(cell(row, index, "税号")), blankToNull(cell(row, index, "账期")),
                    creditDays, leadDays, rating));
        }
        return exists == null ? 1 : 2;
    }

    private int importBom(List<String> row, Map<String, Integer> index, boolean preview) {
        long parentId = productId(required(row, index, "父件编码"), "父件");
        long materialId = productId(required(row, index, "子件编码"), "子件");
        if (parentId == materialId) {
            throw new BizException(1002, "父件与子件不能是同一个产品");
        }
        BigDecimal qty = decimal(row, index, "用量", null);
        if (qty == null || qty.signum() <= 0) {
            throw new BizException(1002, "用量必须大于 0");
        }
        Integer operationSeq = integer(row, index, "工序号");
        String substituteGroup = blankToNull(cell(row, index, "替代组"));
        boolean isKey = yesNo(cell(row, index, "是否关键件"));
        String remark = blankToNull(cell(row, index, "备注"));
        if (preview) {
            // 预览只看「父件下有没有这个物料」，不创建草稿版本
            Long exists = jdbcTemplate.query("""
                    select i.id from bom_item i
                    join bom h on h.id = i.bom_id
                    where h.product_id = ? and i.material_id = ?
                    order by i.id desc limit 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, parentId, materialId);
            return exists == null ? 1 : 2;
        }
        // 导入只写草稿版本：已有生效 BOM 时要求先复制新版本，避免绕过审批
        long bomId = bomService.draftBomForImport(parentId);
        Long exists = jdbcTemplate.query("""
                select id from bom_item where bom_id = ? and material_id = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, bomId, materialId);
        if (exists == null) {
            jdbcTemplate.update("""
                    insert into bom_item (bom_id, material_id, qty, operation_seq,
                                          substitute_group, is_key, remark)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, bomId, materialId, qty, operationSeq, substituteGroup, isKey, remark);
        } else {
            jdbcTemplate.update("""
                    update bom_item set qty = ?, operation_seq = ?, substitute_group = ?,
                                        is_key = ?, remark = ?
                    where id = ?
                    """, qty, operationSeq, substituteGroup, isKey, remark, exists);
        }
        return exists == null ? 1 : 2;
    }

    private int importRouting(List<String> row, Map<String, Integer> index, boolean preview) {
        long productId = productId(required(row, index, "产品编码"), "产品");
        String processCode = required(row, index, "工序编码");
        Long processId = jdbcTemplate.query("select id from rm_process where code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, processCode);
        if (processId == null) {
            throw new BizException(1002, "工序编码不存在：" + processCode);
        }
        Integer seq = integer(row, index, "工序号");
        if (seq == null || seq <= 0) {
            throw new BizException(1002, "工序号必须是大于 0 的整数");
        }
        BigDecimal minutes = decimal(row, index, "单件工时", BigDecimal.ZERO);
        BigDecimal price = decimal(row, index, "计件单价", BigDecimal.ZERO);
        boolean needInspection = yesNo(cell(row, index, "是否检验"));
        String remark = blankToNull(cell(row, index, "备注"));
        Long exists = jdbcTemplate.query("""
                select id from product_routing where product_id = ? and seq = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, productId, seq);
        if (!preview) {
            if (exists == null) {
                jdbcTemplate.update("""
                        insert into product_routing (product_id, seq, process_id, minutes_per_piece,
                                                     unit_price, need_inspection, remark)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, productId, seq, processId, minutes, price, needInspection, remark);
            } else {
                jdbcTemplate.update("""
                        update product_routing set process_id = ?, minutes_per_piece = ?, unit_price = ?,
                                                   need_inspection = ?, remark = ?
                        where id = ?
                        """, processId, minutes, price, needInspection, remark, exists);
            }
        }
        return exists == null ? 1 : 2;
    }

    private int importInventory(List<String> row, Map<String, Integer> index, boolean preview) {
        long productId = productId(required(row, index, "物料编码"), "物料");
        String warehouseCode = required(row, index, "仓库编码");
        Long warehouseId = jdbcTemplate.query("select id from warehouse where code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, warehouseCode);
        if (warehouseId == null) {
            throw new BizException(1002, "仓库编码不存在：" + warehouseCode);
        }
        BigDecimal qty = decimal(row, index, "数量", null);
        if (qty == null || qty.signum() <= 0) {
            throw new BizException(1002, "数量必须大于 0");
        }
        String batchNo = blankToNull(cell(row, index, "批次号"));
        if (!preview) {
            inventoryService.inboundWithBatch(productId, warehouseId, qty, "OPENING",
                    "期初导入", "基础数据导入-" + CurrentUser.name(), batchNo, "MANUAL", null);
        }
        return 1;
    }

    // ==================== CSV 工具 ====================

    private String normalizeType(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(value)) {
            throw new BizException(1002, "不支持的数据类型：" + type
                    + "，可选 product / customer / supplier / bom / routing / inventory");
        }
        return value;
    }

    private long productId(String code, String label) {
        Long id = jdbcTemplate.query("select id from product where code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, code);
        if (id == null) {
            throw new BizException(1002, label + "编码不存在：" + code);
        }
        return id;
    }

    private String normalizeCategory(String value) {
        String text = value == null ? "" : value.trim();
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.isEmpty() || "FINISHED".equals(upper) || "成品".equals(text)) {
            return "FINISHED";
        }
        if ("RAW".equals(upper) || "原料".equals(text) || "原材料".equals(text)) {
            return "RAW";
        }
        if ("SEMI".equals(upper) || "半成品".equals(text)) {
            return "SEMI";
        }
        throw new BizException(1002, "类别只能填 RAW / SEMI / FINISHED（或 原料 / 半成品 / 成品）");
    }

    private boolean yesNo(String value) {
        String text = value == null ? "" : value.trim();
        return "是".equals(text) || "Y".equalsIgnoreCase(text) || "TRUE".equalsIgnoreCase(text)
                || "1".equals(text);
    }

    private String upperOr(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text.toUpperCase(Locale.ROOT);
    }

    private String required(List<String> row, Map<String, Integer> index, String column) {
        String value = cell(row, index, column);
        if (value == null || value.isBlank()) {
            throw new BizException(1002, column + "不能为空");
        }
        return value.trim();
    }

    private String cell(List<String> row, Map<String, Integer> index, String column) {
        Integer position = index.get(column);
        if (position == null || position >= row.size()) {
            return "";
        }
        return row.get(position);
    }

    private BigDecimal decimal(List<String> row, Map<String, Integer> index, String column, BigDecimal fallback) {
        String raw = cell(row, index, column);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim().replace(",", ""));
            if (value.signum() < 0) {
                throw new BizException(1002, column + "不能是负数");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BizException(1002, column + "不是有效数字：" + raw);
        }
    }

    private Integer integer(List<String> row, Map<String, Integer> index, String column) {
        String raw = cell(row, index, column);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new BizException(1002, column + "不是有效整数：" + raw);
        }
    }

    private String toCsvLine(List<String> values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(escape(values.get(i)));
        }
        return line.toString();
    }

    private String escape(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /** 支持引号包裹、字段内逗号/换行、注释行 */
    private List<List<String>> readCsv(String content) {
        String text = content.startsWith("\uFEFF") ? content.substring(1) : content;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        // 去掉尾部空行
        while (!rows.isEmpty() && rows.get(rows.size() - 1).stream().allMatch(String::isBlank)) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    private String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
