package com.mes.warehouse;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.platform.scope.OrgScopeService;
import com.mes.production.api.WorkOrderPort;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OutboundOrderService {

    private static final List<String> TYPES = List.of("MANUAL", "SCRAP", "OTHER", "ISSUE_OTHER");

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final OrgScopeService orgScope;
    private final MasterDataPort masterData;
    private final WorkOrderPort workOrderPort;

    public OutboundOrderService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                                InventoryPort inventoryService,
                                OrgScopeService orgScope,
                                MasterDataPort masterData,
                                WorkOrderPort workOrderPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.orgScope = orgScope;
        this.masterData = masterData;
        this.workOrderPort = workOrderPort;
    }

    public record OutboundItem(Long productId, BigDecimal qty) {
    }

    public record OutboundRequest(String type, Long warehouseId, Long workOrderId, String remark,
                                  List<OutboundItem> items) {
    }

    public PageResult list(String keyword, String status, String type, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where o.outbound_no ilike ? ");
        args.add(like);
        if (status != null && !status.isBlank()) {
            where.append(" and o.status = ? ");
            args.add(status);
        }
        if (type != null && !type.isBlank()) {
            where.append(" and o.type = ? ");
            args.add(type);
        }
        MasterDataViews.appendWarehouseScope(orgScope, masterData, where, args, "o.warehouse_id");
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from outbound_order o
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select o.id, o.outbound_no as "outboundNo", o.type, o.warehouse_id as "warehouseId",
                       o.status,
                       o.work_order_id as "workOrderId",
                       (select count(*) from outbound_order_item i where i.outbound_id = o.id) as "itemCount",
                       (select coalesce(sum(i.qty), 0) from outbound_order_item i where i.outbound_id = o.id) as "totalQty",
                       o.remark, o.created_by as "createdBy",
                       to_char(o.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(o.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from outbound_order o
                """ + where + " order by o.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillWarehouseNames(masterData, records, "warehouseId", "warehouseName");
        fillWorkOrderNos(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id, o.outbound_no as "outboundNo", o.type, o.warehouse_id as "warehouseId",
                       o.status,
                       o.work_order_id as "workOrderId",
                       (select coalesce(sum(i.qty), 0) from outbound_order_item i where i.outbound_id = o.id) as "totalQty",
                       o.remark, o.created_by as "createdBy",
                       to_char(o.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(o.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from outbound_order o
                where o.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4011, "出库单不存在");
        }
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        fillWorkOrderNos(rows);
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.qty
                from outbound_order_item i
                where i.outbound_id = ?
                order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        detail.put("items", items);
        return detail;
    }

    @Transactional
    public long create(OutboundRequest request) {
        validate(request);
        String outboundNo = codeGenerator.next("OUT");
        String type = request.type() == null || request.type().isBlank() ? "MANUAL" : request.type();
        Long id = jdbcTemplate.queryForObject("""
                insert into outbound_order (outbound_no, type, warehouse_id, work_order_id, status, remark, created_by)
                values (?, ?, ?, ?, 'DRAFT', ?, ?)
                returning id
                """, Long.class, outboundNo, type, request.warehouseId(), request.workOrderId(),
                request.remark(), CurrentUser.name());
        insertItems(id, request.items());
        return id;
    }

    @Transactional
    public void update(long id, OutboundRequest request) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿出库单可以修改");
        validate(request);
        String type = request.type() == null || request.type().isBlank() ? "MANUAL" : request.type();
        jdbcTemplate.update("""
                update outbound_order set type = ?, warehouse_id = ?, work_order_id = ?, remark = ? where id = ?
                """, type, request.warehouseId(), request.workOrderId(), request.remark(), id);
        jdbcTemplate.update("delete from outbound_order_item where outbound_id = ?", id);
        insertItems(id, request.items());
    }

    @Transactional
    public void complete(long id) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿出库单可以完成出库");
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select product_id, qty from outbound_order_item where outbound_id = ? order by id
                """, id);
        if (items.isEmpty()) {
            throw new BizException(4013, "出库单没有明细，不能完成出库");
        }
        long warehouseId = ((Number) order.get("warehouse_id")).longValue();
        String outboundNo = String.valueOf(order.get("outbound_no"));
        for (Map<String, Object> item : items) {
            long productId = ((Number) item.get("product_id")).longValue();
            BigDecimal qty = new BigDecimal(String.valueOf(item.get("qty")));
            inventoryService.outbound(productId, warehouseId, qty, "OUTBOUND", outboundNo, "出库单完成出库");
        }
        jdbcTemplate.update("""
                update outbound_order set status = 'COMPLETED', completed_at = now() where id = ?
                """, id);
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿出库单可以取消");
        jdbcTemplate.update("update outbound_order set status = 'CANCELLED' where id = ?", id);
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿出库单可以删除");
        jdbcTemplate.update("delete from outbound_order_item where outbound_id = ?", id);
        jdbcTemplate.update("delete from outbound_order where id = ?", id);
    }

    private void insertItems(long id, List<OutboundItem> items) {
        for (OutboundItem item : items) {
            jdbcTemplate.update("""
                    insert into outbound_order_item (outbound_id, product_id, qty) values (?, ?, ?)
                    """, id, item.productId(), item.qty());
        }
    }

    private Map<String, Object> loadOrder(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, outbound_no, status, warehouse_id from outbound_order where id = ? for update", id);
        if (rows.isEmpty()) {
            throw new BizException(4011, "出库单不存在");
        }
        return rows.get(0);
    }

    private void assertStatus(Map<String, Object> order, String expected, String message) {
        if (!expected.equals(String.valueOf(order.get("status")))) {
            throw new BizException(4012, message);
        }
    }

    /** 补工单号：工单归生产模块，按 id 批量问一次 */
    private void fillWorkOrderNos(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> row.get("workOrderId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> workNos = workOrderPort.workNos(ids);
        for (Map<String, Object> row : rows) {
            Object id = row.get("workOrderId");
            row.put("workOrderNo", id == null ? null : workNos.get(((Number) id).longValue()));
        }
    }

    private void validate(OutboundRequest request) {
        if (request.warehouseId() == null) {
            throw new BizException(1002, "请选择出库仓库");
        }
        String type = request.type() == null || request.type().isBlank() ? "MANUAL" : request.type();
        if (!TYPES.contains(type)) {
            throw new BizException(1002, "出库类型不正确");
        }
        if ("ISSUE_OTHER".equals(type) && request.workOrderId() == null) {
            throw new BizException(1002, "车间领用必须选择工单");
        }
        if (request.workOrderId() != null && !workOrderPort.exists(request.workOrderId())) {
            throw new BizException(1002, "关联的工单不存在");
        }
        if (!masterData.warehouseEnabled(request.warehouseId())) {
            throw new BizException(1002, "出库仓库不存在或已停用");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少添加一条出库明细");
        }
        for (OutboundItem item : request.items()) {
            if (item.productId() == null || item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "出库明细的产品与数量不能为空，数量必须大于 0");
            }
            if (!masterData.productExists(item.productId())) {
                throw new BizException(1002, "产品不存在");
            }
        }
    }
}
