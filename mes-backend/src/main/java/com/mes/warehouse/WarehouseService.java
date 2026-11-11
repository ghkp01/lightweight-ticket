package com.mes.warehouse;

import com.mes.andon.api.AndonPort;
import com.mes.common.BizException;
import com.mes.inventory.api.InventoryPort;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.platform.event.DomainEventPublisher;
import com.mes.production.api.WorkOrderPort;
import com.mes.purchase.api.PurchaseDocPort;
import com.mes.warehouse.api.InboundCompleted;
import com.mes.warehouse.api.InboundCompletionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓储单据公共能力：入库单整单入库。
 * 入库单提交（免检）与来料检验合格都走这里，保证库存流水与单据状态一致。
 *
 * <p>入库完成后的跨模块副作用（回写采购实收）通过 {@link InboundCompleted} 事件发布，
 * 不再直接调用采购模块的 Service —— 依赖方向从「仓储 → 采购」变成「采购订阅仓储的事件」。</p>
 *
 * <p>到货后的齐套判断：工单与 BOM 待领量问生产（{@link WorkOrderPort}）、采购价问采购
 * （{@link PurchaseDocPort}）、缺料安灯的关闭发命令给安灯（{@link AndonPort}）。</p>
 */
@Service
public class WarehouseService implements InboundCompletionPort {

    private final JdbcTemplate jdbcTemplate;
    private final InventoryPort inventoryService;
    private final DomainEventPublisher eventPublisher;
    private final NoticePort notice;
    private final LocationService locationService;
    private final WorkOrderPort workOrderPort;
    private final PurchaseDocPort purchaseDoc;
    private final AndonPort andonPort;

    public WarehouseService(JdbcTemplate jdbcTemplate, InventoryPort inventoryService,
                            DomainEventPublisher eventPublisher, NoticePort notice,
                            LocationService locationService, WorkOrderPort workOrderPort,
                            PurchaseDocPort purchaseDoc, AndonPort andonPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
        this.notice = notice;
        this.locationService = locationService;
        this.workOrderPort = workOrderPort;
        this.purchaseDoc = purchaseDoc;
        this.andonPort = andonPort;
    }

