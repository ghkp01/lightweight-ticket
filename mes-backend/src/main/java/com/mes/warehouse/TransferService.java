package com.mes.warehouse;

import com.mes.approval.api.ApprovalPort;
import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.masterdata.api.WarehouseInfo;
import com.mes.platform.scope.OrgScopeService;
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
public class TransferService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final OrgScopeService orgScope;
    private final ApprovalPort approvalService;
    private final MasterDataPort masterData;

    public TransferService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                           InventoryPort inventoryService,
                           OrgScopeService orgScope,
                           ApprovalPort approvalService,
                           MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.orgScope = orgScope;
        this.approvalService = approvalService;
        this.masterData = masterData;
    }

    public record TransferItem(Long productId, BigDecimal qty) {
    }

    public record TransferRequest(Long fromWarehouseId, Long toWarehouseId, String remark,
                                  List<TransferItem> items) {
    }

    public PageResult list(String keyword, String status, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where t.transfer_no ilike ? ");
        args.add(like);
        if (status != null && !status.isBlank()) {
            where.append(" and t.status = ? ");
            args.add(status);
        }
        MasterDataViews.appendWarehouseScope(orgScope, masterData, where, args, "t.from_warehouse_id");
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from transfer_order t
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select t.id, t.transfer_no as "transferNo",
                       t.from_warehouse_id as "fromWarehouseId",
                       t.to_warehouse_id as "toWarehouseId",
                       t.status, t.cross_factory as "crossFactory",
                       t.approval_instance_id as "approvalInstanceId",
                       ai.status as "approvalStatus", ai.current_node as "approvalNode",
                       (select count(*) from transfer_order_item i where i.transfer_id = t.id) as "itemCount",
                       (select coalesce(sum(i.qty), 0) from transfer_order_item i where i.transfer_id = t.id) as "totalQty",
                       t.remark, t.created_by as "createdBy",
                       to_char(t.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(t.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from transfer_order t
                left join sys_approval_instance ai on ai.id = t.approval_instance_id
                """ + where + " order by t.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillWarehouseNames(masterData, records, "fromWarehouseId", "fromWarehouseName",
                "toWarehouseId", "toWarehouseName");
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select t.id, t.transfer_no as "transferNo",
                       t.from_warehouse_id as "fromWarehouseId",
                       t.to_warehouse_id as "toWarehouseId",
                       t.status, t.cross_factory as "crossFactory",
                       t.approval_instance_id as "approvalInstanceId",
                       ai.status as "approvalStatus", ai.current_node as "approvalNode",
                       (select coalesce(sum(i.qty), 0) from transfer_order_item i where i.transfer_id = t.id) as "totalQty",
                       t.remark, t.created_by as "createdBy",
                       to_char(t.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(t.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from transfer_order t
                left join sys_approval_instance ai on ai.id = t.approval_instance_id
                where t.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4021, "调拨单不存在");
        }
        MasterDataViews.fillWarehouseNames(masterData, rows, "fromWarehouseId", "fromWarehouseName",
                "toWarehouseId", "toWarehouseName");
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.qty
                from transfer_order_item i
                where i.transfer_id = ?
                order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        detail.put("items", items);
        return detail;
    }

    @Transactional
    public long create(TransferRequest request) {
        validate(request);
        String transferNo = codeGenerator.next("TR");
        Long id = jdbcTemplate.queryForObject("""
                insert into transfer_order (transfer_no, from_warehouse_id, to_warehouse_id, status, remark, created_by)
                values (?, ?, ?, 'DRAFT', ?, ?)
                returning id
                """, Long.class, transferNo, request.fromWarehouseId(), request.toWarehouseId(),
                request.remark(), CurrentUser.name());
        for (TransferItem item : request.items()) {
            jdbcTemplate.update("""
                    insert into transfer_order_item (transfer_id, product_id, qty) values (?, ?, ?)
                    """, id, item.productId(), item.qty());
        }
        // 跨厂（源仓与目标仓分属不同工厂）走审批；没有配置审批流时退回草稿，不阻断业务
        Long fromFactory = factoryOfWarehouse(request.fromWarehouseId());
        Long toFactory = factoryOfWarehouse(request.toWarehouseId());
        boolean cross = fromFactory != null && toFactory != null && !fromFactory.equals(toFactory);
        Long instanceId = null;
        String status = "DRAFT";
        if (cross) {
            BigDecimal totalQty = request.items().stream()
                    .map(TransferItem::qty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            instanceId = approvalService.submit("TRANSFER", id, transferNo, "跨厂调拨 " + transferNo,
                    java.util.Map.of("qty", totalQty, "amount", totalQty, "fromFactory", fromFactory,
                            "toFactory", toFactory));
            if (instanceId == null) {
                cross = false;
            } else {
                // 条件把节点全跳过时审批会立即通过，这时单据直接是已批准
                status = "APPROVED".equals(approvalService.statusOf(instanceId)) ? "APPROVED" : "PENDING_APPROVAL";
            }
        }
        jdbcTemplate.update("""
                update transfer_order set status = ?, cross_factory = ?, approval_instance_id = ?
                where id = ?
                """, status, cross, instanceId, id);
        return id;
    }

    private Long factoryOfWarehouse(long warehouseId) {
        return masterData.warehouse(warehouseId).map(WarehouseInfo::factoryOrgId).orElse(null);
    }

    @Transactional
    public void complete(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select transfer_no, status, from_warehouse_id, to_warehouse_id
                from transfer_order where id = ?
                for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4021, "调拨单不存在");
        }
        Map<String, Object> order = rows.get(0);
        String currentStatus = String.valueOf(order.get("status"));
        if ("PENDING_APPROVAL".equals(currentStatus)) {
            throw new BizException(4022, "跨厂调拨还在审批中，审批通过后才能执行");
        }
        if ("REJECTED".equals(currentStatus)) {
            throw new BizException(4022, "该跨厂调拨已被驳回，请修改后重新提交");
        }
        if (!"DRAFT".equals(currentStatus) && !"APPROVED".equals(currentStatus)) {
            throw new BizException(4022, "只有草稿或审批通过的调拨单可以完成调拨");
        }
        long fromWarehouseId = ((Number) order.get("from_warehouse_id")).longValue();
        long toWarehouseId = ((Number) order.get("to_warehouse_id")).longValue();
        String transferNo = String.valueOf(order.get("transfer_no"));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select product_id, qty from transfer_order_item where transfer_id = ? order by id
                """, id);
        if (items.isEmpty()) {
            throw new BizException(4023, "调拨单没有明细，不能完成调拨");
        }
        for (Map<String, Object> item : items) {
            long productId = ((Number) item.get("product_id")).longValue();
            BigDecimal qty = new BigDecimal(String.valueOf(item.get("qty")));
            // 批次跟着货一起走：源仓按先进先出扣，目标仓落同一批次
            inventoryService.transfer(productId, fromWarehouseId, toWarehouseId, qty, transferNo);
        }
        jdbcTemplate.update("""
                update transfer_order set status = 'COMPLETED', completed_at = now() where id = ?
                """, id);
    }

    @Transactional
    public void delete(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select status from transfer_order where id = ? for update", id);
        if (rows.isEmpty()) {
            throw new BizException(4021, "调拨单不存在");
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if (!"DRAFT".equals(status) && !"REJECTED".equals(status)) {
            throw new BizException(4024, "只有草稿或被驳回的调拨单可以删除");
        }
        jdbcTemplate.update("delete from transfer_order_item where transfer_id = ?", id);
        jdbcTemplate.update("delete from transfer_order where id = ?", id);
    }

    private void validate(TransferRequest request) {
        if (request.fromWarehouseId() == null || request.toWarehouseId() == null) {
            throw new BizException(1002, "请选择调出仓库与调入仓库");
        }
        if (request.fromWarehouseId().equals(request.toWarehouseId())) {
            throw new BizException(1002, "调出仓库与调入仓库不能相同");
        }
        if (!masterData.warehouseEnabled(request.fromWarehouseId())
                || !masterData.warehouseEnabled(request.toWarehouseId())) {
            throw new BizException(1002, "调拨仓库不存在或已停用");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少添加一条调拨明细");
        }
        for (TransferItem item : request.items()) {
            if (item.productId() == null || item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "调拨明细的产品与数量不能为空，数量必须大于 0");
            }
            if (!masterData.productExists(item.productId())) {
                throw new BizException(1002, "产品不存在");
            }
        }
    }
}
