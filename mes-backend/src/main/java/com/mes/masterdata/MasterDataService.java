package com.mes.masterdata;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.masterdata.api.BomLineInfo;
import com.mes.masterdata.api.CustomerInfo;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.masterdata.api.WarehouseInfo;
import com.mes.platform.customfield.CustomFieldService;
import com.mes.platform.scope.OrgScopeService;
import com.mes.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class MasterDataService implements MasterDataPort {

    /** 产品对外视图的列清单，批量与单个查询共用，保证字段口径一致 */
    private static final String PRODUCT_COLUMNS = """
            select id, code, name, spec, category, unit, price, cost, avg_cost as "avgCost",
                   safety_stock as "safetyStock", loss_rate as "lossRate",
                   serial_managed as "serialManaged", trace_mode as "traceMode", enabled,
                   default_supplier_id as "defaultSupplierId", source, lead_days as "leadDays"
            from product
            """;

    private static final RowMapper<ProductInfo> PRODUCT_MAPPER = (rs, rowNum) -> new ProductInfo(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("spec"),
            rs.getString("unit"), rs.getString("category"),
            rs.getBigDecimal("price"), rs.getBigDecimal("cost"), rs.getBigDecimal("avgCost"),
            rs.getBigDecimal("safetyStock"), rs.getBigDecimal("lossRate"),
            rs.getString("traceMode"), rs.getBoolean("enabled"),
            rs.getObject("defaultSupplierId") == null ? null : rs.getLong("defaultSupplierId"),
            rs.getString("source"), rs.getInt("leadDays"));

    private static final RowMapper<WarehouseInfo> WAREHOUSE_MAPPER = (rs, rowNum) -> new WarehouseInfo(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("type"),
            rs.getBoolean("enabled"), rs.getObject("factory_org_id") == null
                    ? null : rs.getLong("factory_org_id"));

    private static final RowMapper<CustomerInfo> CUSTOMER_MAPPER = (rs, rowNum) -> new CustomerInfo(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("enabled"),
            rs.getString("contact"), rs.getString("phone"), rs.getString("address"));

    /** 客户对外视图的列清单，批量与单个查询共用 */
    private static final String CUSTOMER_COLUMNS = """
            select id, code, name, enabled, contact, phone, address
            from customer
            """;

    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;
    private final CodeGenerator codeGenerator;
    private final BomService bomService;
    private final CustomFieldService customFieldService;

    public MasterDataService(JdbcTemplate jdbcTemplate, OrgScopeService orgScope,
                             CodeGenerator codeGenerator, BomService bomService,
                             CustomFieldService customFieldService) {
        this.jdbcTemplate = jdbcTemplate;
        this.orgScope = orgScope;
        this.codeGenerator = codeGenerator;
        this.bomService = bomService;
        this.customFieldService = customFieldService;
    }

    // ---------------------------------------------------------------- 对外契约

    @Override
    public Optional<ProductInfo> product(long id) {
        return jdbcTemplate.query(PRODUCT_COLUMNS + " where id = ?", PRODUCT_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<ProductInfo> productByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(PRODUCT_COLUMNS + " where code = ?", PRODUCT_MAPPER, code.trim())
                .stream().findFirst();
    }

    @Override
    public Map<Long, ProductInfo> products(Collection<Long> ids) {
        return byIds(PRODUCT_COLUMNS, PRODUCT_MAPPER, ids, ProductInfo::id);
    }

    @Override
    public Set<Long> searchProductIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Set.of();
        }
        String like = "%" + keyword.trim() + "%";
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                select id from product where code ilike ? or name ilike ? order by id limit 500
                """, Long.class, like, like));
    }

    @Override
    public Optional<WarehouseInfo> warehouse(long id) {
        return jdbcTemplate.query("""
                select id, code, name, type, enabled, factory_org_id
                from warehouse where id = ?
                """, WAREHOUSE_MAPPER, id).stream().findFirst();
    }

    @Override
    public Map<Long, WarehouseInfo> warehouses(Collection<Long> ids) {
        return byIds("select id, code, name, type, enabled, factory_org_id from warehouse ",
                WAREHOUSE_MAPPER, ids, WarehouseInfo::id);
    }

    @Override
    public long warehouseIdOfType(String type) {
        List<Long> rows = jdbcTemplate.queryForList(
                "select id from warehouse where type = ? and enabled = true order by id limit 1",
                Long.class, type);
        if (rows.isEmpty()) {
            throw new BizException(3002, "未找到可用的 " + type + " 类型仓库");
        }
        return rows.get(0);
    }

    @Override
    public Set<Long> visibleWarehouseIds(Long factoryOrgId) {
        if (factoryOrgId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                select id from warehouse where factory_org_id = ? or factory_org_id is null
                """, Long.class, factoryOrgId));
    }

    @Override
    public Optional<CustomerInfo> customer(long id) {
        return jdbcTemplate.query(CUSTOMER_COLUMNS + " where id = ?", CUSTOMER_MAPPER, id).stream().findFirst();
    }

    @Override
    public Map<Long, CustomerInfo> customers(Collection<Long> ids) {
        return byIds(CUSTOMER_COLUMNS, CUSTOMER_MAPPER, ids, CustomerInfo::id);
    }

    @Override
    public Set<Long> searchCustomerIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Set.of();
        }
        String like = "%" + keyword.trim() + "%";
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                select id from customer where code ilike ? or name ilike ? order by id limit 500
                """, Long.class, like, like));
    }

    @Override
    public void updateAvgCost(long productId, BigDecimal avgCost) {
        jdbcTemplate.update("update product set avg_cost = ? where id = ?", avgCost, productId);
    }

    /** 批量查询的公共实现：空集合直接返回，避免拼出 in () 这种非法 SQL */
    private <T> Map<Long, T> byIds(String columns, RowMapper<T> mapper, Collection<Long> ids,
                                   java.util.function.ToLongFunction<T> idOf) {
        Map<Long, T> result = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        List<Long> unique = new ArrayList<>(ids.stream().filter(java.util.Objects::nonNull).distinct().toList());
        if (unique.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(unique.size(), "?"));
        jdbcTemplate.query(columns + " where id in (" + placeholders + ")", mapper, unique.toArray())
                .forEach(item -> result.put(idOf.applyAsLong(item), item));
        return result;
    }

    public record CustomerRequest(String code,
                                  @NotBlank String name,
                                  String contact,
                                  String phone,
                                  String address,
                                  String level) {
    }

    /**
     * 产品维护入参。
     *
     * <p>{@code traceMode} 是默认追溯方式；{@code serialManaged} 是老客户端的布尔写法，
     * 两者都传时以 traceMode 为准，只传布尔就折算成 NONE / SERIAL。</p>
     */
    public record ProductRequest(String code,
                                 @NotBlank String name,
                                 String spec,
                                 @NotBlank String category,
                                 @NotBlank String unit,
                                 @NotNull @Positive BigDecimal price,
                                 BigDecimal cost,
                                 BigDecimal safetyStock,
                                 BigDecimal lossRate,
                                 Boolean serialManaged,
                                 String traceMode,
                                 Long defaultSupplierId,
                                 String source,
                                 Integer leadDays,
                                 Map<String, Object> customFields) {

        /** 兼容老调用：不带默认供应商、来源与提前期 */
        public ProductRequest(String code, String name, String spec, String category, String unit,
                              BigDecimal price, BigDecimal cost, BigDecimal safetyStock, BigDecimal lossRate,
                              Boolean serialManaged, String traceMode) {
            this(code, name, spec, category, unit, price, cost, safetyStock, lossRate,
                    serialManaged, traceMode, null, null, null, null);
        }

        /** 兼容：带默认供应商，不带来源与提前期 */
        public ProductRequest(String code, String name, String spec, String category, String unit,
                              BigDecimal price, BigDecimal cost, BigDecimal safetyStock, BigDecimal lossRate,
                              Boolean serialManaged, String traceMode, Long defaultSupplierId) {
            this(code, name, spec, category, unit, price, cost, safetyStock, lossRate,
                    serialManaged, traceMode, defaultSupplierId, null, null, null);
        }

        /** 归一化：source 优先，没填时按物料类型兜底 */
        public String resolvedSource() {
            if (source != null && !source.isBlank()) {
                String value = source.trim().toUpperCase();
                if (!List.of("MAKE", "BUY").contains(value)) {
                    throw new BizException(1002, "默认来源只能是 MAKE（自制）或 BUY（外购）");
                }
                return value;
            }
            return switch (category == null ? "" : category.trim().toUpperCase()) {
                case "RAW", "PURCHASED" -> "BUY";
                default -> "MAKE";
            };
        }

        /** 归一化：traceMode 优先，布尔兜底，非法值按 NONE */
        public String resolvedTraceMode() {
            if (traceMode != null && !traceMode.isBlank()) {
                String value = traceMode.trim().toUpperCase();
                if (!List.of("NONE", "SERIAL", "LOT").contains(value)) {
                    throw new BizException(1002, "追溯方式只能是 NONE / SERIAL / LOT");
                }
                return value;
            }
            return Boolean.TRUE.equals(serialManaged) ? "SERIAL" : "NONE";
        }
    }

    public record WarehouseRequest(String code,
                                   @NotBlank String name,
                                   @NotBlank String type,
                                   Long factoryOrgId) {
    }

    public record RoutingRequest(@NotNull List<RoutingLine> items) {
    }

    public record RoutingLine(@NotNull Integer seq, @NotNull Long processId, BigDecimal minutesPerPiece,
                              BigDecimal unitPrice, Boolean needInspection, String remark) {
    }

    public List<Map<String, Object>> customers(String keyword) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return jdbcTemplate.queryForList("""
                select id, code, name, contact, phone, address, level, enabled,
                       to_char(created_at, 'YYYY-MM-DD') as "createdAt"
                from customer
                where (code ilike ? or name ilike ? or contact ilike ?)
                order by id desc
                """, like, like, like);
    }

    public long createCustomer(CustomerRequest request) {
        return jdbcTemplate.queryForObject("""
                insert into customer (code, name, contact, phone, address, level)
                values (?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, codeOrGenerate(request.code(), "CUS"), request.name(), request.contact(),
                request.phone(), request.address(), request.level() == null ? "A" : request.level());
    }

    public void updateCustomer(long id, CustomerRequest request) {
        int rows = jdbcTemplate.update("""
                update customer set code = ?, name = ?, contact = ?, phone = ?, address = ?, level = ?
                where id = ?
                """, requireCode(request.code()), request.name(), request.contact(), request.phone(),
                request.address(), request.level() == null ? "A" : request.level(), id);
        if (rows == 0) {
            throw new BizException("客户不存在");
        }
    }

    public void deleteCustomer(long id) {
        jdbcTemplate.update("delete from customer where id = ?", id);
    }

    public List<Map<String, Object>> products(String keyword, String category) {
        return products(keyword, category, null, null);
    }

    public List<Map<String, Object>> products(String keyword, String category,
                                              String customFieldKey, String customFieldValue) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        String categoryFilter = (category == null || category.isBlank()) ? "" : " and p.category = ? ";
        StringBuilder where = new StringBuilder(" and (p.code ilike ? or p.name ilike ?) ")
                .append(categoryFilter);
        List<Object> args = new ArrayList<>(List.of(like, like));
        if (!categoryFilter.isEmpty()) {
            args.add(category);
        }
        if (customFieldKey != null && !customFieldKey.isBlank()
                && customFieldValue != null && !customFieldValue.isBlank()) {
            CustomFieldService.FormField definition =
                    customFieldService.formDefinition("product", customFieldKey);
            if (definition == null) {
                throw new BizException(1002, "自定义字段不存在、已停用或无权查看：" + customFieldKey);
            }
            where.append(" and p.ext_data ->> ? = ? ");
            args.add(definition.fieldKey());
            args.add(customFieldValue.trim());
        }
        String sql = """
                select p.id, p.code, p.name, p.spec, p.category, p.unit, p.price, p.cost,
                       p.safety_stock as "safetyStock", p.loss_rate as "lossRate", p.enabled,
                       p.serial_managed as "serialManaged", p.trace_mode as "traceMode",
                       p.default_supplier_id as "defaultSupplierId",
                       coalesce(p.source, case when p.category in ('RAW','PURCHASED') then 'BUY' else 'MAKE' end) as source,
                       p.lead_days as "leadDays",
                       coalesce(sum(i.qty), 0) as stock,
                       (select count(*) from product_routing r where r.product_id = p.id) as "routingCount",
                       (select count(*) from bom_item i join bom h on h.id = i.bom_id
                         where h.product_id = p.id and h.status = 'ACTIVE') as "bomCount",
                       p.ext_data::text as "extData"
                from product p
                left join rm_inventory i on i.product_id = p.id
                where 1 = 1 %s
                group by p.id
                order by p.id
                """.formatted(where);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(sql, args.toArray());
        List<CustomFieldService.FormField> customFields =
                customFieldService.formDefinitions("product");
        for (Map<String, Object> record : records) {
            Object extData = record.remove("extData");
            record.put("customFields", customFieldService.visibleJson(customFields,
                    extData == null ? null : String.valueOf(extData)));
        }
        return records;
    }

    /** 引用选择专用：只返回启用客户的少量字段，按编码排序，避免一次拉全表 */
    public List<Map<String, Object>> customerOptions(String keyword, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return jdbcTemplate.queryForList("""
                select id, code, name, contact, phone
                from customer
                where enabled and (code ilike ? or name ilike ? or contact ilike ?)
                order by code
                limit ?
                """, like, like, like, size);
    }

    /** 引用选择专用：只返回启用物料的选择字段；routingOnly 只保留已配置工艺路线的产品 */
    public List<Map<String, Object>> productOptions(String keyword, int limit, boolean routingOnly) {
        int size = Math.max(1, Math.min(limit, 50));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        String routingFilter = routingOnly
                ? " and exists (select 1 from product_routing r where r.product_id = p.id) "
                : "";
        return jdbcTemplate.queryForList("""
                select p.id, p.code, p.name, p.spec, p.unit, p.price,
                       p.default_supplier_id as "defaultSupplierId",
                       p.loss_rate as "lossRate", p.trace_mode as "traceMode",
                       coalesce(sum(i.qty), 0) as stock
                from product p
                left join rm_inventory i on i.product_id = p.id
                where p.enabled and (p.code ilike ? or p.name ilike ? or p.spec ilike ?) %s
                group by p.id
                order by p.code
                limit ?
                """.formatted(routingFilter), like, like, like, size);
    }

    /** 引用选择专用：仓库下拉只带选择需要的字段，沿用工厂数据范围，可按类型过滤 */
    public List<Map<String, Object>> warehouseOptions(String keyword, String type, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder sql = new StringBuilder("""
                select id, code, name, type, factory_org_id as "factoryOrgId"
                from warehouse
                where (code ilike ? or name ilike ?)
                """);
        List<Object> args = new ArrayList<>(List.of(like, like));
        if (type != null && !type.isBlank()) {
            sql.append(" and type = ? ");
            args.add(type.trim().toUpperCase());
        }
        Long factory = orgScope.listFilter();
        if (factory != null) {
            sql.append(" and (factory_org_id = ? or factory_org_id is null) ");
            args.add(factory);
        }
        sql.append(" order by code limit ? ");
        args.add(size);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public long createProduct(ProductRequest request) {
        String traceMode = request.resolvedTraceMode();
        Long id = jdbcTemplate.queryForObject("""
                insert into product (code, name, spec, category, unit, price, cost, safety_stock, loss_rate,
                                     serial_managed, trace_mode, default_supplier_id, source, lead_days)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, codeOrGenerate(request.code(), "PD"), request.name(), request.spec(),
                request.category(), request.unit(), request.price(),
                request.cost() == null ? BigDecimal.ZERO : request.cost(),
                request.safetyStock() == null ? BigDecimal.ZERO : request.safetyStock(),
                request.lossRate() == null ? BigDecimal.ZERO : request.lossRate(),
                "SERIAL".equals(traceMode), traceMode, request.defaultSupplierId(),
                request.resolvedSource(), request.leadDays() == null ? 0 : request.leadDays());
        String extJson = request.customFields() == null ? "{}"
                : customFieldService.mergeJson("product", "{}", request.customFields(), true);
        jdbcTemplate.update("update product set ext_data = ?::jsonb where id = ?", extJson, id);
        return id;
    }

    public void updateProduct(long id, ProductRequest request) {
        // serial_managed 是 trace_mode 的镜像，一起写，避免两个字段各说各话
        String traceMode = request.resolvedTraceMode();
        String existingExt = jdbcTemplate.queryForList(
                "select ext_data::text from product where id = ?", String.class, id)
                .stream().findFirst().orElse("{}");
        String extJson = customFieldService.mergeJson("product", existingExt,
                request.customFields(), false);
        int rows = jdbcTemplate.update("""
                update product set code = ?, name = ?, spec = ?, category = ?, unit = ?,
                       price = ?, cost = ?, safety_stock = ?, loss_rate = ?,
                       serial_managed = ?, trace_mode = ?, default_supplier_id = ?,
                       source = ?, lead_days = ?, ext_data = ?::jsonb
                where id = ?
                """, requireCode(request.code()), request.name(), request.spec(), request.category(),
                request.unit(), request.price(),
                request.cost() == null ? BigDecimal.ZERO : request.cost(),
                request.safetyStock() == null ? BigDecimal.ZERO : request.safetyStock(),
                request.lossRate() == null ? BigDecimal.ZERO : request.lossRate(),
                "SERIAL".equals(traceMode), traceMode, request.defaultSupplierId(),
                request.resolvedSource(), request.leadDays() == null ? 0 : request.leadDays(),
                extJson, id);
        if (rows == 0) {
            throw new BizException("产品不存在");
        }
    }

    public void deleteProduct(long id) {
        jdbcTemplate.update("delete from product where id = ?", id);
    }

    public List<Map<String, Object>> bom(long productId) {
        return bomService.activeLinesOfProduct(productId);
    }

    /** 工单与 MRP 取料：当前生效 BOM 的行，用量已折算成每个父件用量 */
    @Override
    public List<BomLineInfo> activeBomLines(long productId) {
        return bomService.activeLines(productId);
    }

    @Override
    public Long activeBomId(long productId) {
        return bomService.activeBomId(productId, java.time.LocalDate.now());
    }

    /** 产品工艺路线：决定工单生成哪些工序、标准工时与工价。 */
    public List<Map<String, Object>> routing(long productId) {
        return jdbcTemplate.queryForList("""
                select r.id, r.seq, r.process_id as "processId", p.code as "processCode",
                       p.name as "processName", p.category, p.inspection_mode as "inspectionMode",
                       r.minutes_per_piece as "minutesPerPiece", r.unit_price as "unitPrice",
                       r.need_inspection as "needInspection", r.remark
                from product_routing r
                join rm_process p on p.id = r.process_id
                where r.product_id = ?
                order by r.seq
                """, productId);
    }

    @Transactional
    public void saveRouting(long productId, RoutingRequest request) {
        Integer exists = jdbcTemplate.queryForObject("select count(*) from product where id = ?", Integer.class, productId);
        if (exists == null || exists == 0) {
            throw new BizException("产品不存在");
        }
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "工艺路线至少需要一道工序");
        }
        List<RoutingLine> lines = new java.util.ArrayList<>(request.items());
        lines.sort(java.util.Comparator.comparing(RoutingLine::seq));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).seq() == null || lines.get(i).seq() != i + 1) {
                throw new BizException(1002, "工序顺序必须从 1 开始连续排列");
            }
        }
        String oldSummary = routingSummary(productId);
        jdbcTemplate.update("delete from product_routing where product_id = ?", productId);
        for (RoutingLine line : lines) {
            List<Map<String, Object>> processes = jdbcTemplate.queryForList("""
                    select id, default_minutes, default_price, need_inspection, enabled
                    from rm_process where id = ?
                    """, line.processId());
            if (processes.isEmpty()) {
                throw new BizException(1002, "第 " + line.seq() + " 道工序不存在");
            }
            Map<String, Object> process = processes.get(0);
            if (!Boolean.TRUE.equals(process.get("enabled"))) {
                throw new BizException(1002, "第 " + line.seq() + " 道工序已停用，不能加入工艺路线");
            }
            BigDecimal minutes = line.minutesPerPiece() == null
                    ? new BigDecimal(String.valueOf(process.get("default_minutes")))
                    : line.minutesPerPiece();
            BigDecimal price = line.unitPrice() == null
                    ? new BigDecimal(String.valueOf(process.get("default_price")))
                    : line.unitPrice();
            boolean needInspection = line.needInspection() == null
                    ? Boolean.TRUE.equals(process.get("need_inspection"))
                    : line.needInspection();
            jdbcTemplate.update("""
                    insert into product_routing (product_id, seq, process_id, minutes_per_piece,
                                                 unit_price, need_inspection, remark)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, productId, line.seq(), line.processId(), minutes, price, needInspection,
                    blankToNull(line.remark()));
        }
        // 路线上的检验模式跟随工序主数据，具体路线不单独维护
        jdbcTemplate.update("""
                update product_routing r set inspection_mode = p.inspection_mode,
                                            need_inspection = p.inspection_mode <> 'NONE'
                from rm_process p where p.id = r.process_id and r.product_id = ?
                """, productId);
        logRoutingChange(productId, oldSummary == null ? "INIT" : "UPDATE", oldSummary,
                routingSummary(productId), null);
    }

    /** 工艺路线摘要，用于变更记录，例如「下料→机加工→装配」。 */
    private String routingSummary(long productId) {
        return jdbcTemplate.queryForObject("""
                select string_agg(p.name, '→' order by r.seq)
                from product_routing r join rm_process p on p.id = r.process_id
                where r.product_id = ?
                """, String.class, productId);
    }

    private void logRoutingChange(long productId, String action, String oldSummary,
                                  String newSummary, String remark) {
        jdbcTemplate.update("""
                insert into product_routing_change (product_id, action, old_summary, new_summary,
                                                   remark, changed_by)
                values (?, ?, ?, ?, ?, ?)
                """, productId, action, oldSummary, newSummary, remark, CurrentUser.name());
    }

    public List<Map<String, Object>> routingChanges(long productId) {
        return jdbcTemplate.queryForList("""
                select id, action, old_summary as "oldSummary", new_summary as "newSummary",
                       remark, changed_by as "changedBy",
                       to_char(changed_at, 'YYYY-MM-DD HH24:MI') as "changedAt"
                from product_routing_change
                where product_id = ? order by id desc limit 20
                """, productId);
    }

    /**
     * 从其他产品复制工艺路线，用于新产品的工序初始化。
     */
    @Transactional
    public Map<String, Object> copyRouting(long productId, Long sourceProductId, Boolean overwrite) {
        if (sourceProductId == null) {
            throw new BizException(1002, "请选择要复制的源产品");
        }
        if (sourceProductId == productId) {
            throw new BizException(1002, "不能从产品自身复制工艺路线");
        }
        Integer sourceCount = jdbcTemplate.queryForObject("""
                select count(*) from product_routing where product_id = ?
                """, Integer.class, sourceProductId);
        if (sourceCount == null || sourceCount == 0) {
            throw new BizException(1002, "源产品还没有工艺路线，无法复制");
        }
        Integer exists = jdbcTemplate.queryForObject("""
                select count(*) from product_routing where product_id = ?
                """, Integer.class, productId);
        if (exists != null && exists > 0 && !Boolean.TRUE.equals(overwrite)) {
            throw new BizException(2004, "目标产品已有工艺路线，勾选覆盖后再复制");
        }
        String oldSummary = routingSummary(productId);
        jdbcTemplate.update("delete from product_routing where product_id = ?", productId);
        int copied = jdbcTemplate.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece,
                                             unit_price, need_inspection, inspection_mode, remark)
                select ?, seq, process_id, minutes_per_piece, unit_price, need_inspection,
                       inspection_mode, remark
                from product_routing where product_id = ?
                """, productId, sourceProductId);
        String newSummary = routingSummary(productId);
        String sourceCode = jdbcTemplate.queryForObject(
                "select code from product where id = ?", String.class, sourceProductId);
        logRoutingChange(productId, "COPY", oldSummary, newSummary, "从产品 " + sourceCode + " 复制工艺路线");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("copied", copied);
        result.put("summary", newSummary);
        return result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 编码留空时按「编码规则」自动生成，填了就沿用用户输入 */
    private String codeOrGenerate(String value, String bizType) {
        return value == null || value.isBlank() ? codeGenerator.next(bizType) : value.trim();
    }

    /** 修改时编码不能清空，避免把已有数据的编码改成空串 */
    private String requireCode(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "编码不能为空");
        }
        return value.trim();
    }

    public List<Map<String, Object>> warehouses() {
        Long factory = orgScope.listFilter();
        if (factory == null) {
            return jdbcTemplate.queryForList("""
                    select w.id, w.code, w.name, w.type, w.enabled,
                           w.factory_org_id as "factoryOrgId", o.name as "factoryName"
                    from warehouse w left join sys_org o on o.id = w.factory_org_id
                    order by w.id
                    """);
        }
        return jdbcTemplate.queryForList("""
                select w.id, w.code, w.name, w.type, w.enabled,
                       w.factory_org_id as "factoryOrgId", o.name as "factoryName"
                from warehouse w left join sys_org o on o.id = w.factory_org_id
                where (w.factory_org_id = ? or w.factory_org_id is null)
                order by w.id
                """, factory);
    }

    public long createWarehouse(WarehouseRequest request) {
        // 显式指定工厂（管理员给别的厂建仓）优先，否则落到当前账号所属工厂
        Long factory = request.factoryOrgId() != null
                ? request.factoryOrgId()
                : orgScope.writeFactoryOrgId();
        return jdbcTemplate.queryForObject("""
                insert into warehouse (code, name, type, factory_org_id) values (?, ?, ?, ?) returning id
                """, Long.class, codeOrGenerate(request.code(), "WH"), request.name(), request.type(), factory);
    }

    public void updateWarehouse(long id, WarehouseRequest request) {
        String code = requireCode(request.code());
        Long factory = request.factoryOrgId() != null ? request.factoryOrgId() : null;
        if (factory == null) {
            jdbcTemplate.update("update warehouse set code = ?, name = ?, type = ? where id = ?",
                    code, request.name(), request.type(), id);
            return;
        }
        jdbcTemplate.update("""
                update warehouse set code = ?, name = ?, type = ?, factory_org_id = ? where id = ?
                """, code, request.name(), request.type(), factory, id);
    }

    public void deleteWarehouse(long id) {
        jdbcTemplate.update("delete from warehouse where id = ?", id);
    }
}
