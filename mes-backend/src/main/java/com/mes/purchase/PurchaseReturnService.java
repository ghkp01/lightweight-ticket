package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.platform.audit.OperLogService;
import com.mes.security.CurrentUser;
import com.mes.warehouse.api.InboundOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采购退货：从已完成的采购入库单退回供应商。
 * 提交退货时按先进先出扣减库存、回退采购单实收数量、冲减未开票的暂估；
 * 已经开票匹配的入库单必须先作废发票才能退货，避免暂估与发票对不上。
 *
 * <p>入库单与入库明细归仓储模块，这里通过 {@link InboundOrderPort} 取，
 * 可退数量 = 入库数量 − 已完成的退货数量（退货单是采购自己的表）。</p>
 */
@Service
public class PurchaseReturnService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final OperLogService operLogService;
    private final ApService apService;
    private final MasterDataPort masterData;
    private final InboundOrderPort inboundOrderPort;

    public PurchaseReturnService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                                 InventoryPort inventoryService, OperLogService operLogService,
                                 ApService apService, MasterDataPort masterData,
                                 InboundOrderPort inboundOrderPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.operLogService = operLogService;
        this.apService = apService;
        this.masterData = masterData;
        this.inboundOrderPort = inboundOrderPort;
    }

    public record ReturnItemRequest(Long inboundItemId, BigDecimal qty, String batchNo) {
    }

    public record ReturnRequest(Long inboundOrderId, LocalDate returnDate, String reason,
                                List<ReturnItemRequest> items) {
    }

    public PageResult list(String keyword, String status, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>(List.of(like, like));
        // 入库单号属于仓储模块，先按关键字换成 id 集合，再当普通条件用
        Set<Long> inboundIds = inboundOrderPort.searchIds(keyword);
        String inboundCondition = inboundIds.isEmpty()
                ? "1 = 0"
                : "r.inbound_order_id in (" + String.join(", ",
                        java.util.Collections.nCopies(inboundIds.size(), "?")) + ")";
        args.addAll(inboundIds);
        args.add(like);
        StringBuilder where = new StringBuilder(
                " where (r.return_no ilike ? or o.order_no ilike ? or " + inboundCondition
                        + " or s.name ilike ?) ");
        if (status != null && !status.isBlank()) {
            where.append(" and r.status = ?");
            args.add(status.trim());
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from purchase_return r
                join purchase_order o on o.id = r.purchase_order_id
                join supplier s on s.id = r.supplier_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select r.id, r.return_no as "returnNo", r.status,
                       r.purchase_order_id as "purchaseOrderId", o.order_no as "purchaseOrderNo",
                       r.inbound_order_id as "inboundOrderId",
                       r.supplier_id as "supplierId", s.name as "supplierName",
                       r.warehouse_id as "warehouseId",
                       ri.invoice_no as "redInvoiceNo",
                       to_char(r.return_date, 'YYYY-MM-DD') as "returnDate",
                       r.total_qty as "totalQty", r.total_amount as "totalAmount",
                       (select count(*) from purchase_return_item i where i.return_id = r.id) as "itemCount",
                       r.reason, r.created_by as "createdBy",
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(r.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from purchase_return r
                join purchase_order o on o.id = r.purchase_order_id
                join supplier s on s.id = r.supplier_id
                left join ap_invoice ri on ri.id = r.red_invoice_id
                """ + where + " order by r.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillWarehouseNames(masterData, records, "warehouseId", "warehouseName");
        fillInboundNos(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select r.id, r.return_no as "returnNo", r.status,
                       r.purchase_order_id as "purchaseOrderId", o.order_no as "purchaseOrderNo",
                       r.inbound_order_id as "inboundOrderId",
                       r.supplier_id as "supplierId", s.name as "supplierName",
                       r.warehouse_id as "warehouseId",
                       r.red_invoice_id as "redInvoiceId",
                       ri.invoice_no as "redInvoiceNo",
                       to_char(r.return_date, 'YYYY-MM-DD') as "returnDate",
                       r.total_qty as "totalQty", r.total_amount as "totalAmount",
                       r.reason, r.created_by as "createdBy",
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       r.completed_by as "completedBy",
                       to_char(r.completed_at, 'YYYY-MM-DD HH24:MI') as "completedAt"
                from purchase_return r
                join purchase_order o on o.id = r.purchase_order_id
                join supplier s on s.id = r.supplier_id
                left join ap_invoice ri on ri.id = r.red_invoice_id
                where r.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4012, "采购退货单不存在");
        }
        MasterDataViews.fillWarehouseNames(masterData, rows, "warehouseId", "warehouseName");
        fillInboundNos(rows);
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        detail.put("redInvoices", jdbcTemplate.queryForList("""
                select id, invoice_no as "invoiceNo", status, gross_amount as "grossAmount",
                       to_char(invoice_date, 'YYYY-MM-DD') as "invoiceDate"
                from ap_invoice where invoice_no like ?
                order by id
                """, "RC" + detail.get("returnNo") + "%"));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.qty,
                       i.batch_no as "batchNo", i.price, i.amount,
                       i.inbound_item_id as "inboundItemId",
                       i.purchase_order_item_id as "purchaseOrderItemId"
                from purchase_return_item i
                where i.return_id = ?
                order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        detail.put("items", items);
        return detail;
    }

    /** 可以发起退货的采购入库单：已完成、挂采购单、还有可退数量 */
    public List<Map<String, Object>> returnableInbounds(String keyword) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        // 入库单归仓储模块：先拿一批已完成的采购入库单，再在内存里按关键字过滤
        List<InboundOrderPort.InboundOrderView> candidates = inboundOrderPort.completedPurchaseInbounds(300);
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, List<InboundOrderPort.InboundLine>> lines =
                inboundOrderPort.linesByInbound(candidates.stream().map(InboundOrderPort.InboundOrderView::id).toList());
        Map<Long, BigDecimal> returned = returnedQtyByInboundItem(lines.values().stream()
                .flatMap(List::stream).map(InboundOrderPort.InboundLine::id).toList());
        Map<Long, String> orderNos = orderNosOf(candidates.stream()
                .map(InboundOrderPort.InboundOrderView::purchaseOrderId).toList());
        Map<Long, String> supplierNames = supplierNamesOf(candidates.stream()
                .map(InboundOrderPort.InboundOrderView::supplierId).toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (InboundOrderPort.InboundOrderView view : candidates) {
            String orderNo = orderNos.get(view.purchaseOrderId());
            String supplierName = supplierNames.get(view.supplierId());
            if (!matches(view.inboundNo(), like) && !matches(orderNo, like) && !matches(supplierName, like)) {
                continue;
            }
            int returnableLines = 0;
            for (InboundOrderPort.InboundLine line : lines.getOrDefault(view.id(), List.of())) {
                BigDecimal left = line.qty().subtract(returned.getOrDefault(line.id(), BigDecimal.ZERO));
                if (left.signum() > 0) {
                    returnableLines++;
                }
            }
            if (returnableLines == 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("inboundOrderId", view.id());
            row.put("inboundNo", view.inboundNo());
            row.put("purchaseOrderId", view.purchaseOrderId());
            row.put("purchaseOrderNo", orderNo);
            row.put("supplierName", supplierName);
            row.put("warehouseId", view.warehouseId());
            row.put("completedAt", view.completedAt());
            row.put("returnableLines", returnableLines);
            result.add(row);
            if (result.size() >= 100) {
                break;
            }
        }
        MasterDataViews.fillWarehouseNames(masterData, result, "warehouseId", "warehouseName");
        return result;
    }

    /** 某张入库单的可退明细：入库数量 - 已完成退货数量 */
    public List<Map<String, Object>> returnable(long inboundOrderId) {
        List<InboundOrderPort.InboundLine> lines = inboundOrderPort.lines(inboundOrderId);
        Map<Long, BigDecimal> prices = purchasePrices(lines.stream()
                .map(InboundOrderPort.InboundLine::purchaseItemId).toList());
        Map<Long, BigDecimal> returned = returnedQtyByInboundItem(
                lines.stream().map(InboundOrderPort.InboundLine::id).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (InboundOrderPort.InboundLine line : lines) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("inboundItemId", line.id());
            item.put("productId", line.productId());
            item.put("inboundQty", line.qty());
            item.put("batchNo", line.batchNo());
            item.put("purchaseOrderItemId", line.purchaseItemId());
            item.put("price", line.purchaseItemId() == null
                    ? BigDecimal.ZERO
                    : prices.getOrDefault(line.purchaseItemId(), BigDecimal.ZERO));
            BigDecimal used = returned.getOrDefault(line.id(), BigDecimal.ZERO);
            item.put("returnedQty", used);
            item.put("availableQty", line.qty().subtract(used).max(BigDecimal.ZERO));
            items.add(item);
        }
        MasterDataViews.fillProductNames(masterData, items);
        return items;
    }

    @Transactional
    public long create(ReturnRequest request) {
        if (request == null || request.inboundOrderId() == null) {
            throw new BizException(1002, "请选择要退货的采购入库单");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 2) {
            throw new BizException(1002, "请填写退货原因");
        }
        Map<String, Object> inbound = loadInbound(request.inboundOrderId());
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少选择一条退货明细");
        }

        Map<Long, BigDecimal> qtyByItem = new LinkedHashMap<>();
        Map<Long, String> batchByItem = new LinkedHashMap<>();
        for (ReturnItemRequest item : request.items()) {
            if (item == null || item.inboundItemId() == null) {
                continue;
            }
            if (item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "退货数量必须大于 0");
            }
            qtyByItem.merge(item.inboundItemId(), item.qty(), BigDecimal::add);
            if (item.batchNo() != null && !item.batchNo().isBlank()) {
                batchByItem.put(item.inboundItemId(), item.batchNo().trim());
            }
        }
        if (qtyByItem.isEmpty()) {
            throw new BizException(1002, "请至少选择一条退货明细");
        }

        Map<Long, Map<String, Object>> lines = new LinkedHashMap<>();
        for (Map<String, Object> line : returnable(request.inboundOrderId())) {
            lines.put(((Number) line.get("inboundItemId")).longValue(), line);
        }
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<Map<String, Object>> toSave = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : qtyByItem.entrySet()) {
            Map<String, Object> line = lines.get(entry.getKey());
            if (line == null) {
                throw new BizException(1002, "退货明细不属于该入库单，请刷新后重试");
            }
            BigDecimal available = decimal(line.get("availableQty"));
            if (entry.getValue().compareTo(available) > 0) {
                throw new BizException(4010, line.get("productName") + " 退货数量超过可退数量 "
                        + available.stripTrailingZeros().toPlainString());
            }
            BigDecimal price = decimal(line.get("price"));
            BigDecimal amount = entry.getValue().multiply(price).setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("inboundItemId", entry.getKey());
            row.put("purchaseOrderItemId", line.get("purchaseOrderItemId"));
            row.put("productId", line.get("productId"));
            row.put("qty", entry.getValue());
            row.put("batchNo", batchByItem.getOrDefault(entry.getKey(),
                    line.get("batchNo") == null ? null : String.valueOf(line.get("batchNo"))));
            row.put("price", price);
            row.put("amount", amount);
            toSave.add(row);
            totalQty = totalQty.add(entry.getValue());
            totalAmount = totalAmount.add(amount);
        }

        assertReturnableAccrual(inbound, totalAmount);

        String returnNo = codeGenerator.next("PRT");
        LocalDate returnDate = request.returnDate() == null ? LocalDate.now() : request.returnDate();
        Long returnId = jdbcTemplate.queryForObject("""
                insert into purchase_return (return_no, purchase_order_id, inbound_order_id, supplier_id,
                                             warehouse_id, status, return_date, total_qty, total_amount,
                                             reason, created_by)
                values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, returnNo, inbound.get("purchase_order_id"), request.inboundOrderId(),
                inbound.get("supplier_id"), inbound.get("warehouse_id"), returnDate, totalQty,
                totalAmount, reason, CurrentUser.name());
        for (Map<String, Object> row : toSave) {
            jdbcTemplate.update("""
                    insert into purchase_return_item (return_id, inbound_item_id, purchase_order_item_id,
                                                      product_id, qty, batch_no, price, amount)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, returnId, row.get("inboundItemId"), row.get("purchaseOrderItemId"),
                    row.get("productId"), row.get("qty"), row.get("batchNo"), row.get("price"),
                    row.get("amount"));
        }
        operLogService.touch("PURCHASE", longOf(inbound.get("purchase_order_id")),
                String.valueOf(inbound.get("order_no")),
                "对入库单 " + inbound.get("inbound_no") + " 发起采购退货 " + returnNo);
        operLogService.action("采购退货");
        return returnId;
    }

    /** 提交退货：扣库存、回退实收、已开票部分开红字发票红冲、冲减暂估 */
    @Transactional
    public Map<String, Object> complete(long id) {
        Map<String, Object> header = loadForUpdate(id);
        if (!"DRAFT".equals(String.valueOf(header.get("status")))) {
            throw new BizException(4013, "只有草稿退货单可以提交");
        }
        long inboundId = longOf(header.get("inbound_order_id"));
        long warehouseId = longOf(header.get("warehouse_id"));
        String returnNo = String.valueOf(header.get("return_no"));
        BigDecimal totalAmount = decimal(header.get("total_amount"));

        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select id, inbound_item_id, purchase_order_item_id, product_id, qty, batch_no, amount
                from purchase_return_item where return_id = ? order by id
                """, id);
        if (items.isEmpty()) {
            throw new BizException(1002, "退货单没有明细，不能提交");
        }
        // 再次核对可退数量，避免并发的两张退货单重复扣同一批货
        Map<Long, BigDecimal> available = new LinkedHashMap<>();
        for (Map<String, Object> line : returnable(inboundId)) {
            available.put(((Number) line.get("inboundItemId")).longValue(),
                    decimal(line.get("availableQty")));
        }
        for (Map<String, Object> item : items) {
            long inboundItemId = longOf(item.get("inbound_item_id"));
            BigDecimal qty = decimal(item.get("qty"));
            BigDecimal left = available.getOrDefault(inboundItemId, BigDecimal.ZERO);
            if (qty.compareTo(left) > 0) {
                throw new BizException(4010, "退货数量超过可退数量 "
                        + left.stripTrailingZeros().toPlainString() + "，请刷新后重试");
            }
        }

        Map<String, Object> inbound = loadInbound(inboundId);
        assertReturnableAccrual(inbound, totalAmount);

        for (Map<String, Object> item : items) {
            inventoryService.consumeFifo(longOf(item.get("product_id")), warehouseId,
                    decimal(item.get("qty")), "PURCHASE_RETURN", "PURCHASE_RETURN", returnNo,
                    "采购退货 " + returnNo + "，批次 "
                            + (item.get("batch_no") == null ? "-" : item.get("batch_no")));
        }

        // 回退采购单实收数量，并把采购单状态从「全部到货」退回「部分到货」
        long purchaseOrderId = longOf(header.get("purchase_order_id"));
        jdbcTemplate.update("""
                update purchase_order_item i
                set received_qty = greatest(i.received_qty - x.qty, 0)
                from (select purchase_order_item_id, sum(qty) as qty
                      from purchase_return_item
                      where return_id = ? and purchase_order_item_id is not null
                      group by purchase_order_item_id) x
                where i.id = x.purchase_order_item_id
                """, id);
        jdbcTemplate.update("""
                update purchase_order o
                set status = case when s.received <= 0 then 'CONFIRMED'
                                  when s.received < s.total then 'PARTIAL'
                                  else 'RECEIVED' end
                from (select coalesce(sum(qty), 0) as total, coalesce(sum(received_qty), 0) as received
                      from purchase_order_item where order_id = ?) s
                where o.id = ? and o.status in ('CONFIRMED', 'PARTIAL', 'RECEIVED')
                """, purchaseOrderId, purchaseOrderId);

        // 已开票的部分不能直接改暂估，要先开红字发票红冲，再冲减暂估
        List<Long> creditIds = creditForReturn(returnNo, inboundId, items);

        // 冲减该入库单的暂估（已开票部分已由红冲回退匹配）
        reduceAccrual(inboundId, items);

        jdbcTemplate.update("""
                update purchase_return
                set status = 'COMPLETED', completed_by = ?, completed_at = now(),
                    red_invoice_id = coalesce(?, red_invoice_id)
                where id = ?
                """, CurrentUser.name(), creditIds.isEmpty() ? null : creditIds.get(0), id);
        operLogService.touch("PURCHASE", purchaseOrderId, String.valueOf(inbound.get("order_no")),
                "采购退货 " + returnNo + " 提交，冲减暂估 " + totalAmount.stripTrailingZeros().toPlainString()
                        + " 元" + (creditIds.isEmpty() ? "" : "，生成红字发票 " + creditIds.size() + " 张"));
        operLogService.action("退货提交");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("returnNo", returnNo);
        result.put("redInvoiceIds", creditIds);
        result.put("redInvoiceCount", creditIds.size());
        return result;
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> header = loadForUpdate(id);
        if (!"DRAFT".equals(String.valueOf(header.get("status")))) {
            throw new BizException(4013, "只有草稿退货单可以取消");
        }
        jdbcTemplate.update("update purchase_return set status = 'CANCELLED' where id = ?", id);
        operLogService.touch("PURCHASE", longOf(header.get("purchase_order_id")),
                String.valueOf(header.get("return_no")), "取消采购退货单 " + header.get("return_no"));
        operLogService.action("退货取消");
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> header = loadForUpdate(id);
        if (!"DRAFT".equals(String.valueOf(header.get("status")))) {
            throw new BizException(4013, "只有草稿退货单可以删除");
        }
        jdbcTemplate.update("delete from purchase_return_item where return_id = ?", id);
        jdbcTemplate.update("delete from purchase_return where id = ?", id);
    }

    /** 退货金额不能超过该入库单的暂估总额，已开票部分通过红字发票红冲 */
    private void assertReturnableAccrual(Map<String, Object> inbound, BigDecimal returnAmount) {
        long inboundId = longOf(inbound.get("inbound_order_id"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, amount, matched_amount from ap_accrual where inbound_order_id = ?
                """, inboundId);
        if (rows.isEmpty()) {
            return;
        }
        BigDecimal amount = decimal(rows.get(0).get("amount"));
        if (returnAmount.compareTo(amount) > 0) {
            throw new BizException(4011, "退货金额超过该入库单暂估金额 "
                    + amount.stripTrailingZeros().toPlainString() + " 元");
        }
    }

    /**
     * 已开票匹配的部分：按蓝票明细行开红字发票并立即红冲。
     * 未匹配的数量直接冲暂估，匹配过的数量走红票，保证账实一致。
     */
    private List<Long> creditForReturn(String returnNo, long inboundId, List<Map<String, Object>> items) {
        Map<Long, List<ApService.CreditLine>> plan = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            Object poiId = item.get("purchase_order_item_id");
            if (poiId == null) {
                continue;
            }
            BigDecimal qty = decimal(item.get("qty"));
            BigDecimal openQty = jdbcTemplate.queryForObject("""
                    select coalesce(sum(greatest(ai.qty - ai.matched_qty, 0)), 0)
                    from ap_accrual_item ai
                    join ap_accrual a on a.id = ai.accrual_id
                    where a.inbound_order_id = ? and ai.purchase_order_item_id = ?
                    """, BigDecimal.class, inboundId, longOf(poiId));
            BigDecimal need = qty.subtract(openQty == null ? BigDecimal.ZERO : openQty);
            if (need.signum() <= 0) {
                continue;
            }
            List<Map<String, Object>> matches = jdbcTemplate.queryForList("""
                    select m.invoice_id as "invoiceId", m.invoice_item_id as "invoiceItemId", m.qty,
                           (ii.qty - coalesce((select -sum(ci.qty) from ap_invoice_item ci
                                               join ap_invoice c on c.id = ci.invoice_id
                                               where c.related_invoice_id = m.invoice_id
                                                 and c.status = 'MATCHED' and c.invoice_type = 'CREDIT'
                                                 and ci.purchase_order_item_id = ii.purchase_order_item_id), 0))
                               as "creditable"
                    from ap_invoice_match m
                    join ap_invoice i on i.id = m.invoice_id
                    join ap_invoice_item ii on ii.id = m.invoice_item_id
                    join ap_accrual_item ai on ai.id = m.accrual_item_id
                    join ap_accrual a on a.id = ai.accrual_id
                    where a.inbound_order_id = ? and ai.purchase_order_item_id = ?
                      and m.qty > 0 and i.invoice_type <> 'CREDIT'
                      and i.status in ('MATCHED', 'PARTIAL', 'PAID')
                    order by m.id
                    """, inboundId, longOf(poiId));
            BigDecimal remain = need;
            for (Map<String, Object> match : matches) {
                if (remain.signum() <= 0) {
                    break;
                }
                BigDecimal take = remain.min(decimal(match.get("qty")))
                        .min(decimal(match.get("creditable")));
                if (take.signum() <= 0) {
                    continue;
                }
                plan.computeIfAbsent(longOf(match.get("invoiceId")), key -> new ArrayList<>())
                        .add(new ApService.CreditLine(longOf(match.get("invoiceItemId")), take));
                remain = remain.subtract(take);
            }
            if (remain.signum() > 0) {
                throw new BizException(4011, "该入库单已开票数量不足，无法红冲本次退货，请核对发票");
            }
        }
        List<Long> creditIds = new ArrayList<>();
        int index = 0;
        for (Map.Entry<Long, List<ApService.CreditLine>> entry : plan.entrySet()) {
            index++;
            String creditNo = "RC" + returnNo + "-" + index;
            long creditId = apService.createCredit(new ApService.CreditRequest(creditNo, entry.getKey(),
                    LocalDate.now(), null, "采购退货 " + returnNo + " 红冲",
                    entry.getValue()));
            apService.matchCredit(creditId);
            creditIds.add(creditId);
        }
        return creditIds;
    }

    private void reduceAccrual(long inboundId, List<Map<String, Object>> items) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id from ap_accrual where inbound_order_id = ? for update
                """, inboundId);
        if (rows.isEmpty()) {
            return;
        }
        long accrualId = longOf(rows.get(0).get("id"));
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            total = total.add(decimal(item.get("amount")));
            Object purchaseItemId = item.get("purchase_order_item_id");
            if (purchaseItemId == null) {
                continue;
            }
            jdbcTemplate.update("""
                    update ap_accrual_item
                    set qty = greatest(qty - ?, 0), amount = greatest(amount - ?, 0)
                    where accrual_id = ? and purchase_order_item_id = ?
                    """, decimal(item.get("qty")), decimal(item.get("amount")), accrualId,
                    longOf(purchaseItemId));
        }
        jdbcTemplate.update("""
                update ap_accrual set amount = greatest(amount - ?, 0) where id = ?
                """, total, accrualId);
        // 冲减后重新汇总状态：金额与已匹配金额同步下降，剩余部分应为已匹配或被全部冲掉
        jdbcTemplate.update("""
                update ap_accrual a
                set matched_amount = x.matched,
                    status = case when x.matched >= a.amount then 'MATCHED'
                                  when x.matched > 0 then 'PARTIAL'
                                  else 'OPEN' end
                from (select coalesce(sum(matched_amount), 0) as matched
                      from ap_accrual_item where accrual_id = ?) x
                where a.id = ?
                """, accrualId, accrualId);
    }

    private Map<String, Object> loadInbound(long inboundId) {
        InboundOrderPort.InboundOrderView view = inboundOrderPort.inbound(inboundId);
        if (view == null) {
            throw new BizException(4001, "入库单不存在");
        }
        if (!"COMPLETED".equals(view.status())) {
            throw new BizException(4012, "只有已完成的采购入库单可以退货");
        }
        if (view.purchaseOrderId() == null || view.supplierId() == null) {
            throw new BizException(4012, "该入库单没有关联采购单与供应商，不能走采购退货");
        }
        List<String> orderNos = jdbcTemplate.queryForList(
                "select order_no from purchase_order where id = ?", String.class, view.purchaseOrderId());
        Map<String, Object> inbound = new LinkedHashMap<>();
        inbound.put("inbound_order_id", view.id());
        inbound.put("inbound_no", view.inboundNo());
        inbound.put("status", view.status());
        inbound.put("source", view.source());
        inbound.put("purchase_order_id", view.purchaseOrderId());
        inbound.put("supplier_id", view.supplierId());
        inbound.put("warehouse_id", view.warehouseId());
        inbound.put("order_no", orderNos.isEmpty() ? null : orderNos.get(0));
        return inbound;
    }

    /** 补入库单号：入库单归仓储，退货单只存 id */
    private void fillInboundNos(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> row.get("inboundOrderId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, InboundOrderPort.InboundOrderView> views = inboundOrderPort.inbounds(ids);
        for (Map<String, Object> row : rows) {
            Object id = row.get("inboundOrderId");
            InboundOrderPort.InboundOrderView view = id == null ? null : views.get(((Number) id).longValue());
            row.put("inboundNo", view == null ? null : view.inboundNo());
        }
    }

    /** 入库明细上已完成的退货数量，退货明细是采购自己的表 */
    private Map<Long, BigDecimal> returnedQtyByInboundItem(List<Long> inboundItemIds) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        List<Long> ids = inboundItemIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select x.inbound_item_id as "inboundItemId", sum(x.qty) as "qty"
                from purchase_return_item x
                join purchase_return pr on pr.id = x.return_id
                where pr.status = 'COMPLETED' and x.inbound_item_id in (""" + marks + """
                  )
                group by x.inbound_item_id
                """, ids.toArray())) {
            map.put(longOf(row.get("inboundItemId")), decimal(row.get("qty")));
        }
        return map;
    }

    private Map<Long, BigDecimal> purchasePrices(List<Long> purchaseItemIds) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        List<Long> ids = purchaseItemIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, price from purchase_order_item where id in (" + marks + ")", ids.toArray())) {
            map.put(longOf(row.get("id")), decimal(row.get("price")));
        }
        return map;
    }

    private Map<Long, String> orderNosOf(List<Long> purchaseOrderIds) {
        Map<Long, String> map = new LinkedHashMap<>();
        List<Long> ids = purchaseOrderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, order_no from purchase_order where id in (" + marks + ")", ids.toArray())) {
            map.put(longOf(row.get("id")), String.valueOf(row.get("order_no")));
        }
        return map;
    }

    private Map<Long, String> supplierNamesOf(List<Long> supplierIds) {
        Map<Long, String> map = new LinkedHashMap<>();
        List<Long> ids = supplierIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, name from supplier where id in (" + marks + ")", ids.toArray())) {
            map.put(longOf(row.get("id")), String.valueOf(row.get("name")));
        }
        return map;
    }

    /** 关键字是 SQL 里那套「两端模糊」的口径，搬到内存后要保持一致 */
    private static boolean matches(String value, String like) {
        if (value == null) {
            return false;
        }
        String keyword = like.substring(1, like.length() - 1);
        return value.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT));
    }

    private Map<String, Object> loadForUpdate(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, return_no, status, purchase_order_id, inbound_order_id, supplier_id,
                       warehouse_id, total_qty, total_amount, reason
                from purchase_return where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4012, "采购退货单不存在");
        }
        return rows.get(0);
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static long longOf(Object value) {
        return ((Number) value).longValue();
    }
}
