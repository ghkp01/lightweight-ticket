package com.mes.warehouse;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.security.CurrentUser;
import com.mes.warehouse.api.LocationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库位：仓库下的货位维护、批次上架与移库。
 * 库存总量仍记在 inventory（产品 + 仓库）上，库位只细化到批次，
 * 这样不改变现有的先进先出与可用量口径，又能回答「这批货在哪个货位」。
 *
 * <p>库位表归仓储，批次归属归库存：所以这里出现的批次数据（按库位看批次、移库时的拆分）
 * 都走 {@link InventoryPort}，只有库位本身与移库单（{@code stock_move}）留在本模块。</p>
 */
@Service
public class LocationService implements LocationPort {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final MasterDataPort masterData;
    private final InventoryPort inventory;

    public LocationService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                           MasterDataPort masterData, InventoryPort inventory) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.masterData = masterData;
        this.inventory = inventory;
    }

    public record LocationRequest(Long id, Long warehouseId, String code, String name, String zone,
                                  Boolean enabled, String remark) {
    }

    public record MoveRequest(Long productId, Long warehouseId, String batchNo, BigDecimal qty,
                              Long toLocationId, String remark) {
    }

    public List<Map<String, Object>> list(Long warehouseId, String keyword) {
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (warehouseId != null) {
            where.append(" and l.warehouse_id = ? ");
            args.add(warehouseId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (l.code ilike ? or l.name ilike ? or coalesce(l.zone, '') ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select l.id, l.warehouse_id as "warehouseId",
                       l.code, l.name, l.zone, l.enabled, l.remark
                from warehouse_location l
                """ + where + " order by l.warehouse_id, l.code", args.toArray());
        // 批次数与占用量在库存那边，按库位批量问一次
        Map<Long, InventoryPort.LocationOccupancy> occupancy = inventory.locationOccupancy(
                rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList());
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            InventoryPort.LocationOccupancy usage = occupancy.get(id);
            row.put("batchCount", usage == null ? 0 : usage.batchCount());
            row.put("stockQty", usage == null ? BigDecimal.ZERO : usage.stockQty());
        }
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        return rows;
    }

    /**
     * 库位占用概览：已上架 / 未指定库位的批次与数量。
     * 库位只细化到批次，所以「未指定库位」的量仍然算在仓库总库存里，
     * 这里单独拎出来，避免看到库位页几乎全是 0 就以为仓库空了。
     */
    public Map<String, Object> stats(Long warehouseId) {
        InventoryPort.LocationUsage usage = inventory.locationUsage(warehouseId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignedQty", usage.assignedQty());
        result.put("unassignedQty", usage.unassignedQty());
        result.put("assignedBatches", usage.assignedBatches());
        result.put("unassignedBatches", usage.unassignedBatches());
        return result;
    }

    @Transactional
    public long save(LocationRequest request) {
        if (request.warehouseId() == null) {
            throw new BizException(1002, "请选择仓库");
        }
        String code = request.code() == null ? "" : request.code().trim();
        if (code.isEmpty()) {
            throw new BizException(1002, "库位编码不能为空");
        }
        String name = request.name() == null || request.name().isBlank() ? code : request.name().trim();
        if (masterData.warehouse(request.warehouseId()).isEmpty()) {
            throw new BizException(1002, "仓库不存在");
        }
        Long duplicated = jdbcTemplate.queryForObject("""
                select count(*) from warehouse_location
                where warehouse_id = ? and code = ? and id <> coalesce(?, 0)
                """, Long.class, request.warehouseId(), code,
                request.id() == null ? 0L : request.id());
        if (duplicated != null && duplicated > 0) {
            throw new BizException(4002, "该仓库下库位编码已存在：" + code);
        }
        if (request.id() != null) {
            int updated = jdbcTemplate.update("""
                    update warehouse_location
                    set warehouse_id = ?, code = ?, name = ?, zone = ?, enabled = ?, remark = ?
                    where id = ?
                    """, request.warehouseId(), code, name, request.zone(),
                    request.enabled() == null || request.enabled(), request.remark(), request.id());
            if (updated == 0) {
                throw new BizException(4001, "库位不存在");
            }
            return request.id();
        }
        return jdbcTemplate.queryForObject("""
                insert into warehouse_location (warehouse_id, code, name, zone, enabled, remark)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, request.warehouseId(), code, name, request.zone(),
                request.enabled() == null || request.enabled(), request.remark());
    }

    /** 删除库位：还有库存的货位不允许删，避免批次指向空库位 */
    @Transactional
    public void delete(long id) {
        if (inventory.batchesInLocation(id) > 0) {
            throw new BizException(4003, "该库位还有库存，请先移库或出库后再删除");
        }
        inventory.detachLocation(id);
        jdbcTemplate.update("delete from warehouse_location where id = ?", id);
    }

    /** 按仓库列出可用库位，供入库下拉 */
    public List<Map<String, Object>> options(Long warehouseId) {
        if (warehouseId == null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select l.id, l.code, l.name, l.warehouse_id as "warehouseId"
                    from warehouse_location l
                    where l.enabled order by l.warehouse_id, l.code
                    """);
            MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
            return rows;
        }
        return jdbcTemplate.queryForList("""
                select l.id, l.code, l.name, l.warehouse_id as "warehouseId"
                from warehouse_location l
                where l.enabled and l.warehouse_id = ? order by l.code
                """, warehouseId);
    }

    /** 入库上架：按批次把货位记到批次上，库位可空 */
    @Override
    @Transactional
    public void assignBatch(long productId, long warehouseId, String batchNo, String locationCode) {
        if (locationCode == null || locationCode.isBlank()) {
            return;
        }
        Long locationId = findLocation(warehouseId, locationCode.trim());
        if (locationId == null) {
            throw new BizException(4004, "库位不存在：" + locationCode);
        }
        inventory.assignLocation(productId, warehouseId, batchNo, locationId);
    }

    /** 批次库存分布：哪个批次在哪个货位、还有多少 */
    public List<Map<String, Object>> batchLocations(Long warehouseId, String keyword) {
        // 批次与库位归属在库存侧，库位编码/名称在仓储侧，各自取一次再拼
        List<Long> productIds = keyword == null || keyword.isBlank()
                ? List.of() : new ArrayList<>(masterData.searchProductIds(keyword));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InventoryPort.BatchAtLocation batch
                : inventory.locationBatches(warehouseId, productIds, keyword)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", batch.id());
            row.put("productId", batch.productId());
            row.put("warehouseId", batch.warehouseId());
            row.put("batchNo", batch.batchNo());
            row.put("qty", batch.qty());
            row.put("source", batch.source());
            row.put("locationId", batch.locationId());
            row.put("createdAt", batch.createdAt());
            rows.add(row);
        }
        fillLocationNames(rows);
        rows.sort(Comparator
                .comparing((Map<String, Object> row) -> (String) row.get("locationCode"),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> String.valueOf(row.get("batchNo"))));
        MasterDataViews.fillProductNames(masterData, rows);
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        return rows;
    }

    /** 补库位编码与名称：库位表是本模块自己的，一次查完 */
    private void fillLocationNames(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> row.get("locationId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, Map<String, Object>> locations = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, code, name from warehouse_location where id in ("
                        + String.join(", ", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                ids.toArray())) {
            locations.put(((Number) row.get("id")).longValue(), row);
        }
        for (Map<String, Object> row : rows) {
            Object id = row.get("locationId");
            Map<String, Object> location = id == null ? null : locations.get(((Number) id).longValue());
            row.put("locationCode", location == null ? null : location.get("code"));
            row.put("locationName", location == null ? null : location.get("name"));
        }
    }

    /**
     * 移库：批次换货位，不改变库存数量；
     * 批次数量大于移库数量时，只把移走的部分拆到新批次尾号，原批次保留剩余。
     */
    @Transactional
    public Map<String, Object> move(MoveRequest request) {
        if (request.productId() == null || request.warehouseId() == null || request.batchNo() == null
                || request.batchNo().isBlank()) {
            throw new BizException(1002, "请选择要移动的批次");
        }
        if (request.toLocationId() == null) {
            throw new BizException(1002, "请选择目标库位");
        }
        BigDecimal qty = request.qty() == null ? BigDecimal.ZERO : request.qty();
        if (qty.signum() <= 0) {
            throw new BizException(1002, "移库数量必须大于 0");
        }
        // 批次定位与拆分都在库存侧：仓储只保留「库位校验 + 移库单」
        InventoryPort.BatchRef batch = inventory.batchForUpdate(request.productId(),
                request.warehouseId(), request.batchNo());
        if (batch == null) {
            throw new BizException(4001, "批次不存在");
        }
        BigDecimal current = batch.qty();
        if (current.compareTo(qty) < 0) {
            throw new BizException(4005, "移库数量超过批次库存，当前 "
                    + current.stripTrailingZeros().toPlainString());
        }
        List<Map<String, Object>> target = jdbcTemplate.queryForList("""
                select id, warehouse_id, enabled from warehouse_location where id = ?
                """, request.toLocationId());
        if (target.isEmpty()) {
            throw new BizException(4004, "目标库位不存在");
        }
        if (!target.get(0).get("warehouse_id").equals(request.warehouseId())) {
            throw new BizException(1002, "目标库位不属于该仓库，跨仓调拨请走调拨单");
        }
        if (!Boolean.TRUE.equals(target.get(0).get("enabled"))) {
            throw new BizException(4004, "目标库位已停用");
        }
        Long fromLocation = batch.locationId();
        String moveNo = codeGenerator.next("MV");
        if (current.compareTo(qty) == 0) {
            inventory.relocateBatch(batch.id(), request.toLocationId());
        } else {
            // 部分移库：原批次扣减，目标库位建一个新批次（批次号加尾号区分）
            String splitBatch = request.batchNo() + "-" + request.toLocationId();
            inventory.splitBatchToLocation(batch.id(), splitBatch, qty, request.toLocationId());
        }
        jdbcTemplate.update("""
                insert into stock_move (move_no, product_id, warehouse_id, batch_no, qty,
                                        from_location_id, to_location_id, operator, remark)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, moveNo, request.productId(), request.warehouseId(), request.batchNo(), qty,
                fromLocation, request.toLocationId(), CurrentUser.name(), request.remark());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moveNo", moveNo);
        result.put("qty", qty);
        result.put("toLocationId", request.toLocationId());
        return result;
    }

    public List<Map<String, Object>> moves(Long warehouseId, int limit) {
        int size = limit <= 0 ? 100 : Math.min(limit, 500);
        String where = warehouseId == null ? "" : " where m.warehouse_id = ? ";
        Object[] args = warehouseId == null ? new Object[]{size} : new Object[]{warehouseId, size};
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select m.id, m.move_no as "moveNo", m.product_id as "productId",
                       m.warehouse_id as "warehouseId", m.batch_no as "batchNo", m.qty,
                       fl.code as "fromLocation", tl.code as "toLocation",
                       m.operator, m.remark,
                       to_char(m.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from stock_move m
                left join warehouse_location fl on fl.id = m.from_location_id
                join warehouse_location tl on tl.id = m.to_location_id
                """ + where + " order by m.id desc limit ?", args);
        MasterDataViews.fillProductNames(masterData, rows);
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        return rows;
    }

    private Long findLocation(long warehouseId, String code) {
        List<Long> ids = jdbcTemplate.queryForList("""
                select id from warehouse_location
                where warehouse_id = ? and code = ? and enabled
                """, Long.class, warehouseId, code);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