    @Override
    @Transactional
    public void completeInbound(long inboundOrderId, String inspectionNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select inbound_no, warehouse_id, status
                from inbound_order
                where id = ?
                for update
                """, inboundOrderId);
        if (rows.isEmpty()) {
            throw new BizException(4001, "入库单不存在");
        }
        Map<String, Object> order = rows.get(0);
        String status = String.valueOf(order.get("status"));
        if (!"DRAFT".equals(status) && !"PENDING_QC".equals(status)) {
            throw new BizException(4002, "只有草稿或待检入库单才能完成入库，当前状态：" + status);
        }
        String inboundNo = String.valueOf(order.get("inbound_no"));
        long warehouseId = ((Number) order.get("warehouse_id")).longValue();
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.product_id, i.qty, i.batch_no,
                       i.purchase_item_id as "purchaseItemId",
                       i.unit_price as "unitPrice",
                       i.location_code as "locationCode"
                from inbound_order_item i
                where i.inbound_id = ?
                order by i.id
                """, inboundOrderId);
        if (items.isEmpty()) {
            throw new BizException(4003, "入库单没有明细，不能完成入库");
        }
        // 采购价从采购契约取：明细自带单价为 0 时（比如手工改过）回落到订单价
        Map<Long, BigDecimal> purchasePrices = purchaseDoc.itemPrices(items.stream()
                .map(item -> item.get("purchaseItemId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .toList());
        for (Map<String, Object> item : items) {
            Object purchaseItemId = item.get("purchaseItemId");
            BigDecimal quote = purchaseItemId == null
                    ? BigDecimal.ZERO : purchasePrices.getOrDefault(((Number) purchaseItemId).longValue(),
                    BigDecimal.ZERO);
            BigDecimal unitPrice = decimal(item.get("unitPrice"));
            item.put("unitPrice", unitPrice.max(quote));
        }
        boolean byInspection = inspectionNo != null && !inspectionNo.isBlank();
        String refNo = byInspection ? inspectionNo : inboundNo;
        for (Map<String, Object> item : items) {
            long productId = ((Number) item.get("product_id")).longValue();
            BigDecimal qty = new BigDecimal(String.valueOf(item.get("qty")));
            String batchNo = item.get("batch_no") == null ? null : String.valueOf(item.get("batch_no"));
            String remark = byInspection ? "来料检验合格入库" : "入库单完成入库";
            if (batchNo != null && !batchNo.isBlank()) {
                remark = remark + "，批次 " + batchNo;
            }
            String batch = inventoryService.inboundWithBatch(productId, warehouseId, qty, "INBOUND", refNo,
                    remark, batchNo, "INBOUND", null, decimal(item.get("unitPrice")));
            // 指定了库位就上架，没指定就留空（后续可在库位页移库）
            Object locationCode = item.get("locationCode");
            if (locationCode != null && !String.valueOf(locationCode).isBlank()) {
                locationService.assignBatch(productId, warehouseId, batch, String.valueOf(locationCode));
            }
        }
        jdbcTemplate.update("""
                update inbound_order
                set status = 'COMPLETED', completed_at = now()
                where id = ?
                """, inboundOrderId);
        // 采购收货生成的入库单：回写采购明细实收并刷新采购单状态，由采购模块订阅事件处理
        eventPublisher.publish(new InboundCompleted(inboundOrderId, inboundNo, items.size(),
                OffsetDateTime.now()));
        // 到货后自动看一眼：哪些工单已经齐套可以领料，直接提醒仓库发料
        notifyKittedWorkOrders(inboundNo, items);
    }

    /**
     * 到货入库后自动判断齐套：本次到货的物料让哪些工单的待领量已经全部有货。
     * 齐套的工单给仓库/计划推一条「可以领料」，同时把之前自动报的缺料安灯关掉，
     * 仓库不用自己算什么时候能发料，也不用人工去消安灯。
     */
    private void notifyKittedWorkOrders(String inboundNo, List<Map<String, Object>> items) {
        Set<Long> productIds = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            if (item.get("product_id") != null) {
                productIds.add(((Number) item.get("product_id")).longValue());
            }
        }
        if (productIds.isEmpty()) {
            return;
        }
        try {
            // 候选工单：本次到货物料牵涉到、且还有待领行的工单（紧急程度由生产给）
            List<WorkOrderPort.KittingCandidate> candidates =
                    workOrderPort.kittingCandidates(productIds);
            if (candidates.isEmpty()) {
                return;
            }
            // 齐套判断需要「这些工单的所有待领行 + 对应结存」，各取一次算完
            List<Long> candidateIds = candidates.stream()
                    .map(WorkOrderPort.KittingCandidate::id).toList();
            List<WorkOrderPort.MaterialNeed> openLines = workOrderPort.openMaterialNeeds(candidateIds);
            Map<Long, BigDecimal> stocks = inventoryService.totalStocks(openLines.stream()
                    .map(WorkOrderPort.MaterialNeed::materialId).toList());
            Set<Long> shortOrders = new LinkedHashSet<>();
            for (WorkOrderPort.MaterialNeed line : openLines) {
                if (stocks.getOrDefault(line.materialId(), BigDecimal.ZERO).compareTo(line.needQty()) < 0) {
                    shortOrders.add(line.workOrderId());
                }
            }
            // 提醒按紧急程度排：在制的优先，其次是已下达，再看排产日期
            List<WorkOrderPort.KittingCandidate> kitted = candidates.stream()
                    .filter(candidate -> !shortOrders.contains(candidate.id()))
                    .sorted(Comparator
                            .comparingInt(WorkOrderPort.KittingCandidate::rank)
                            .thenComparing(candidate -> candidate.dueDate(),
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Comparator.comparingLong(
                                    WorkOrderPort.KittingCandidate::id).reversed()))
                    .toList();
            if (kitted.isEmpty()) {
                return;
            }
            // 已经把之前自动报的缺料安灯全部关掉：只要这张工单已经齐套，不管排在第几位
            andonPort.resolveMaterialShortage(kitted.stream()
                    .map(WorkOrderPort.KittingCandidate::id).toList());
            for (WorkOrderPort.KittingCandidate candidate : kitted.stream().limit(20).toList()) {
                String workNo = candidate.workNo();
                notice.pushToRoles(List.of("WAREHOUSE", "PLANNER"), NoticeMessage.of("INFO",
                        "工单 " + workNo + " 物料已齐套，可以领料",
                        "入库单 " + inboundNo + " 到货后该工单物料已满足领料，可直接发料并按工序领用。",
                        "WORK_ORDER", workNo));
            }
        } catch (Exception e) {
            // 齐套提醒是辅助能力，算不出来不能影响入库本身
        }
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
