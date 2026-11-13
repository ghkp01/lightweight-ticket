package com.mes.inventory;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.inventory.api.BatchMove;
import com.mes.inventory.api.InventoryPort;
import com.mes.inventory.api.StockLine;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.masterdata.api.ProductInfo;
import com.mes.masterdata.api.WarehouseInfo;
import com.mes.platform.scope.OrgScopeService;
import com.mes.production.api.WorkOrderIssuePort;
import com.mes.security.CurrentUser;
import com.mes.warehouse.api.WarehouseLocationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库存：所有出入库的唯一写入口。
 *
 * <p>对外契约见 {@link InventoryPort}：生产、仓储、采购、发货、退货、质检、基础数据这些模块
 * 只依赖那个接口，不再直接注入本类，将来把库存拆成独立服务时改实现即可。</p>
 */
@Service
public class InventoryService implements InventoryPort {

    private static final org.springframework.jdbc.core.RowMapper<StockLine> STOCK_LINE_MAPPER =
            (rs, rowNum) -> new StockLine(rs.getLong("product_id"), rs.getLong("warehouse_id"),
                    rs.getBigDecimal("qty"), rs.getBigDecimal("locked_qty"));

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final OrgScopeService orgScope;
    private final MasterDataPort masterData;
    private final WorkOrderIssuePort workOrderIssue;
    private final WarehouseLocationPort locations;

