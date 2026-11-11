package com.mes.warehouse;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.inventory.api.InventoryPort;
import com.mes.inventory.api.StockLine;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.platform.scope.OrgScopeService;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StocktakeService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final OrgScopeService orgScope;
    private final MasterDataPort masterData;

    public StocktakeService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                            InventoryPort inventoryService,
                            OrgScopeService orgScope,
                            MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.orgScope = orgScope;
        this.masterData = masterData;
    }

    public record StocktakeItem(Long id, BigDecimal actualQty) {
    }

    public record StocktakeRequest(Long warehouseId, String remark) {
    }

    public record StocktakeSaveRequest(List<StocktakeItem> items) {
    }

    public PageResult list(String keyword, String status, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where s.stocktake_no ilike ? ");
        args.add(like);
        if (status != null && !status.isBlank()) {
            where.append(" and s.status = ? ");
            args.add(status);
        }
        MasterDataViews.appendWarehouseScope(orgScope, masterData, where, args, "s.warehouse_id");
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from stocktake s
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select s.id, s.stocktake_no as "stocktakeNo", s.warehouse_id as "warehouseId",
                       s.status,
                       (select count(*) from stocktake_item i where i.stocktake_id = s.id) as "itemCount",
                       (select count(*) from stocktake_item i
                         where i.stocktake_id = s.id and i.diff_qty <> 0) as "diffCount",
                       (select coalesce(sum(i.diff_qty), 0) from stocktake_item i where i.stocktake_id = s.id) as "totalDiff",
                       s.remark, s.created_by as "createdBy",
                       to_char(s.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(s.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from stocktake s
                """ + where + " order by s.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillWarehouseNames(masterData, records, "warehouseId", "warehouseName");
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select s.id, s.stocktake_no as "stocktakeNo", s.warehouse_id as "warehouseId",
                       s.status,
                       (select coalesce(sum(i.diff_qty), 0) from stocktake_item i where i.stocktake_id = s.id) as "totalDiff",
                       s.remark, s.created_by as "createdBy",
                       to_char(s.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(s.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from stocktake s
                where s.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4031, "盘点单不存在");
        }
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.book_qty as "bookQty",
                       i.actual_qty as "actualQty", i.diff_qty as "diffQty"
                from stocktake_item i
                where i.stocktake_id = ?
                order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        // 原来按产品编码排序（order by p.code），join 去掉后改在内存里排，口径不变
        items.sort(Comparator.comparing(row -> (String) row.get("productCode"),
                Comparator.nullsLast(Comparator.naturalOrder())));
        detail.put("items", items);
        return detail;
    }

    @Transactional
    public long create(StocktakeRequest request) {
        if (request.warehouseId() == null) {
            throw new BizException(1002, "请选择盘点仓库");
        }
        if (!masterData.warehouseEnabled(request.warehouseId())) {
            throw new BizException(1002, "盘点仓库不存在或已停用");
        }
        // 该仓的结存明细走库存契约：盘点单要按库存行生成明细，这是库存对外的合法读
        List<StockLine> stocks = inventoryService.stocksIn(request.warehouseId()).stream()
                .filter(line -> line.qty() != null && line.qty().signum() != 0)
                .toList();
        if (stocks.isEmpty()) {
            throw new BizException(1002, "该仓库暂无可盘点库存，无法创建盘点单");
        }
        String stocktakeNo = codeGenerator.next("ST");
        Long id = jdbcTemplate.queryForObject("""
                insert into stocktake (stocktake_no, warehouse_id, status, remark, created_by)
                values (?, ?, 'DRAFT', ?, ?)
                returning id
                """, Long.class, stocktakeNo, request.warehouseId(), request.remark(), CurrentUser.name());
        for (StockLine line : stocks) {
            jdbcTemplate.update("""
                    insert into stocktake_item (stocktake_id, product_id, book_qty, actual_qty, diff_qty)
                    values (?, ?, ?, ?, 0)
                    """, id, line.productId(), line.qty(), line.qty());
        }
        return id;
    }

    @Transactional
    public void save(long id, StocktakeSaveRequest request) {
        Map<String, Object> order = loadOrder(id);
        assertDraft(order, "只有草稿盘点单可以修改实盘数量");
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请填写实盘数量");
        }
        for (StocktakeItem item : request.items()) {
            if (item.id() == null || item.actualQty() == null || item.actualQty().signum() < 0) {
                throw new BizException(1002, "实盘数量不能为空且不能小于 0");
            }
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from stocktake_item where id = ? and stocktake_id = ?", Integer.class,
                    item.id(), id);
            if (exists == null || exists == 0) {
                throw new BizException(4034, "盘点明细不存在，请刷新后重试");
            }
            jdbcTemplate.update("""
                    update stocktake_item
                    set actual_qty = ?, diff_qty = ? - book_qty
                    where id = ? and stocktake_id = ?
                    """, item.actualQty(), item.actualQty(), item.id(), id);
        }
    }

    @Transactional
    public void complete(long id) {
        Map<String, Object> order = loadOrder(id);
        assertDraft(order, "只有草稿盘点单可以完成盘点");
        long warehouseId = ((Number) order.get("warehouse_id")).longValue();
        String stocktakeNo = String.valueOf(order.get("stocktake_no"));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select id, product_id, book_qty, actual_qty, diff_qty
                from stocktake_item where stocktake_id = ? order by id
                """, id);
        if (items.isEmpty()) {
            throw new BizException(4033, "盘点单没有明细，不能完成盘点");
        }
        for (Map<String, Object> item : items) {
            BigDecimal diff = new BigDecimal(String.valueOf(item.get("diff_qty")));
            if (diff.signum() == 0) {
                continue;
            }
            long productId = ((Number) item.get("product_id")).longValue();
            String remark = diff.signum() > 0 ? "盘点盘盈调整" : "盘点盘亏调整";
            inventoryService.change(productId, warehouseId, diff, "ADJUST", "STOCKTAKE", stocktakeNo, remark);
        }
        jdbcTemplate.update("""
                update stocktake set status = 'COMPLETED', completed_at = now() where id = ?
                """, id);
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> order = loadOrder(id);
        assertDraft(order, "只有草稿盘点单可以删除");
        jdbcTemplate.update("delete from stocktake_item where stocktake_id = ?", id);
        jdbcTemplate.update("delete from stocktake where id = ?", id);
    }

    private Map<String, Object> loadOrder(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, stocktake_no, status, warehouse_id from stocktake where id = ? for update", id);
        if (rows.isEmpty()) {
            throw new BizException(4031, "盘点单不存在");
        }
        return rows.get(0);
    }

    private void assertDraft(Map<String, Object> order, String message) {
        if (!"DRAFT".equals(String.valueOf(order.get("status")))) {
            throw new BizException(4032, message);
        }
    }
}