    public InventoryService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                           OrgScopeService orgScope, MasterDataPort masterData,
                           WorkOrderIssuePort workOrderIssue,
                           WarehouseLocationPort locations) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.orgScope = orgScope;
        this.masterData = masterData;
        this.workOrderIssue = workOrderIssue;
        this.locations = locations;
    }

    public List<Map<String, Object>> list(String keyword, Long warehouseId, boolean onlyWarning) {
        Long scopeWarehouseId = warehouseScope();
        if (scopeWarehouseId != null) {
            warehouseId = scopeWarehouseId;
        }
        // 产品名/编码是主数据：先换成 id 集合，库存侧只用 in 条件
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        Set<Long> productIds = hasKeyword ? masterData.searchProductIds(keyword) : Set.of();
        if (hasKeyword && productIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select i.id, i.product_id as "productId", i.warehouse_id as "warehouseId",
                       i.warehouse_id as "_warehouseId", i.qty,
                       i.locked_qty as "lockedQty", (i.qty - i.locked_qty) as available,
                       (select count(*) from inventory_batch b
                        where b.product_id = i.product_id and b.warehouse_id = i.warehouse_id and b.qty > 0) as "batchCount"
                from inventory i
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (hasKeyword) {
            sql.append(" and i.product_id in (")
                    .append(String.join(", ", java.util.Collections.nCopies(productIds.size(), "?")))
                    .append(") ");
            args.addAll(productIds);
        }
        if (warehouseId != null) {
            sql.append(" and i.warehouse_id = ? ");
            args.add(warehouseId);
        }
        // 多工厂：库存跟着仓库归属，开关关闭时这段不生效
        MasterDataViews.appendWarehouseScope(orgScope, masterData, sql, args, "i.warehouse_id");
        List<Map<String, Object>> rows =
                new ArrayList<>(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
        // 安全库存、移动平均成本都在产品档案上：预警与金额只能补完产品后再算（一次批量取，别退化成 N+1）
        List<Long> rowProductIds = rows.stream()
                .map(row -> row.get("productId"))
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).longValue())
                .distinct().toList();
        Map<Long, ProductInfo> products = masterData.products(rowProductIds);
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        for (Map<String, Object> row : rows) {
            ProductInfo product = row.get("productId") instanceof Number id
                    ? products.get(id.longValue()) : null;
            putProductColumns(row, product);
        }
        if (onlyWarning) {
            rows.removeIf(row -> !Boolean.TRUE.equals(row.get("warning")));
        }
        rows.sort(java.util.Comparator
                .comparing((Map<String, Object> row) -> Boolean.TRUE.equals(row.get("warning")) ? 0 : 1)
                .thenComparing(row -> row.get("productCode") == null ? "" : String.valueOf(row.get("productCode"))));
        return rows;
    }

    /** 库存列表分页：库存行数 = 产品 × 仓库，改为后端分页避免一次拉全表 */
    public PageResult page(String keyword, Long warehouseId, boolean onlyWarning, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        Long scopeWarehouseId = warehouseScope();
        if (scopeWarehouseId != null) {
            warehouseId = scopeWarehouseId;
        }
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        Set<Long> productIds = hasKeyword ? masterData.searchProductIds(keyword) : Set.of();
        if (hasKeyword && productIds.isEmpty()) {
            return new PageResult(List.of(), 0, safePage, safeSize);
        }
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (hasKeyword) {
            where.append(" and i.product_id in (")
                    .append(String.join(", ", java.util.Collections.nCopies(productIds.size(), "?")))
                    .append(") ");
            args.addAll(productIds);
        }
        if (warehouseId != null) {
            where.append(" and i.warehouse_id = ? ");
            args.add(warehouseId);
        }
        MasterDataViews.appendWarehouseScope(orgScope, masterData, where, args, "i.warehouse_id");
        if (onlyWarning) {
            where.append(" and p.safety_stock is not null and i.qty < p.safety_stock ");
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from inventory i join rm_product p on p.id = i.product_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.warehouse_id as "warehouseId",
                       i.warehouse_id as "_warehouseId", i.qty,
                       i.locked_qty as "lockedQty", (i.qty - i.locked_qty) as available,
                       (select count(*) from inventory_batch b
                        where b.product_id = i.product_id and b.warehouse_id = i.warehouse_id
                          and b.qty > 0) as "batchCount",
                       p.code as "productCode", p.name as "productName", p.spec, p.unit, p.category,
                       p.safety_stock as "safetyStock", p.avg_cost as "avgCost",
                       coalesce(i.qty * p.avg_cost, 0) as "stockAmount",
                       (p.safety_stock is not null and i.qty < p.safety_stock) as warning
                from inventory i
                join rm_product p on p.id = i.product_id
                """ + where + """
                 order by case when p.safety_stock is not null and i.qty < p.safety_stock
                               then 0 else 1 end, p.code
                 limit ? offset ?
                """, pageArgs.toArray());
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        return new PageResult(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** 把产品档案里「库存列表要用」的列补到行上，顺带算是否低于安全库存 */
    private void putProductColumns(Map<String, Object> row, ProductInfo product) {
        row.put("productCode", product == null ? null : product.code());
        row.put("productName", product == null ? null : product.name());
        row.put("spec", product == null ? null : product.spec());
        row.put("unit", product == null ? null : product.unit());
        row.put("category", product == null ? null : product.category());
        row.put("safetyStock", product == null ? null : product.safetyStock());
        row.put("avgCost", product == null ? null : product.avgCost());
        BigDecimal qty = row.get("qty") instanceof Number number
                ? new BigDecimal(number.toString()) : BigDecimal.ZERO;
        BigDecimal avgCost = product == null ? null : product.avgCost();
        row.put("stockAmount", avgCost == null ? null : qty.multiply(avgCost).setScale(2, java.math.RoundingMode.HALF_UP));
        BigDecimal safetyStock = product == null ? null : product.safetyStock();
        row.put("warning", safetyStock != null && qty.compareTo(safetyStock) < 0);
    }

    /** 某个产品在某仓的批次库存，按先进先出排序 */
    public List<Map<String, Object>> batchStocks(long productId, long warehouseId) {
        return batchStocks(productId, warehouseId, null);
    }

    /** 不分页的批次库存，可按批次号搜索；保持旧接口返回数组 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> batchStocks(long productId, long warehouseId, String keyword) {
        return (List<Map<String, Object>>) batchStocks(productId, warehouseId, keyword, null, null).records();
    }

    /** 某产品在某仓的批次库存（可分页、按批次号搜索）；page 为空时返回全量 */
    public PageResult batchStocks(long productId, long warehouseId, String keyword, Integer page, Integer size) {
        StringBuilder where = new StringBuilder(
                " where b.product_id = ? and b.warehouse_id = ? and b.qty <> 0 ");
        List<Object> args = new ArrayList<>(List.of(productId, warehouseId));
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and b.batch_no ilike ? ");
            args.add("%" + keyword.trim() + "%");
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from inventory_batch b" + where, Long.class, args.toArray());
        String selectSql = """
                select b.batch_no as "batchNo", b.qty, b.locked_qty as "lockedQty", b.source,
                       b.work_order_id as "workOrderId", b.location_id as "locationId",
                       to_char(b.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from inventory_batch b
                """ + where + " order by b.created_at, b.id ";
        List<Map<String, Object>> rows;
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 20 : Math.min(size, 100);
        if (page == null) {
            // 旧调用不带 page：返回全部批次
            rows = jdbcTemplate.queryForList(selectSql, args.toArray());
        } else {
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(safeSize);
            pageArgs.add((safePage - 1) * safeSize);
            rows = jdbcTemplate.queryForList(selectSql + " limit ? offset ?", pageArgs.toArray());
        }
        enrichBatches(rows, warehouseId);
        return new PageResult(rows, total == null ? 0 : total, safePage, safeSize);
    }

    private void enrichBatches(List<Map<String, Object>> rows, long warehouseId) {
        if (rows.isEmpty()) {
            return;
        }
        // 工单号归生产、库位编码归仓储：都走各自的只读端口批量补
        List<Long> workOrderIds = rows.stream().map(row -> row.get("workOrderId"))
                .filter(Number.class::isInstance).map(value -> ((Number) value).longValue()).distinct().toList();
        Map<Long, String> workNos = workOrderIssue.workNos(workOrderIds);
        List<Long> locationIds = rows.stream().map(row -> row.get("locationId"))
                .filter(Number.class::isInstance).map(value -> ((Number) value).longValue()).distinct().toList();
        Map<Long, WarehouseLocationPort.LocationRef> locationRefs = locations.locations(locationIds);
        // 这里只可能是同一个仓库，走契约取一次名字即可，不必 join warehouse
        String warehouseName = masterData.warehouse(warehouseId)
                .map(WarehouseInfo::name).orElse(null);
        for (Map<String, Object> row : rows) {
            row.put("workNo", row.get("workOrderId") instanceof Number id ? workNos.get(id.longValue()) : null);
            WarehouseLocationPort.LocationRef location = row.get("locationId") instanceof Number id
                    ? locationRefs.get(id.longValue()) : null;
            row.put("locationCode", location == null ? null : location.code());
            row.put("locationName", location == null ? null : location.name());
            row.put("warehouseName", warehouseName);
            row.remove("locationId");
        }
    }

    public PageResult txns(Long productId, String txnType, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (productId != null) {
            where.append(" and t.product_id = ? ");
            args.add(productId);
        }
        if (txnType != null && !txnType.isBlank()) {
            where.append(" and t.txn_type = ? ");
            args.add(txnType);
        }
        Long scopeWarehouseId = warehouseScope();
        if (scopeWarehouseId != null) {
            where.append(" and t.warehouse_id = ? ");
            args.add(scopeWarehouseId);
        }
        Long factory = orgScope.listFilter();
        if (factory != null) {
            // 工厂隔离落在仓库上：向主数据要一份「本厂 + 未归属」的仓库 id 集合
            Set<Long> visible = masterData.visibleWarehouseIds(factory);
            if (visible.isEmpty()) {
                where.append(" and 1 = 0 ");
            } else {
                where.append(" and t.warehouse_id in (")
                        .append(String.join(", ", java.util.Collections.nCopies(visible.size(), "?")))
                        .append(") ");
                args.addAll(visible);
            }
        }
        Long total = jdbcTemplate.queryForObject("select count(*) from stock_txn t " + where, Long.class, args.toArray());
        java.util.List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select t.id, t.txn_no as "txnNo", t.product_id as "productId",
                       t.warehouse_id as "_warehouseId", t.txn_type as "txnType",
                       t.qty, t.batch_no as "batchNo", t.ref_type as "refType", t.ref_no as "refNo",
                       t.unit_cost as "unitCost", t.amount,
                       t.operator, t.remark,
                       to_char(t.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from stock_txn t
                """ + where + " order by t.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(fillMasterData(records), total == null ? 0 : total, page, size);
    }

    /**
     * 用契约把产品编码/名称、仓库名称补到结果行里，替代 SQL 里的 join product / join warehouse。
     *
     * <p>一页最多几十行，一次批量查询就够，不会退化成 N+1；这也是 {@code MasterDataPort.products()}
     * 从一开始就按 id 批量设计的原因 —— 否则每补一行的名称都要多一次往返。</p>
     *
     * <p>{@code _warehouseId} 是为了补名称临时取的列，补完就删掉，不改变对外返回的字段。</p>
     */
    private List<Map<String, Object>> fillMasterData(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<Long> productIds = new ArrayList<>();
        List<Long> warehouseIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("productId") instanceof Number value) {
                productIds.add(value.longValue());
            }
            if (row.get("_warehouseId") instanceof Number value) {
                warehouseIds.add(value.longValue());
            }
        }
        Map<Long, ProductInfo> products = masterData.products(productIds);
        Map<Long, WarehouseInfo> warehouses = masterData.warehouses(warehouseIds);
        for (Map<String, Object> row : rows) {
            if (row.get("productId") instanceof Number value) {
                ProductInfo product = products.get(value.longValue());
                if (product != null) {
                    row.put("productCode", product.code());
                    row.put("productName", product.name());
                }
            }
            if (row.get("_warehouseId") instanceof Number value) {
                WarehouseInfo warehouse = warehouses.get(value.longValue());
                if (warehouse != null) {
                    row.put("warehouseName", warehouse.name());
                }
                row.remove("_warehouseId");
            }
        }
        return rows;
    }

    @Transactional
    public void inbound(long productId, long warehouseId, BigDecimal qty, String refType, String refNo, String remark) {
        addBatch(productId, warehouseId, qty, "IN", refType, refNo, remark, null, "INBOUND", null, null, null);
    }

    /** 带批次入库：来料按合格证批次、成品按工单号批次 */
    @Transactional
    public String inboundWithBatch(long productId, long warehouseId, BigDecimal qty, String refType, String refNo,
                                  String remark, String batchNo, String source, Long workOrderId) {
        return addBatch(productId, warehouseId, qty, "IN", refType, refNo, remark, batchNo, source,
                workOrderId, null, null);
    }

    /**
     * 带成本入库：采购收货传实际单价、工单完工传工单单位成本；
     * 传空时退回产品档案成本，出库一律按产品移动平均成本计价。
     */
    @Transactional
    public String inboundWithBatch(long productId, long warehouseId, BigDecimal qty, String refType, String refNo,
                                   String remark, String batchNo, String source, Long workOrderId,
                                   BigDecimal unitCost) {
        return addBatch(productId, warehouseId, qty, "IN", refType, refNo, remark, batchNo, source,
                workOrderId, null, unitCost);
    }

    @Transactional
    public void outbound(long productId, long warehouseId, BigDecimal qty, String refType, String refNo, String remark) {
        consumeFifo(productId, warehouseId, qty, "OUT", refType, refNo, remark);
    }

    @Transactional
    public void issue(long productId, long warehouseId, BigDecimal qty, String workNo) {
        issue(productId, warehouseId, qty, workNo, "生产领料");
    }

    /** 生产领料：按批次先进先出扣减，并把消耗的批次记到工单上，成品才能往上追溯到原料 */
    @Transactional
    public void issue(long productId, long warehouseId, BigDecimal qty, String workNo, String remark) {
        issue(productId, warehouseId, qty, workNo, remark, null);
    }

    /** issueId 非空时，流水与领料单双向可查；返回实际扣减的批次，供领料单记录批次摘要 */
    @Transactional
    public List<BatchMove> issueAndTrack(long productId, long warehouseId, BigDecimal qty, String workNo,
                                         String remark, Long issueId) {
        List<BatchMove> moves = consumeFifo(productId, warehouseId, qty, "ISSUE", "WORK_ORDER", workNo,
                remark == null || remark.isBlank() ? "生产领料" : remark, issueId);
        // 批次绑到工单上这件事归生产（那是 work_order_material_batch 的归属），走只读端口的写命令
        workOrderIssue.bindIssuedBatches(workNo, productId, moves);
        return moves;
    }

    @Transactional
    public void issue(long productId, long warehouseId, BigDecimal qty, String workNo, String remark, Long issueId) {
        issueAndTrack(productId, warehouseId, qty, workNo, remark, issueId);
    }

    /** 销售发货：返回实际扣减的成品批次，供发货单记录批次明细 */
    @Transactional
    public List<BatchMove> ship(long productId, long warehouseId, BigDecimal qty, String shipmentNo) {
        return consumeFifo(productId, warehouseId, qty, "SHIP", "SHIPMENT", shipmentNo, "销售发货");
    }

    @Transactional
    public void returnIn(long productId, long warehouseId, BigDecimal qty, String returnNo) {
        addBatch(productId, warehouseId, qty, "RETURN", "RETURN_ORDER", returnNo, "退货入库",
                "RT-" + returnNo, "RETURN", null, null, null);
    }

    @Transactional
    public void returnMaterial(long productId, long warehouseId, BigDecimal qty, String workNo) {
        addBatch(productId, warehouseId, qty, "RETURN_MAT", "WORK_ORDER_RETURN", workNo, "生产退料",
                "RET-" + workNo, "RETURN", null, null, null);
    }

    // ---------------------------------------------------------------- 库位：按库位看批次 / 移库

    @Override
    public List<BatchAtLocation> locationBatches(Long warehouseId, Collection<Long> productIds,
                                                 String batchNoLike) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where b.qty > 0 ");
        if (warehouseId != null) {
            where.append(" and b.warehouse_id = ? ");
            args.add(warehouseId);
        }
        if (productIds != null && !productIds.isEmpty()) {
            where.append(" and b.product_id in (")
                    .append(String.join(", ", java.util.Collections.nCopies(productIds.size(), "?")))
                    .append(") ");
            args.addAll(productIds);
        }
        if (batchNoLike != null && !batchNoLike.isBlank()) {
            where.append(" and b.batch_no ilike ? ");
            args.add("%" + batchNoLike.trim() + "%");
        }
        List<BatchAtLocation> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select b.id, b.product_id, b.warehouse_id, b.batch_no, b.qty, b.source, b.location_id,
                       to_char(b.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from inventory_batch b
                """ + where + " order by b.location_id nulls last, b.batch_no", args.toArray())) {
            rows.add(new BatchAtLocation(((Number) row.get("id")).longValue(),
                    ((Number) row.get("product_id")).longValue(),
                    ((Number) row.get("warehouse_id")).longValue(),
                    row.get("batch_no") == null ? null : String.valueOf(row.get("batch_no")),
                    new BigDecimal(String.valueOf(row.get("qty"))),
                    row.get("source") == null ? null : String.valueOf(row.get("source")),
                    row.get("location_id") == null ? null : ((Number) row.get("location_id")).longValue(),
                    row.get("createdAt") == null ? null : String.valueOf(row.get("createdAt"))));
        }
        return rows;
    }

    @Override
    public Map<Long, LocationOccupancy> locationOccupancy(Collection<Long> locationIds) {
        Map<Long, LocationOccupancy> map = new java.util.LinkedHashMap<>();
        List<Long> ids = locationIds == null ? List.of()
                : locationIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select location_id as "locationId",
                       count(*) filter (where qty <> 0) as "batchCount",
                       coalesce(sum(qty) filter (where qty > 0), 0) as "stockQty"
                from inventory_batch
                where location_id in (""" + marks + """
                )
                group by location_id
                """, ids.toArray())) {
            map.put(((Number) row.get("locationId")).longValue(),
                    new LocationOccupancy(((Number) row.get("batchCount")).longValue(),
                            new BigDecimal(String.valueOf(row.get("stockQty")))));
        }
        return map;
    }

    @Override
    public LocationUsage locationUsage(Long warehouseId) {
        Object[] args = warehouseId == null ? new Object[]{} : new Object[]{warehouseId};
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select coalesce(sum(case when location_id is not null then qty else 0 end), 0) as "assignedQty",
                       coalesce(sum(case when location_id is null then qty else 0 end), 0) as "unassignedQty",
                       count(*) filter (where location_id is not null) as "assignedBatches",
                       count(*) filter (where location_id is null) as "unassignedBatches"
                from inventory_batch b
                where b.qty > 0 """ + (warehouseId == null ? "" : " and b.warehouse_id = ? "), args);
        return new LocationUsage(new BigDecimal(String.valueOf(row.get("assignedQty"))),
                new BigDecimal(String.valueOf(row.get("unassignedQty"))),
                ((Number) row.get("assignedBatches")).longValue(),
                ((Number) row.get("unassignedBatches")).longValue());
    }

    @Override
    public long batchesInLocation(long locationId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from inventory_batch where location_id = ? and qty <> 0", Long.class, locationId);
        return count == null ? 0 : count;
    }

    @Override
    public void detachLocation(long locationId) {
        jdbcTemplate.update("update inventory_batch set location_id = null where location_id = ?", locationId);
    }

    @Override
    public BatchRef batchForUpdate(long productId, long warehouseId, String batchNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, product_id, warehouse_id, batch_no, qty, location_id
                from inventory_batch
                where product_id = ? and warehouse_id = ? and batch_no = ? for update
                """, productId, warehouseId, batchNo);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new BatchRef(((Number) row.get("id")).longValue(),
                ((Number) row.get("product_id")).longValue(),
                ((Number) row.get("warehouse_id")).longValue(),
                row.get("batch_no") == null ? null : String.valueOf(row.get("batch_no")),
                new BigDecimal(String.valueOf(row.get("qty"))),
                row.get("location_id") == null ? null : ((Number) row.get("location_id")).longValue());
    }

    @Override
    public void assignLocation(long productId, long warehouseId, String batchNo, long locationId) {
        jdbcTemplate.update("""
                update inventory_batch set location_id = ?, updated_at = now()
                where product_id = ? and warehouse_id = ? and batch_no = ?
                """, locationId, productId, warehouseId, batchNo);
    }

    @Override
    public void relocateBatch(long batchId, long toLocationId) {
        jdbcTemplate.update("""
                update inventory_batch set location_id = ?, updated_at = now(), version = version + 1
                where id = ?
                """, toLocationId, batchId);
    }

    @Override
    public void splitBatchToLocation(long batchId, String splitBatchNo, BigDecimal qty, long toLocationId) {
        Map<String, Object> batch = jdbcTemplate.queryForMap(
                "select product_id, warehouse_id from inventory_batch where id = ?", batchId);
        long productId = ((Number) batch.get("product_id")).longValue();
        long warehouseId = ((Number) batch.get("warehouse_id")).longValue();
        jdbcTemplate.update("""
                update inventory_batch set qty = qty - ?, updated_at = now(), version = version + 1
                where id = ?
                """, qty, batchId);
        // 目标库位建（或累加）一个新批次：同一批号可能已经分过几次，用 upsert 合并
        jdbcTemplate.update("""
                insert into inventory_batch (product_id, warehouse_id, batch_no, qty, source, location_id)
                values (?, ?, ?, ?, 'MOVE', ?)
                on conflict (product_id, warehouse_id, batch_no) do update
                set qty = inventory_batch.qty + excluded.qty,
                    location_id = excluded.location_id,
                    updated_at = now(),
                    version = inventory_batch.version + 1
                """, productId, warehouseId, splitBatchNo, qty, toLocationId);
    }

    /**
     * 生产退料（按原批次）：退回时沿用原领料批次号，批次库存重新累加，追溯链不断；
     * returnId 非空时库存流水回指退料单。
     */
    @Transactional
    public String returnMaterialBatch(long productId, long warehouseId, BigDecimal qty, String workNo,
                                      String batchNo, Long returnId) {
        return addBatch(productId, warehouseId, qty, "RETURN_MAT", "WORK_ORDER_RETURN", workNo, "生产退料",
                batchNo == null || batchNo.isBlank() ? "RET-" + workNo : batchNo, "RETURN", null, returnId, null);
    }

    /** 调拨：源仓按批次先进先出扣，目标仓沿用同一批次入库，批次不丢 */
    @Transactional
    public List<BatchMove> transfer(long productId, long fromWarehouseId, long toWarehouseId,
                                    BigDecimal qty, String transferNo) {
        List<BatchMove> moves = consumeFifo(productId, fromWarehouseId, qty, "OUT", "TRANSFER", transferNo, "调拨出库");
        for (BatchMove move : moves) {
            addBatch(productId, toWarehouseId, move.qty(), "IN", "TRANSFER", transferNo, "调拨入库",
                    move.batchNo(), "TRANSFER", null, null, null);
        }
        return moves;
    }

    /**
     * 兼容入口：正数入库（批次按来源单据号生成），负数按批次先进先出出库。
     */
    @Transactional
    public void change(long productId, long warehouseId, BigDecimal delta, String type,
                       String refType, String refNo, String remark) {
        if (delta == null || delta.signum() == 0) {
            throw new BizException(1002, "数量必须大于 0");
        }
        if (delta.signum() > 0) {
            addBatch(productId, warehouseId, delta, type, refType, refNo, remark, null, "INBOUND", null, null, null);
        } else {
            consumeFifo(productId, warehouseId, delta.negate(), type, refType, refNo, remark);
        }
    }

    /** 入库并累加批次库存 */
    @Transactional
    public String addBatch(long productId, long warehouseId, BigDecimal qty, String type,
                           String refType, String refNo, String remark,
                           String batchNo, String source, Long workOrderId) {
        return addBatch(productId, warehouseId, qty, type, refType, refNo, remark, batchNo, source,
                workOrderId, null);
    }

    /** 不带成本信息的入库：单价退回产品档案成本 */
    @Transactional
    public String addBatch(long productId, long warehouseId, BigDecimal qty, String type,
                           String refType, String refNo, String remark,
                           String batchNo, String source, Long workOrderId, Long returnId) {
        return addBatch(productId, warehouseId, qty, type, refType, refNo, remark, batchNo, source,
                workOrderId, returnId, null);
    }

    /** 生产退料等场景可带上 returnId，让库存流水能反查到退料单；unitCost 为空时按档案成本 */
    @Transactional
    public String addBatch(long productId, long warehouseId, BigDecimal qty, String type,
                           String refType, String refNo, String remark,
                           String batchNo, String source, Long workOrderId, Long returnId,
                           BigDecimal unitCost) {
        if (qty == null || qty.signum() <= 0) {
            throw new BizException(1002, "数量必须大于 0");
        }
        String batch = (batchNo == null || batchNo.isBlank()) ? deriveBatch(refType, refNo) : batchNo.trim();
        ensureInventoryRow(productId, warehouseId);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select qty, locked_qty from inventory where product_id = ? and warehouse_id = ? for update",
                productId, warehouseId);
        BigDecimal oldQty = new BigDecimal(String.valueOf(row.get("qty")));
        BigDecimal avgCost = productAvgCost(productId);
        // 没带单价（或带了 0）的入库退回产品档案成本，避免把均价冲成 0
        BigDecimal cost = unitCost != null && unitCost.signum() > 0 ? unitCost : productCost(productId);
        jdbcTemplate.update("""
                update inventory set qty = qty + ?, version = version + 1
                where product_id = ? and warehouse_id = ?
                """, qty, productId, warehouseId);
        jdbcTemplate.update("""
                insert into inventory_batch (product_id, warehouse_id, batch_no, qty, source, work_order_id)
                values (?, ?, ?, ?, ?, ?)
                on conflict (product_id, warehouse_id, batch_no) do update
                set qty = inventory_batch.qty + excluded.qty,
                    source = coalesce(inventory_batch.source, excluded.source),
                    work_order_id = coalesce(inventory_batch.work_order_id, excluded.work_order_id),
                    updated_at = now(),
                    version = inventory_batch.version + 1
                """, productId, warehouseId, batch, qty, source, workOrderId);
        // 移动加权：新均价 = (原数量×原均价 + 本次数量×本次单价) ÷ 新数量
        BigDecimal newQty = oldQty.add(qty);
        if (newQty.signum() > 0) {
            BigDecimal newAvg = oldQty.multiply(avgCost).add(qty.multiply(cost))
                    .divide(newQty, 6, java.math.RoundingMode.HALF_UP);
            // 成本字段在产品档案上，回写走主数据契约（算法留在库存这一侧）
            masterData.updateAvgCost(productId, newAvg);
        }
        writeTxn(productId, warehouseId, type, qty, refType, refNo, remark, batch, null, returnId, cost);
        return batch;
    }

    /** 按批次先进先出扣减，逐批次记流水 */
    @Transactional
    public List<BatchMove> consumeFifo(long productId, long warehouseId, BigDecimal qty, String type,
                                       String refType, String refNo, String remark) {
        return consumeFifo(productId, warehouseId, qty, type, refType, refNo, remark, null);
    }

    /** issueId 非空时，写出的库存流水会回指生产领料单 */
    public List<BatchMove> consumeFifo(long productId, long warehouseId, BigDecimal qty, String type,
                                       String refType, String refNo, String remark, Long issueId) {
        if (qty == null || qty.signum() <= 0) {
            throw new BizException(1002, "数量必须大于 0");
        }
        ensureInventoryRow(productId, warehouseId);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select qty, locked_qty from inventory where product_id = ? and warehouse_id = ? for update",
                productId, warehouseId);
        BigDecimal current = new BigDecimal(String.valueOf(row.get("qty")));
        BigDecimal locked = new BigDecimal(String.valueOf(row.get("locked_qty")));
        if (current.subtract(qty).signum() < 0) {
            throw new BizException(3001, "库存不足，当前可用 "
                    + current.subtract(locked).stripTrailingZeros().toPlainString());
        }
        reconcileBatches(productId, warehouseId, current);

        List<Map<String, Object>> batches = jdbcTemplate.queryForList("""
                select id, batch_no, qty from inventory_batch
                where product_id = ? and warehouse_id = ? and qty > 0
                order by created_at, id
                for update
                """, productId, warehouseId);
        BigDecimal available = BigDecimal.ZERO;
        for (Map<String, Object> batch : batches) {
            available = available.add(new BigDecimal(String.valueOf(batch.get("qty"))));
        }
        if (available.compareTo(qty) < 0) {
            throw new BizException(3001, "批次库存不足，可用 "
                    + available.stripTrailingZeros().toPlainString()
                    + "，需要 " + qty.stripTrailingZeros().toPlainString()
                    + "；请先在库存里补录批次");
        }

        List<BatchMove> moves = new ArrayList<>();
        BigDecimal remain = qty;
        BigDecimal unitCost = productAvgCost(productId);
        for (Map<String, Object> batch : batches) {
            if (remain.signum() <= 0) {
                break;
            }
            BigDecimal batchQty = new BigDecimal(String.valueOf(batch.get("qty")));
            BigDecimal take = batchQty.min(remain);
            jdbcTemplate.update("""
                    update inventory_batch set qty = qty - ?, updated_at = now(), version = version + 1
                    where id = ?
                    """, take, batch.get("id"));
            String batchNo = String.valueOf(batch.get("batch_no"));
            writeTxn(productId, warehouseId, type, take.negate(), refType, refNo, remark, batchNo,
                    issueId, null, unitCost);
            moves.add(new BatchMove(batchNo, take));
            remain = remain.subtract(take);
        }
        jdbcTemplate.update("""
                update inventory set qty = qty - ?, version = version + 1
                where product_id = ? and warehouse_id = ?
                """, qty, productId, warehouseId);
        return moves;
    }

    private void writeTxn(long productId, long warehouseId, String type, BigDecimal delta,
                          String refType, String refNo, String remark, String batchNo) {
        writeTxn(productId, warehouseId, type, delta, refType, refNo, remark, batchNo, null, null);
    }

    private void writeTxn(long productId, long warehouseId, String type, BigDecimal delta,
                          String refType, String refNo, String remark, String batchNo, Long issueId) {
        writeTxn(productId, warehouseId, type, delta, refType, refNo, remark, batchNo, issueId, null);
    }

    private void writeTxn(long productId, long warehouseId, String type, BigDecimal delta,
                          String refType, String refNo, String remark, String batchNo,
                          Long issueId, Long returnId) {
        writeTxn(productId, warehouseId, type, delta, refType, refNo, remark, batchNo,
                issueId, returnId, null);
    }

    private void writeTxn(long productId, long warehouseId, String type, BigDecimal delta,
                          String refType, String refNo, String remark, String batchNo,
                          Long issueId, Long returnId, BigDecimal unitCost) {
        String txnNo = codeGenerator.next("TX");
        BigDecimal cost = unitCost == null ? productAvgCost(productId) : unitCost;
        BigDecimal amount = delta.multiply(cost).setScale(2, java.math.RoundingMode.HALF_UP);
        jdbcTemplate.update("""
                insert into stock_txn (txn_no, product_id, warehouse_id, txn_type, qty,
                                       ref_type, ref_no, operator, remark, batch_no, issue_id, return_id,
                                       unit_cost, amount)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, txnNo, productId, warehouseId, type, delta, refType, refNo,
                CurrentUser.name(), remark, batchNo, issueId, returnId, cost, amount);
    }

    /** 产品档案成本：入库没带单价时用它兜底 */
    private BigDecimal productCost(long productId) {
        return masterData.product(productId).map(ProductInfo::cost)
                .filter(java.util.Objects::nonNull).orElse(BigDecimal.ZERO);
    }

    /** 产品移动加权平均成本：出库按它计价 */
    private BigDecimal productAvgCost(long productId) {
        return masterData.product(productId).map(ProductInfo::avgCost)
                .filter(java.util.Objects::nonNull).orElse(BigDecimal.ZERO);
    }

    private void ensureInventoryRow(long productId, long warehouseId) {
        jdbcTemplate.update("""
                insert into inventory (product_id, warehouse_id, qty) values (?, ?, 0)
                on conflict (product_id, warehouse_id) do nothing
                """, productId, warehouseId);
    }

    /**
     * 批次合计对不上汇总库存时，把差额补成一个兜底批次。
     * 历史数据、手工 SQL 补的库存都没有批次，不补的话会出现「库存有、批次没有」而扣不动。
     */
    private void reconcileBatches(long productId, long warehouseId, BigDecimal ledgerQty) {
        BigDecimal batchTotal = jdbcTemplate.queryForObject("""
                select coalesce(sum(qty), 0) from inventory_batch
                where product_id = ? and warehouse_id = ?
                """, BigDecimal.class, productId, warehouseId);
        BigDecimal total = batchTotal == null ? BigDecimal.ZERO : batchTotal;
        BigDecimal gap = ledgerQty.subtract(total);
        if (gap.signum() <= 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into inventory_batch (product_id, warehouse_id, batch_no, qty, source)
                values (?, ?, ?, ?, 'ADJUST')
                on conflict (product_id, warehouse_id, batch_no) do update
                set qty = inventory_batch.qty + excluded.qty,
                    updated_at = now(),
                    version = inventory_batch.version + 1
                """, productId, warehouseId, "AUTO-" + LocalDate.now(), gap);
    }

    private String deriveBatch(String refType, String refNo) {
        if (refNo != null && !refNo.isBlank()) {
            return refNo.trim();
        }
        return "AUTO-" + LocalDate.now();
    }

    public long warehouseOfType(String type) {
        return masterData.warehouseIdOfType(type);
    }

    public BigDecimal stock(long productId, long warehouseId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select qty from inventory where product_id = ? and warehouse_id = ?", productId, warehouseId);
        return rows.isEmpty() ? BigDecimal.ZERO : new BigDecimal(String.valueOf(rows.get(0).get("qty")));
    }

    /** 一批产品在所有仓库的结存合计；没有库存记录的产品不会出现在返回里 */
    @Override
    public Map<Long, BigDecimal> totalStocks(java.util.Collection<Long> productIds) {
        return sumByProduct("sum(qty)", productIds);
    }

    /** 一批产品在所有仓库的可用量合计（结存 - 锁定） */
    @Override
    public Map<Long, BigDecimal> availableStocks(java.util.Collection<Long> productIds) {
        return sumByProduct("sum(qty - locked_qty)", productIds);
    }

    @Override
    public List<StockLine> stocksIn(long warehouseId) {
        return jdbcTemplate.query("""
                select product_id, warehouse_id, qty, locked_qty from inventory
                where warehouse_id = ? order by product_id
                """, STOCK_LINE_MAPPER, warehouseId);
    }

    @Override
    public List<StockLine> allStocks() {
        return jdbcTemplate.query("""
                select product_id, warehouse_id, qty, locked_qty from inventory
                order by warehouse_id, product_id
                """, STOCK_LINE_MAPPER);
    }

    /** 按产品汇总的公共实现：空集合直接返回，避免拼出 in () 这种非法 SQL */
    private Map<Long, BigDecimal> sumByProduct(String aggregate, java.util.Collection<Long> productIds) {
        Map<Long, BigDecimal> result = new java.util.LinkedHashMap<>();
        if (productIds == null || productIds.isEmpty()) {
            return result;
        }
        List<Long> unique = new ArrayList<>(productIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList());
        if (unique.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(unique.size(), "?"));
        jdbcTemplate.query("select product_id, " + aggregate + " as total from inventory"
                        + " where product_id in (" + placeholders + ") group by product_id",
                rs -> {
                    result.put(rs.getLong("product_id"), rs.getBigDecimal("total"));
                }, unique.toArray());
        return result;
    }

    private Long warehouseScope() {
        if (!"WAREHOUSE".equals(CurrentUser.role())) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select warehouse_id from sys_user where username = ?", CurrentUser.name());
        if (rows.isEmpty() || rows.get(0).get("warehouse_id") == null) {
            return null;
        }
        return ((Number) rows.get(0).get("warehouse_id")).longValue();
    }
}
