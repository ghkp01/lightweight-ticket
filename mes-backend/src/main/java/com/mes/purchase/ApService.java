package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.masterdata.api.WarehouseInfo;
import com.mes.platform.audit.OperLogService;
import com.mes.platform.scope.OrgScopeService;
import com.mes.security.CurrentUser;
import com.mes.warehouse.api.InboundOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发票与应付：采购入库完成自动暂估，发票登记含税与税额，
 * 三单匹配（订单价、入库量、发票量）后冲暂估，付款与账龄。
 *
 * <p>入库单与入库明细归仓储模块，这里只通过 {@link InboundOrderPort} 回读；
 * 暂估的单价仍取采购自己的 {@code purchase_order_item.price}。</p>
 */
@Service
public class ApService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final OperLogService operLogService;
    private final OrgScopeService orgScope;
    private final MasterDataPort masterData;
    private final InboundOrderPort inboundOrderPort;

    public ApService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                     OperLogService operLogService,
                     OrgScopeService orgScope,
                     MasterDataPort masterData,
                     InboundOrderPort inboundOrderPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.operLogService = operLogService;
        this.orgScope = orgScope;
        this.masterData = masterData;
        this.inboundOrderPort = inboundOrderPort;
    }

    /**
     * 多工厂过滤片段：直接拼字面量，因为值来自数据库里的组织 id，不是外部输入。
     * 开关关闭或无工厂归属时返回空串，SQL 完全不变。
     */
    private String scopeSql(String column) {
        Long factory = orgScope.listFilter();
        if (factory == null) {
            return "";
        }
        return " and (" + column + " = " + factory + " or " + column + " is null) ";
    }

    public record InvoiceItemRequest(Long purchaseOrderItemId, BigDecimal qty, BigDecimal price, String remark) {
    }

    public record InvoiceRequest(String invoiceNo, String invoiceType, Long supplierId, Long purchaseOrderId,
                                 LocalDate invoiceDate, BigDecimal taxRate, String remark,
                                 List<InvoiceItemRequest> items) {
    }

    public record MatchRequest(Boolean force, String remark) {
    }

    public record PaymentRequest(LocalDate payDate, BigDecimal amount, String method,
                                 String referenceNo, String remark) {
    }

    /** 红字发票明细：按蓝票行退多少数量，单价沿用蓝票 */
    public record CreditLine(Long invoiceItemId, BigDecimal qty) {
    }

    public record CreditRequest(String invoiceNo, Long relatedInvoiceId, LocalDate invoiceDate,
                                BigDecimal taxRate, String remark, List<CreditLine> lines) {
    }

    public record RefundRequest(LocalDate refundDate, BigDecimal amount, String method,
                                String referenceNo, String remark) {
    }

    /** 采购入库完成时生成暂估，按入库单幂等 */
    @Transactional
    public void createAccrualForInbound(long inboundOrderId) {
        InboundOrderPort.InboundOrderView inbound = inboundOrderPort.inbound(inboundOrderId);
        if (inbound == null) {
            return;
        }
        if (inbound.purchaseOrderId() == null || inbound.supplierId() == null) {
            return;
        }
        // 暂估跟着入库仓库的工厂走，仓库归属问 masterdata
        Long inboundFactory = inbound.warehouseId() == null
                ? null
                : masterData.warehouse(inbound.warehouseId()).map(WarehouseInfo::factoryOrgId).orElse(null);
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from ap_accrual where inbound_order_id = ?", Integer.class, inboundOrderId);
        if (exists != null && exists > 0) {
            return;
        }
        // 暂估金额按入库数量 × 采购价：数量来自仓储契约，价格是采购自己的表
        List<InboundOrderPort.InboundLine> inboundLines = inboundOrderPort.lines(inboundOrderId);
        Map<Long, BigDecimal> prices = purchasePrices(inboundLines.stream()
                .map(InboundOrderPort.InboundLine::purchaseItemId).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (InboundOrderPort.InboundLine line : inboundLines) {
            if (line.purchaseItemId() == null) {
                continue;
            }
            BigDecimal price = prices.getOrDefault(line.purchaseItemId(), BigDecimal.ZERO);
            BigDecimal amount = line.qty().multiply(price).setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("purchaseItemId", line.purchaseItemId());
            item.put("qty", line.qty());
            item.put("price", price);
            item.put("amount", amount);
            items.add(item);
            total = total.add(amount);
        }
        if (total.signum() <= 0) {
            return;
        }
        String accrualNo = "AC" + inbound.inboundNo();
        Long factory = inboundFactory == null ? orgScope.writeFactoryOrgId() : inboundFactory;
        Long accrualId = jdbcTemplate.queryForObject("""
                insert into ap_accrual (accrual_no, purchase_order_id, inbound_order_id, supplier_id,
                                        accrual_date, amount, matched_amount, status, remark, factory_org_id)
                values (?, ?, ?, ?, ?, ?, 0, 'OPEN', ?, ?)
                returning id
                """, Long.class, accrualNo, inbound.purchaseOrderId(), inboundOrderId,
                inbound.supplierId(), inbound.completedDate() == null ? LocalDate.now() : inbound.completedDate(),
                total, "采购入库 " + inbound.inboundNo() + " 自动暂估", factory);
        for (Map<String, Object> item : items) {
            jdbcTemplate.update("""
                    insert into ap_accrual_item (accrual_id, purchase_order_item_id, qty, amount,
                                                 matched_qty, matched_amount)
                    values (?, ?, ?, ?, 0, 0)
                    """, accrualId, item.get("purchaseItemId"), item.get("qty"), item.get("amount"));
        }
        operLogService.touch("AP", accrualId, accrualNo,
                "采购入库 " + inbound.inboundNo() + " 生成暂估 " + total.stripTrailingZeros().toPlainString());
        operLogService.action("暂估");
    }

    public PageResult accruals(Long supplierId, String status, String keyword, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        // 入库单号属于仓储模块，先按关键字换成 id 集合
        Set<Long> inboundIds = inboundOrderPort.searchIds(keyword);
        String inboundCondition = inboundIds.isEmpty()
                ? "1 = 0"
                : "a.inbound_order_id in (" + String.join(", ",
                        java.util.Collections.nCopies(inboundIds.size(), "?")) + ")";
        StringBuilder where = new StringBuilder(
                " where (a.accrual_no ilike ? or " + inboundCondition + " or o.order_no ilike ?"
                        + " or s.name ilike ?) ");
        List<Object> args = new ArrayList<>(List.of(like));
        args.addAll(inboundIds);
        args.add(like);
        args.add(like);
        if (supplierId != null) {
            where.append(" and a.supplier_id = ?");
            args.add(supplierId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and a.status = ?");
            args.add(status.trim());
        }
        orgScope.appendScope(where, args, "a.factory_org_id");
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from ap_accrual a
                join purchase_order o on o.id = a.purchase_order_id
                join supplier s on s.id = a.supplier_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select a.id, a.accrual_no as "accrualNo", a.purchase_order_id as "purchaseOrderId",
                       o.order_no as "purchaseOrderNo", a.inbound_order_id as "inboundOrderId",
                       a.supplier_id as "supplierId", s.name as "supplierName",
                       to_char(a.accrual_date, 'YYYY-MM-DD') as "accrualDate",
                       a.amount, a.matched_amount as "matchedAmount",
                       (a.amount - a.matched_amount) as "openAmount", a.status, a.remark,
                       to_char(a.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from ap_accrual a
                join purchase_order o on o.id = a.purchase_order_id
                join supplier s on s.id = a.supplier_id
                """ + where + " order by a.id desc limit ? offset ?", pageArgs.toArray());
        fillInboundNos(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> summary() {
        // 多工厂：概览的每个口径都按同一工厂过滤，避免几个数字口径不一致
        String aScope = scopeSql("factory_org_id");
        String iScope = scopeSql("factory_org_id");
        String pScope = scopeSql("i.factory_org_id");
        String sql = """
                select coalesce((select sum(amount - matched_amount) from ap_accrual
                                 where status <> 'MATCHED'%s), 0) as "accrualOpen",
                       coalesce((select sum(greatest(gross_amount - paid_amount, 0)) from ap_invoice
                                 where status in ('MATCHED', 'PARTIAL')
                                   and invoice_type <> 'CREDIT'%s), 0) as "invoiceOpen",
                       coalesce((select sum(greatest(gross_amount - paid_amount, 0)) from ap_invoice
                                 where status in ('MATCHED', 'PARTIAL') and invoice_type <> 'CREDIT'
                                   and due_date < current_date%s), 0) as "overdue",
                       coalesce((select sum(p.amount) from ap_payment p
                                 join ap_invoice i on i.id = p.invoice_id
                                 where p.pay_date >= date_trunc('month', current_date)%s), 0) as "monthPaid",
                       (select count(*) from ap_invoice where status = 'DRAFT'%s) as "pendingMatch",
                       (select count(*) from ap_invoice where status in ('MATCHED', 'PARTIAL')
                         and invoice_type <> 'CREDIT'%s) as "openInvoiceCount",
                       coalesce((select sum(refundable_amount) from ap_invoice
                                 where refundable_amount > 0%s), 0) as "refundable",
                       coalesce((select -sum(gross_amount) from ap_invoice
                                 where invoice_type = 'CREDIT' and status = 'MATCHED'
                                   and matched_at >= date_trunc('month', current_date)%s), 0) as "monthCredit"
                """.formatted(aScope, iScope, iScope, pScope, iScope, iScope, iScope, iScope);
        return jdbcTemplate.queryForMap(sql);
    }

    public List<Map<String, Object>> invoiceableLines(Long supplierId) {
        List<Object> args = new ArrayList<>();
        args.add(supplierId);
        StringBuilder where = new StringBuilder(
                " where o.supplier_id = ? and o.status in ('PARTIAL', 'RECEIVED') ");
        MasterDataViews.appendWarehouseScope(orgScope, masterData, where, args, "o.warehouse_id");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select * from (
                    select poi.id as "purchaseOrderItemId", o.id as "purchaseOrderId",
                           o.order_no as "orderNo", poi.product_id as "productId", poi.qty,
                           poi.received_qty as "receivedQty", poi.price,
                           coalesce((select sum(ii.qty) from ap_invoice_item ii
                                     join ap_invoice inv on inv.id = ii.invoice_id
                                     where ii.purchase_order_item_id = poi.id
                                       and inv.status <> 'CANCELLED'), 0) as "invoicedQty",
                           poi.received_qty - coalesce((select sum(ii.qty) from ap_invoice_item ii
                                     join ap_invoice inv on inv.id = ii.invoice_id
                                     where ii.purchase_order_item_id = poi.id
                                       and inv.status <> 'CANCELLED'), 0) as "invoiceableQty"
                    from purchase_order_item poi
                    join purchase_order o on o.id = poi.order_id
                """ + where + """
                ) t
                where t."invoiceableQty" > 0
                order by t."orderNo", t."purchaseOrderItemId"
                """, args.toArray());
        MasterDataViews.fillProductNames(masterData, rows);
        return rows;
    }

    public PageResult invoices(String keyword, String status, Long supplierId, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder where = new StringBuilder("""
                 where (i.invoice_no ilike ? or s.name ilike ? or coalesce(o.order_no, '') ilike ?)
                """);
        List<Object> args = new ArrayList<>(List.of(like, like, like));
        if (status != null && !status.isBlank()) {
            where.append(" and i.status = ?");
            args.add(status.trim());
        }
        if (supplierId != null) {
            where.append(" and i.supplier_id = ?");
            args.add(supplierId);
        }
        orgScope.appendScope(where, args, "i.factory_org_id");
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from ap_invoice i
                join supplier s on s.id = i.supplier_id
                left join purchase_order o on o.id = i.purchase_order_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select i.id, i.invoice_no as "invoiceNo", i.invoice_type as "invoiceType",
                       i.supplier_id as "supplierId", s.name as "supplierName",
                       i.purchase_order_id as "purchaseOrderId", o.order_no as "purchaseOrderNo",
                       i.related_invoice_id as "relatedInvoiceId",
                       ri.invoice_no as "relatedInvoiceNo",
                       to_char(i.invoice_date, 'YYYY-MM-DD') as "invoiceDate",
                       to_char(i.due_date, 'YYYY-MM-DD') as "dueDate",
                       i.tax_rate as "taxRate", i.net_amount as "netAmount", i.tax_amount as "taxAmount",
                       i.gross_amount as "grossAmount", i.paid_amount as "paidAmount",
                       i.refundable_amount as "refundableAmount",
                       greatest(i.gross_amount - i.paid_amount, 0) as "remainingAmount",
                       i.difference_amount as "differenceAmount", i.difference_note as "differenceNote",
                       i.status, to_char(i.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       (i.invoice_type <> 'CREDIT' and i.due_date < current_date
                        and i.status in ('MATCHED', 'PARTIAL')) as "overdue"
                from ap_invoice i
                join supplier s on s.id = i.supplier_id
                left join purchase_order o on o.id = i.purchase_order_id
                left join ap_invoice ri on ri.id = i.related_invoice_id
                """ + where + " order by i.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> invoiceDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.id, i.invoice_no as "invoiceNo", i.invoice_type as "invoiceType",
                       i.supplier_id as "supplierId", s.name as "supplierName", s.code as "supplierCode",
                       s.payment_term as "paymentTerm", s.credit_days as "creditDays",
                       i.purchase_order_id as "purchaseOrderId", o.order_no as "purchaseOrderNo",
                       i.related_invoice_id as "relatedInvoiceId",
                       ri.invoice_no as "relatedInvoiceNo",
                       i.refundable_amount as "refundableAmount",
                       to_char(i.invoice_date, 'YYYY-MM-DD') as "invoiceDate",
                       to_char(i.due_date, 'YYYY-MM-DD') as "dueDate",
                       i.tax_rate as "taxRate", i.net_amount as "netAmount", i.tax_amount as "taxAmount",
                       i.gross_amount as "grossAmount", i.paid_amount as "paidAmount",
                       greatest(i.gross_amount - i.paid_amount, 0) as "remainingAmount",
                       i.difference_amount as "differenceAmount", i.difference_note as "differenceNote",
                       i.status, i.matched_by as "matchedBy",
                       to_char(i.matched_at, 'YYYY-MM-DD HH24:MI') as "matchedAt",
                       i.remark, i.created_by as "createdBy",
                       to_char(i.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from ap_invoice i
                join supplier s on s.id = i.supplier_id
                left join purchase_order o on o.id = i.purchase_order_id
                left join ap_invoice ri on ri.id = i.related_invoice_id
                where i.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2012, "发票不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> invoiceItems = jdbcTemplate.queryForList("""
                select ii.id, ii.purchase_order_item_id as "purchaseOrderItemId",
                       o.order_no as "orderNo", poi.product_id as "productId",
                       ii.qty, ii.price, ii.amount, ii.matched_qty as "matchedQty", ii.remark
                from ap_invoice_item ii
                join purchase_order_item poi on poi.id = ii.purchase_order_item_id
                join purchase_order o on o.id = poi.order_id
                where ii.invoice_id = ?
                order by ii.id
                """, id);
        MasterDataViews.fillProductNames(masterData, invoiceItems);
        detail.put("items", invoiceItems);
        detail.put("matches", jdbcTemplate.queryForList("""
                select m.id, a.accrual_no as "accrualNo", m.qty, m.amount,
                       to_char(m.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from ap_invoice_match m
                join ap_accrual_item ai on ai.id = m.accrual_item_id
                join ap_accrual a on a.id = ai.accrual_id
                where m.invoice_id = ?
                order by m.id
                """, id));
        detail.put("payments", jdbcTemplate.queryForList("""
                select p.id, p.payment_no as "paymentNo", to_char(p.pay_date, 'YYYY-MM-DD') as "payDate",
                       p.amount, p.method, p.reference_no as "referenceNo", p.remark,
                       p.created_by as "createdBy",
                       to_char(p.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from ap_payment p
                where p.invoice_id = ?
                order by p.id
                """, id));
        detail.put("refunds", jdbcTemplate.queryForList("""
                select r.id, r.refund_no as "refundNo", to_char(r.refund_date, 'YYYY-MM-DD') as "refundDate",
                       r.amount, r.method, r.reference_no as "referenceNo", r.remark,
                       r.created_by as "createdBy",
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from ap_refund r
                where r.invoice_id = ?
                order by r.id
                """, id));
        detail.put("creditInvoices", jdbcTemplate.queryForList("""
                select c.id, c.invoice_no as "invoiceNo", c.status, c.gross_amount as "grossAmount",
                       to_char(c.invoice_date, 'YYYY-MM-DD') as "invoiceDate"
                from ap_invoice c
                where c.related_invoice_id = ?
                order by c.id
                """, id));
        return detail;
    }

    @Transactional
    public long createInvoice(InvoiceRequest request) {
        assertNotCreditType(request);
        validateInvoice(request, null);
        Map<String, Object> supplier = loadSupplier(request.supplierId());
        LocalDate invoiceDate = request.invoiceDate() == null ? LocalDate.now() : request.invoiceDate();
        BigDecimal taxRate = request.taxRate() == null ? BigDecimal.valueOf(13) : request.taxRate();
        BigDecimal gross = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (InvoiceItemRequest item : request.items()) {
            Map<String, Object> poi = loadPurchaseItem(item.purchaseOrderItemId(), request.supplierId());
            BigDecimal amount = item.qty().multiply(item.price()).setScale(2, RoundingMode.HALF_UP);
            gross = gross.add(amount);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("poi", poi);
            line.put("qty", item.qty());
            line.put("price", item.price());
            line.put("amount", amount);
            line.put("remark", blankToNull(item.remark()));
            lines.add(line);
        }
        BigDecimal[] split = splitTax(gross, taxRate);
        long creditDays = ((Number) supplier.get("credit_days")).longValue();
        Long purchaseOrderId = request.purchaseOrderId();
        if (purchaseOrderId == null && !lines.isEmpty()) {
            purchaseOrderId = ((Number) ((Map<String, Object>) lines.get(0).get("poi")).get("order_id")).longValue();
        }
        // 发票的工厂随采购单收货仓库，没有关联采购单时落到当前账号所属工厂
        Long factory = null;
        if (purchaseOrderId != null) {
            // 采购单的工厂靠「收货仓库」决定：先取本模块的 warehouse_id，再问 masterdata 要归属
            List<Long> warehouseIds = jdbcTemplate.queryForList(
                    "select warehouse_id from purchase_order where id = ?", Long.class, purchaseOrderId);
            if (!warehouseIds.isEmpty() && warehouseIds.get(0) != null) {
                factory = masterData.warehouse(warehouseIds.get(0))
                        .map(WarehouseInfo::factoryOrgId).orElse(null);
            }
        }
        if (factory == null) {
            factory = orgScope.writeFactoryOrgId();
        }
        Long id = jdbcTemplate.queryForObject("""
                insert into ap_invoice (invoice_no, invoice_type, supplier_id, purchase_order_id, invoice_date,
                                        tax_rate, net_amount, tax_amount, gross_amount, paid_amount,
                                        status, due_date, remark, created_by, factory_org_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'DRAFT', ?, ?, ?, ?)
                returning id
                """, Long.class, request.invoiceNo().trim(), invoiceTypeOf(request.invoiceType()),
                request.supplierId(), purchaseOrderId, invoiceDate, taxRate,
                split[0], split[1], gross, invoiceDate.plusDays(creditDays),
                blankToNull(request.remark()), CurrentUser.name(), factory);
        for (Map<String, Object> line : lines) {
            Map<String, Object> poi = (Map<String, Object>) line.get("poi");
            jdbcTemplate.update("""
                    insert into ap_invoice_item (invoice_id, purchase_order_item_id, qty, price, amount,
                                                 matched_qty, remark)
                    values (?, ?, ?, ?, ?, 0, ?)
                    """, id, poi.get("id"), line.get("qty"), line.get("price"), line.get("amount"),
                    line.get("remark"));
        }
        operLogService.touch("AP", id, request.invoiceNo().trim(),
                "登记发票 " + request.invoiceNo().trim() + "，供应商 " + supplier.get("name")
                        + "，价税合计 " + gross.stripTrailingZeros().toPlainString());
        return id;
    }

    @Transactional
    public void updateInvoice(long id, InvoiceRequest request) {
        assertNotCreditType(request);
        Map<String, Object> invoice = loadInvoiceForUpdate(id);
        assertStatus(invoice, "DRAFT", "只有待匹配的发票可以修改");
        validateInvoice(request, id);
        Map<String, Object> supplier = loadSupplier(request.supplierId());
        LocalDate invoiceDate = request.invoiceDate() == null ? LocalDate.now() : request.invoiceDate();
        BigDecimal taxRate = request.taxRate() == null ? BigDecimal.valueOf(13) : request.taxRate();
        BigDecimal gross = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (InvoiceItemRequest item : request.items()) {
            Map<String, Object> poi = loadPurchaseItem(item.purchaseOrderItemId(), request.supplierId());
            BigDecimal amount = item.qty().multiply(item.price()).setScale(2, RoundingMode.HALF_UP);
            gross = gross.add(amount);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("poi", poi);
            line.put("qty", item.qty());
            line.put("price", item.price());
            line.put("amount", amount);
            line.put("remark", blankToNull(item.remark()));
            lines.add(line);
        }
        BigDecimal[] split = splitTax(gross, taxRate);
        long creditDays = ((Number) supplier.get("credit_days")).longValue();
        Long purchaseOrderId = request.purchaseOrderId();
        if (purchaseOrderId == null && !lines.isEmpty()) {
            purchaseOrderId = ((Number) ((Map<String, Object>) lines.get(0).get("poi")).get("order_id")).longValue();
        }
        jdbcTemplate.update("""
                update ap_invoice set invoice_type = ?, supplier_id = ?, purchase_order_id = ?,
                       invoice_date = ?, tax_rate = ?, net_amount = ?, tax_amount = ?, gross_amount = ?,
                       due_date = ?, remark = ?
                where id = ?
                """, invoiceTypeOf(request.invoiceType()), request.supplierId(), purchaseOrderId,
                invoiceDate, taxRate, split[0], split[1], gross, invoiceDate.plusDays(creditDays),
                blankToNull(request.remark()), id);
        jdbcTemplate.update("delete from ap_invoice_item where invoice_id = ?", id);
        for (Map<String, Object> line : lines) {
            Map<String, Object> poi = (Map<String, Object>) line.get("poi");
            jdbcTemplate.update("""
                    insert into ap_invoice_item (invoice_id, purchase_order_item_id, qty, price, amount,
                                                 matched_qty, remark)
                    values (?, ?, ?, ?, ?, 0, ?)
                    """, id, poi.get("id"), line.get("qty"), line.get("price"), line.get("amount"),
                    line.get("remark"));
        }
    }

    @Transactional
    public void deleteInvoice(long id) {
        Map<String, Object> invoice = loadInvoiceForUpdate(id);
        assertStatus(invoice, "DRAFT", "只有待匹配的发票可以删除");
        jdbcTemplate.update("delete from ap_invoice_item where invoice_id = ?", id);
        jdbcTemplate.update("delete from ap_invoice where id = ?", id);
    }

    /**
     * 登记红字发票：必须挂在已匹配的蓝字发票上，按蓝票明细行填退货数量，
     * 单价沿用蓝票，金额与税额取负。红字发票本身不参与付款与账龄。
     */
    @Transactional
    public long createCredit(CreditRequest request) {
        if (request == null || request.relatedInvoiceId() == null) {
            throw new BizException(1002, "红字发票必须选择要红冲的蓝字发票");
        }
        if (request.invoiceNo() == null || request.invoiceNo().trim().isEmpty()) {
            throw new BizException(1002, "红字发票号不能为空");
        }
        if (request.invoiceDate() != null && request.invoiceDate().isAfter(LocalDate.now())) {
            throw new BizException(1002, "开票日期不能晚于今天");
        }
        Map<String, Object> blue = loadInvoiceForUpdate(request.relatedInvoiceId());
        if ("CREDIT".equals(String.valueOf(blue.get("invoice_type")))) {
            throw new BizException(1002, "红字发票不能再作为蓝票被红冲");
        }
        if (!List.of("MATCHED", "PARTIAL", "PAID").contains(String.valueOf(blue.get("status")))) {
            throw new BizException(2014, "只有已匹配的蓝字发票可以开红字发票");
        }
        Integer duplicate = jdbcTemplate.queryForObject(
                "select count(*) from ap_invoice where invoice_no = ?", Integer.class, request.invoiceNo().trim());
        if (duplicate != null && duplicate > 0) {
            throw new BizException(2015, "发票号已存在");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new BizException(1002, "请至少填写一条红冲明细");
        }
        List<Map<String, Object>> blueItems = jdbcTemplate.queryForList("""
                select ii.id, ii.purchase_order_item_id as "poiId", ii.qty, ii.price,
                       ii.matched_qty as "matchedQty",
                       coalesce((select -sum(ci.qty) from ap_invoice_item ci
                                 join ap_invoice c on c.id = ci.invoice_id
                                 where c.related_invoice_id = ?
                                   and c.status = 'MATCHED' and c.invoice_type = 'CREDIT'
                                   and ci.purchase_order_item_id = ii.purchase_order_item_id), 0) as "creditedQty"
                from ap_invoice_item ii
                where ii.invoice_id = ?
                order by ii.id
                """, request.relatedInvoiceId(), request.relatedInvoiceId());
        Map<Long, Map<String, Object>> byItemId = new LinkedHashMap<>();
        for (Map<String, Object> item : blueItems) {
            byItemId.put(((Number) item.get("id")).longValue(), item);
        }
        BigDecimal gross = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (CreditLine line : request.lines()) {
            if (line == null || line.invoiceItemId() == null) {
                throw new BizException(1002, "红冲明细必须选择蓝票行");
            }
            if (line.qty() == null || line.qty().signum() <= 0) {
                throw new BizException(1002, "红冲数量必须大于 0");
            }
            Map<String, Object> blueItem = byItemId.get(line.invoiceItemId());
            if (blueItem == null) {
                throw new BizException(1002, "红冲明细不属于该蓝字发票");
            }
            BigDecimal invoiced = decimal(blueItem.get("qty"));
            BigDecimal credited = decimal(blueItem.get("creditedQty"));
            BigDecimal matched = decimal(blueItem.get("matchedQty"));
            BigDecimal available = invoiced.subtract(credited);
            if (line.qty().compareTo(available) > 0) {
                throw new BizException(2021, "红冲数量超过可红冲数量 " + available.stripTrailingZeros().toPlainString());
            }
            if (line.qty().compareTo(matched) > 0) {
                throw new BizException(2021, "红冲数量超过该行已匹配数量 " + matched.stripTrailingZeros().toPlainString());
            }
            BigDecimal price = decimal(blueItem.get("price"));
            BigDecimal amount = line.qty().multiply(price).negate().setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("poiId", ((Number) blueItem.get("poiId")).longValue());
            row.put("qty", line.qty().negate());
            row.put("price", price);
            row.put("amount", amount);
            lines.add(row);
            gross = gross.add(amount);
        }
        if (gross.signum() >= 0) {
            throw new BizException(1002, "红字发票金额必须为负");
        }
        BigDecimal taxRate = request.taxRate() != null ? request.taxRate() : decimal(blue.get("tax_rate"));
        BigDecimal[] split = splitTax(gross, taxRate);
        Long id = jdbcTemplate.queryForObject("""
                insert into ap_invoice (invoice_no, invoice_type, supplier_id, purchase_order_id, invoice_date,
                                        tax_rate, net_amount, tax_amount, gross_amount, paid_amount,
                                        refundable_amount, status, remark, created_by, related_invoice_id,
                                        factory_org_id)
                values (?, 'CREDIT', ?, ?, ?, ?, ?, ?, ?, 0, 0, 'DRAFT', ?, ?, ?, ?)
                returning id
                """, Long.class, request.invoiceNo().trim(), blue.get("supplier_id"), blue.get("purchase_order_id"),
                request.invoiceDate() == null ? LocalDate.now() : request.invoiceDate(), taxRate,
                split[0], split[1], gross, blankToNull(request.remark()), CurrentUser.name(),
                request.relatedInvoiceId(), blue.get("factory_org_id"));
        for (Map<String, Object> line : lines) {
            jdbcTemplate.update("""
                    insert into ap_invoice_item (invoice_id, purchase_order_item_id, qty, price, amount,
                                                 matched_qty)
                    values (?, ?, ?, ?, ?, 0)
                    """, id, line.get("poiId"), line.get("qty"), line.get("price"), line.get("amount"));
        }
        operLogService.touch("AP", id, request.invoiceNo().trim(),
                "登记红字发票 " + request.invoiceNo().trim() + " 红冲蓝票 " + blue.get("invoice_no")
                        + "，红冲金额 " + gross.abs().stripTrailingZeros().toPlainString());
        operLogService.action("红字发票");
        return id;
    }

    /**
     * 红冲：按暂估明细先进先出回退已匹配数量与金额，蓝票金额同步减少；
     * 已付款超过红冲后应付的部分转为「待供应商退款」。
     */
    @Transactional
    public Map<String, Object> matchCredit(long id) {
        Map<String, Object> credit = loadInvoiceForUpdate(id);
        if (!"CREDIT".equals(String.valueOf(credit.get("invoice_type")))) {
            throw new BizException(1002, "只有红字发票可以做红冲");
        }
        assertStatus(credit, "DRAFT", "只有待红冲的红字发票可以提交红冲");
        long blueId = ((Number) credit.get("related_invoice_id")).longValue();
        Map<String, Object> blue = loadInvoiceForUpdate(blueId);
        String blueStatus = String.valueOf(blue.get("status"));
        if (List.of("CANCELLED", "RED_REVERSED").contains(blueStatus)) {
            throw new BizException(2014, "蓝字发票已作废或已全额红冲，不能再红冲");
        }
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select id, purchase_order_item_id as "poiId", qty, amount
                from ap_invoice_item where invoice_id = ? order by id
                """, id);
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (Map<String, Object> item : items) {
            long poiId = ((Number) item.get("poiId")).longValue();
            BigDecimal needQty = decimal(item.get("qty")).negate();
            BigDecimal needAmount = decimal(item.get("amount")).negate();
            List<Map<String, Object>> accrualItems = jdbcTemplate.queryForList("""
                    select id, matched_qty, matched_amount from ap_accrual_item
                    where purchase_order_item_id = ? and matched_qty > 0
                    order by id
                    """, poiId);
            BigDecimal remainQty = needQty;
            BigDecimal remainAmount = needAmount;
            for (Map<String, Object> accrualItem : accrualItems) {
                if (remainQty.signum() <= 0 && remainAmount.signum() <= 0) {
                    break;
                }
                BigDecimal takeQty = remainQty.min(decimal(accrualItem.get("matched_qty")));
                BigDecimal takeAmount = remainAmount.min(decimal(accrualItem.get("matched_amount")));
                if (takeQty.signum() <= 0) {
                    continue;
                }
                Map<String, Object> allocation = new LinkedHashMap<>();
                allocation.put("invoiceItemId", item.get("id"));
                allocation.put("accrualItemId", accrualItem.get("id"));
                allocation.put("qty", takeQty);
                allocation.put("amount", takeAmount);
                allocations.add(allocation);
                remainQty = remainQty.subtract(takeQty);
                remainAmount = remainAmount.subtract(takeAmount);
            }
            if (remainQty.signum() > 0) {
                throw new BizException(2021, "该采购行可红冲的匹配记录不足，请检查蓝票是否已部分红冲");
            }
        }
        for (Map<String, Object> allocation : allocations) {
            jdbcTemplate.update("""
                    insert into ap_invoice_match (invoice_id, invoice_item_id, accrual_item_id, qty, amount)
                    values (?, ?, ?, ?, ?)
                    """, id, allocation.get("invoiceItemId"), allocation.get("accrualItemId"),
                    decimal(allocation.get("qty")).negate(), decimal(allocation.get("amount")).negate());
            jdbcTemplate.update("""
                    update ap_accrual_item
                    set matched_qty = greatest(matched_qty - ?, 0),
                        matched_amount = greatest(matched_amount - ?, 0)
                    where id = ?
                    """, allocation.get("qty"), allocation.get("amount"), allocation.get("accrualItemId"));
            jdbcTemplate.update("""
                    update ap_invoice_item set matched_qty = greatest(matched_qty - ?, 0)
                    where invoice_id = ? and purchase_order_item_id = (
                        select purchase_order_item_id from ap_invoice_item where id = ?)
                    """, allocation.get("qty"), blueId, allocation.get("invoiceItemId"));
        }
        BigDecimal creditGross = decimal(credit.get("gross_amount"));
        BigDecimal newGross = decimal(blue.get("gross_amount")).add(creditGross).max(BigDecimal.ZERO);
        BigDecimal newNet = decimal(blue.get("net_amount")).add(decimal(credit.get("net_amount")));
        BigDecimal newTax = decimal(blue.get("tax_amount")).add(decimal(credit.get("tax_amount")));
        BigDecimal paid = decimal(blue.get("paid_amount"));
        BigDecimal refundable = paid.subtract(newGross).max(BigDecimal.ZERO);
        String newStatus;
        if (newGross.signum() == 0 && paid.signum() == 0) {
            newStatus = "RED_REVERSED";
        } else if (paid.compareTo(newGross) > 0) {
            newStatus = "PAID";
        } else if (paid.signum() == 0) {
            newStatus = "MATCHED";
        } else {
            newStatus = "PARTIAL";
        }
        jdbcTemplate.update("""
                update ap_invoice set gross_amount = ?, net_amount = ?, tax_amount = ?,
                       refundable_amount = ?, status = ?
                where id = ?
                """, newGross, newNet, newTax, refundable, newStatus, blueId);
        refreshAccruals(blueId);
        jdbcTemplate.update("""
                update ap_invoice set status = 'MATCHED', matched_by = ?, matched_at = now() where id = ?
                """, CurrentUser.name(), id);
        operLogService.touch("AP", blueId, String.valueOf(blue.get("invoice_no")),
                "红字发票 " + credit.get("invoice_no") + " 红冲蓝票 " + blue.get("invoice_no")
                        + "，红冲 " + creditGross.abs().stripTrailingZeros().toPlainString()
                        + " 元，蓝票剩余应付 " + newGross.stripTrailingZeros().toPlainString() + " 元");
        operLogService.action("红冲");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("creditId", id);
        result.put("blueInvoiceId", blueId);
        result.put("blueGrossAmount", newGross);
        result.put("blueStatus", newStatus);
        result.put("refundableAmount", refundable);
        result.put("reversed", allocations.size());
        return result;
    }

    /** 供应商退款：红冲后已付款多于应付时登记，冲减发票的待退款金额 */
    @Transactional
    public Map<String, Object> refund(long invoiceId, RefundRequest request) {
        Map<String, Object> invoice = loadInvoiceForUpdate(invoiceId);
        if ("CREDIT".equals(String.valueOf(invoice.get("invoice_type")))) {
            throw new BizException(2014, "红字发票不能登记退款，请到对应的蓝字发票上操作");
        }
        BigDecimal refundable = decimal(invoice.get("refundable_amount"));
        if (refundable.signum() <= 0) {
            throw new BizException(2014, "该发票没有待供应商退款金额");
        }
        if (request == null || request.amount() == null || request.amount().signum() <= 0) {
            throw new BizException(1002, "退款金额必须大于 0");
        }
        if (request.amount().compareTo(refundable) > 0) {
            throw new BizException(1002, "退款金额超过待退款金额 " + refundable.stripTrailingZeros().toPlainString());
        }
        String method = request.method() == null || request.method().isBlank()
                ? "BANK" : request.method().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("BANK", "CASH", "OTHER").contains(method)) {
            throw new BizException(1002, "退款方式只能是 BANK / CASH / OTHER");
        }
        String refundNo = codeGenerator.next("RF");
        jdbcTemplate.update("""
                insert into ap_refund (refund_no, invoice_id, supplier_id, refund_date, amount, method,
                                       reference_no, remark, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, refundNo, invoiceId, invoice.get("supplier_id"),
                request.refundDate() == null ? LocalDate.now() : request.refundDate(), request.amount(),
                method, blankToNull(request.referenceNo()), blankToNull(request.remark()), CurrentUser.name());
        BigDecimal remain = refundable.subtract(request.amount());
        jdbcTemplate.update("update ap_invoice set refundable_amount = ? where id = ?", remain, invoiceId);
        operLogService.touch("AP", invoiceId, String.valueOf(invoice.get("invoice_no")),
                "发票 " + invoice.get("invoice_no") + " 收到供应商退款 "
                        + request.amount().stripTrailingZeros().toPlainString() + "，剩余待退 "
                        + remain.stripTrailingZeros().toPlainString());
        operLogService.action("供应商退款");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundNo", refundNo);
        result.put("refundableAmount", remain);
        return result;
    }

    /** 可开红字发票的蓝票：已匹配/部分付款/已付清，且带每行可红冲数量 */
    public List<Map<String, Object>> creditCandidates(Long supplierId) {
        return creditCandidates(supplierId, null, 100);
    }

    /** 红字发票候选：keyword 匹配发票号/供应商/采购单号，limit 限制返回条数（逐单带明细，条数不宜过大） */
    public List<Map<String, Object>> creditCandidates(Long supplierId, String keyword, Integer limit) {
        int size = limit == null ? 100 : Math.max(1, Math.min(limit, 100));
        String sql = """
                select i.id, i.invoice_no as "invoiceNo", s.id as "supplierId", s.name as "supplierName",
                       o.order_no as "purchaseOrderNo", i.status,
                       i.gross_amount as "grossAmount", i.paid_amount as "paidAmount",
                       i.refundable_amount as "refundableAmount",
                       to_char(i.invoice_date, 'YYYY-MM-DD') as "invoiceDate"
                from ap_invoice i
                join supplier s on s.id = i.supplier_id
                left join purchase_order o on o.id = i.purchase_order_id
                where i.invoice_type <> 'CREDIT' and i.status in ('MATCHED', 'PARTIAL', 'PAID')
                """;
        List<Object> args = new java.util.ArrayList<>();
        if (supplierId != null) {
            sql += " and i.supplier_id = ?";
            args.add(supplierId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql += " and (i.invoice_no ilike ? or s.name ilike ? or coalesce(o.order_no, '') ilike ?)";
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        args.add(size);
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                sql + scopeSql("i.factory_org_id") + " order by i.id desc limit ?", args.toArray());
        for (Map<String, Object> row : list) {
            List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                    select ii.id as "invoiceItemId", ii.qty, ii.price, ii.matched_qty as "matchedQty",
                           poi.product_id as "productId",
                           (ii.qty - coalesce((select -sum(ci.qty) from ap_invoice_item ci
                                               join ap_invoice c on c.id = ci.invoice_id
                                               where c.related_invoice_id = ? and c.status = 'MATCHED'
                                                 and c.invoice_type = 'CREDIT'
                                                 and ci.purchase_order_item_id = ii.purchase_order_item_id), 0))
                               as "creditableQty"
                    from ap_invoice_item ii
                    join purchase_order_item poi on poi.id = ii.purchase_order_item_id
                    where ii.invoice_id = ?
                    order by ii.id
                    """, row.get("id"), row.get("id"));
            MasterDataViews.fillProductNames(masterData, items);
            row.put("items", items);
        }
        return list;
    }

    /**
     * 退货对账：按入库单汇总暂估、已开票、已红冲、已退货与未开票暂估，
     * 再加上供应商维度的待退款与红字合计。
     */
    public Map<String, Object> reconciliation(Long supplierId) {
        String sql = """
                select a.inbound_order_id as "inboundOrderId",
                       o.order_no as "purchaseOrderNo", s.id as "supplierId", s.name as "supplierName",
                       a.accrual_no as "accrualNo", a.amount as "accrualAmount",
                       a.matched_amount as "matchedAmount",
                       (a.amount - a.matched_amount) as "openAccrual",
                       coalesce((select sum(m.amount) from ap_invoice_match m
                                 join ap_invoice i on i.id = m.invoice_id
                                 join ap_accrual_item ai on ai.id = m.accrual_item_id
                                 where ai.accrual_id = a.id and m.amount > 0
                                   and i.invoice_type <> 'CREDIT'), 0) as "invoicedAmount",
                       coalesce((select -sum(m.amount) from ap_invoice_match m
                                 join ap_invoice i on i.id = m.invoice_id
                                 join ap_accrual_item ai on ai.id = m.accrual_item_id
                                 where ai.accrual_id = a.id and m.amount < 0
                                   and i.invoice_type = 'CREDIT' and i.status = 'MATCHED'), 0) as "creditedAmount",
                       coalesce((select sum(pr.total_amount) from purchase_return pr
                                 where pr.inbound_order_id = a.inbound_order_id
                                   and pr.status = 'COMPLETED'), 0) as "returnAmount",
                       (select count(*) from purchase_return pr
                         where pr.inbound_order_id = a.inbound_order_id
                           and pr.status = 'COMPLETED') as "returnCount",
                       (select max(ri.invoice_no) from purchase_return pr
                         join ap_invoice ri on ri.id = pr.red_invoice_id
                         where pr.inbound_order_id = a.inbound_order_id) as "redInvoiceNo"
                from ap_accrual a
                join purchase_order o on o.id = a.purchase_order_id
                join supplier s on s.id = a.supplier_id
                """;
        List<Map<String, Object>> rows;
        String scope = scopeSql("a.factory_org_id");
        if (supplierId == null) {
            rows = jdbcTemplate.queryForList(
                    sql + " where 1 = 1 " + scope + " order by a.inbound_order_id desc limit 200");
        } else {
            rows = jdbcTemplate.queryForList(sql + " where a.supplier_id = ? " + scope
                    + " order by a.inbound_order_id desc limit 200", supplierId);
        }
        // 入库单号与完成日期来自仓储契约，一次批量补齐
        Map<Long, InboundOrderPort.InboundOrderView> inbounds = inboundViews(rows);
        for (Map<String, Object> row : rows) {
            Object id = row.get("inboundOrderId");
            InboundOrderPort.InboundOrderView view = id == null ? null : inbounds.get(((Number) id).longValue());
            row.put("inboundNo", view == null ? null : view.inboundNo());
            row.put("completedDate", view == null ? null : view.completedDate() == null
                    ? null : view.completedDate().toString());
        }
        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal credited = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        BigDecimal open = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            invoiced = invoiced.add(decimal(row.get("invoicedAmount")));
            credited = credited.add(decimal(row.get("creditedAmount")));
            returned = returned.add(decimal(row.get("returnAmount")));
            open = open.add(decimal(row.get("openAccrual")));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("invoiced", invoiced);
        totals.put("credited", credited);
        totals.put("returned", returned);
        totals.put("openAccrual", open);
        String refundSql = "select coalesce(sum(refundable_amount), 0) from ap_invoice where refundable_amount > 0"
                + scopeSql("factory_org_id");
        totals.put("refundable", supplierId == null
                ? jdbcTemplate.queryForObject(refundSql, BigDecimal.class)
                : jdbcTemplate.queryForObject(refundSql + " and supplier_id = ?", BigDecimal.class, supplierId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", rows);
        result.put("totals", totals);
        return result;
    }

    /**
     * 三单匹配：数量不能超过已入库未开票数量，单价与订单价容差 0.5% 或 1 元，
     * 差异存在时必须强制匹配才能通过，差异金额与说明会记在发票上。
     */
    @Transactional
    public Map<String, Object> match(long id, MatchRequest request) {
        Map<String, Object> invoice = loadInvoiceForUpdate(id);
        assertStatus(invoice, "DRAFT", "只有待匹配的发票可以做三单匹配");
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select ii.id, ii.purchase_order_item_id as "poiId", ii.qty, ii.price, ii.amount,
                       poi.price as "poPrice", poi.received_qty as "receivedQty"
                from ap_invoice_item ii
                join purchase_order_item poi on poi.id = ii.purchase_order_item_id
                where ii.invoice_id = ?
                order by ii.id
                """, id);
        if (items.isEmpty()) {
            throw new BizException(1002, "发票没有明细，不能匹配");
        }
        BigDecimal qtyShortAmount = BigDecimal.ZERO;
        BigDecimal priceVariance = BigDecimal.ZERO;
        BigDecimal allocatedTotal = BigDecimal.ZERO;
        StringBuilder notes = new StringBuilder();
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (Map<String, Object> item : items) {
            long poiId = ((Number) item.get("poiId")).longValue();
            BigDecimal qty = decimal(item.get("qty"));
            BigDecimal price = decimal(item.get("price"));
            BigDecimal amount = decimal(item.get("amount"));
            BigDecimal poPrice = decimal(item.get("poPrice"));
            BigDecimal received = decimal(item.get("receivedQty"));
            BigDecimal already = jdbcTemplate.queryForObject("""
                    select coalesce(sum(ii.qty), 0) from ap_invoice_item ii
                    join ap_invoice i on i.id = ii.invoice_id
                    where ii.purchase_order_item_id = ?
                      and i.status in ('MATCHED', 'PARTIAL', 'PAID')
                    """, BigDecimal.class, poiId);
            BigDecimal available = received.subtract(already == null ? BigDecimal.ZERO : already);
            if (qty.compareTo(available) > 0) {
                BigDecimal qtyDiff = qty.subtract(available);
                qtyShortAmount = qtyShortAmount.add(qtyDiff.multiply(poPrice)
                        .setScale(2, RoundingMode.HALF_UP));
                notes.append("数量超出已入库未开票 ").append(qtyDiff.stripTrailingZeros().toPlainString()).append("；");
            }
            if (poPrice.signum() > 0) {
                BigDecimal priceDiff = price.subtract(poPrice).abs();
                BigDecimal tolerance = poPrice.multiply(new BigDecimal("0.005")).max(BigDecimal.ONE);
                if (priceDiff.compareTo(tolerance) > 0) {
                    priceVariance = priceVariance.add(price.subtract(poPrice).multiply(qty)
                            .setScale(2, RoundingMode.HALF_UP));
                    notes.append("单价偏离订单价（订单 ").append(poPrice.stripTrailingZeros().toPlainString())
                            .append("，发票 ").append(price.stripTrailingZeros().toPlainString()).append("）；");
                }
            }
            List<Map<String, Object>> accrualItems = jdbcTemplate.queryForList("""
                    select ai.id, ai.qty, ai.amount, ai.matched_qty, ai.matched_amount
                    from ap_accrual_item ai
                    join ap_accrual a on a.id = ai.accrual_id
                    where ai.purchase_order_item_id = ?
                      and a.status in ('OPEN', 'PARTIAL')
                    order by a.id
                    """, poiId);
            BigDecimal remainAmount = amount;
            BigDecimal remainQty = qty;
            for (Map<String, Object> accrualItem : accrualItems) {
                if (remainAmount.signum() <= 0) {
                    break;
                }
                BigDecimal openAmount = decimal(accrualItem.get("amount"))
                        .subtract(decimal(accrualItem.get("matched_amount")));
                BigDecimal openQty = decimal(accrualItem.get("qty"))
                        .subtract(decimal(accrualItem.get("matched_qty")));
                if (openAmount.signum() <= 0 || openQty.signum() <= 0) {
                    continue;
                }
                BigDecimal takeAmount = remainAmount.min(openAmount);
                BigDecimal takeQty = remainQty.min(openQty);
                if (takeQty.signum() <= 0) {
                    continue;
                }
                Map<String, Object> allocation = new LinkedHashMap<>();
                allocation.put("invoiceItemId", item.get("id"));
                allocation.put("accrualItemId", accrualItem.get("id"));
                allocation.put("qty", takeQty);
                allocation.put("amount", takeAmount);
                allocations.add(allocation);
                allocatedTotal = allocatedTotal.add(takeAmount);
                remainAmount = remainAmount.subtract(takeAmount);
                remainQty = remainQty.subtract(takeQty);
            }
            if (remainAmount.signum() > 0) {
                notes.append("暂估不足 ").append(remainAmount.stripTrailingZeros().toPlainString()).append("；");
            }
        }
        BigDecimal invoiceGross = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            invoiceGross = invoiceGross.add(decimal(item.get("amount")));
        }
        BigDecimal shortfall = invoiceGross.subtract(allocatedTotal).max(BigDecimal.ZERO);
        BigDecimal difference = shortfall.max(priceVariance.abs()).max(qtyShortAmount);
        boolean hasDifference = difference.signum() > 0 || notes.length() > 0;
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        if (hasDifference && !force) {
            throw new BizException(2020, "三单匹配存在差异：" + notes
                    + "差异金额约 " + difference.stripTrailingZeros().toPlainString() + " 元；确认无误可强制匹配");
        }
        for (Map<String, Object> allocation : allocations) {
            jdbcTemplate.update("""
                    insert into ap_invoice_match (invoice_id, invoice_item_id, accrual_item_id, qty, amount)
                    values (?, ?, ?, ?, ?)
                    """, id, allocation.get("invoiceItemId"), allocation.get("accrualItemId"),
                    allocation.get("qty"), allocation.get("amount"));
            jdbcTemplate.update("""
                    update ap_accrual_item set matched_qty = matched_qty + ?, matched_amount = matched_amount + ?
                    where id = ?
                    """, allocation.get("qty"), allocation.get("amount"), allocation.get("accrualItemId"));
            jdbcTemplate.update("""
                    update ap_invoice_item set matched_qty = matched_qty + ? where id = ?
                    """, allocation.get("qty"), allocation.get("invoiceItemId"));
        }
        refreshAccruals(id);
        jdbcTemplate.update("""
                update ap_invoice set status = 'MATCHED', matched_by = ?, matched_at = now(),
                       difference_amount = ?, difference_note = ?
                where id = ?
                """, CurrentUser.name(), difference, blankToNull(notes.toString()), id);
        operLogService.touch("AP", id, String.valueOf(invoice.get("invoice_no")),
                "发票 " + invoice.get("invoice_no") + " 三单匹配"
                        + (hasDifference ? "（强制，差异 " + difference.stripTrailingZeros().toPlainString() + "）" : "通过"));
        operLogService.action("匹配");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceId", id);
        result.put("differenceAmount", difference);
        result.put("differenceNote", notes.toString());
        result.put("matched", allocations.size());
        return result;
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> invoice = loadInvoiceForUpdate(id);
        String status = String.valueOf(invoice.get("status"));
        String type = String.valueOf(invoice.get("invoice_type"));
        if ("CREDIT".equals(type)) {
            if (!List.of("DRAFT", "MATCHED").contains(status)) {
                throw new BizException(2014, "只有待红冲或已红冲的红字发票可以作废");
            }
            if ("MATCHED".equals(status) && linkedToReturn(id, String.valueOf(invoice.get("invoice_no")))) {
                throw new BizException(2014, "该红字发票由采购退货生成，不能直接作废");
            }
            if ("MATCHED".equals(status)) {
                revertCredit(id, invoice);
            }
            jdbcTemplate.update("update ap_invoice set status = 'CANCELLED' where id = ?", id);
            operLogService.touch("AP", id, String.valueOf(invoice.get("invoice_no")),
                    "作废红字发票 " + invoice.get("invoice_no") + "，蓝票金额与暂估匹配恢复");
            operLogService.action("作废红字发票");
            return;
        }
        if (!List.of("DRAFT", "MATCHED").contains(status)) {
            throw new BizException(2014, "只有待匹配或已匹配未付款的发票可以作废");
        }
        Integer credits = jdbcTemplate.queryForObject("""
                select count(*) from ap_invoice
                where related_invoice_id = ? and invoice_type = 'CREDIT' and status = 'MATCHED'
                """, Integer.class, id);
        if (credits != null && credits > 0) {
            throw new BizException(2014, "该发票已有红字发票红冲，请先作废红字发票");
        }
        Integer payments = jdbcTemplate.queryForObject(
                "select count(*) from ap_payment where invoice_id = ?", Integer.class, id);
        if (payments != null && payments > 0) {
            throw new BizException(2014, "发票已有付款记录，不能作废");
        }
        if ("MATCHED".equals(status)) {
            List<Long> accrualIds = jdbcTemplate.queryForList("""
                    select distinct ai.accrual_id
                    from ap_invoice_match m
                    join ap_accrual_item ai on ai.id = m.accrual_item_id
                    where m.invoice_id = ?
                    """, Long.class, id);
            List<Map<String, Object>> matches = jdbcTemplate.queryForList("""
                    select accrual_item_id as "accrualItemId", qty, amount
                    from ap_invoice_match where invoice_id = ?
                    """, id);
            for (Map<String, Object> match : matches) {
                jdbcTemplate.update("""
                        update ap_accrual_item set matched_qty = greatest(matched_qty - ?, 0),
                               matched_amount = greatest(matched_amount - ?, 0)
                        where id = ?
                        """, match.get("qty"), match.get("amount"), match.get("accrualItemId"));
            }
            jdbcTemplate.update("delete from ap_invoice_match where invoice_id = ?", id);
            for (Long accrualId : accrualIds) {
                refreshAccrual(accrualId);
            }
        }
        jdbcTemplate.update("update ap_invoice set status = 'CANCELLED' where id = ?", id);
        operLogService.touch("AP", id, String.valueOf(invoice.get("invoice_no")),
                "作废发票 " + invoice.get("invoice_no"));
        operLogService.action("作废");
    }

    @Transactional
    public Map<String, Object> pay(long id, PaymentRequest request) {
        Map<String, Object> invoice = loadInvoiceForUpdate(id);
        String status = String.valueOf(invoice.get("status"));
        if ("CREDIT".equals(String.valueOf(invoice.get("invoice_type")))) {
            throw new BizException(2014, "红字发票不需要付款，提交红冲即可冲减应付");
        }
        if (!List.of("MATCHED", "PARTIAL").contains(status)) {
            throw new BizException(2014, "只有已匹配且未付清的发票可以付款");
        }
        if (request == null || request.amount() == null || request.amount().signum() <= 0) {
            throw new BizException(1002, "付款金额必须大于 0");
        }
        BigDecimal gross = decimal(invoice.get("gross_amount"));
        BigDecimal paid = decimal(invoice.get("paid_amount"));
        BigDecimal remaining = gross.subtract(paid);
        if (request.amount().compareTo(remaining) > 0) {
            throw new BizException(1002, "付款金额超过未付金额 " + remaining.stripTrailingZeros().toPlainString());
        }
        String method = request.method() == null || request.method().isBlank()
                ? "BANK" : request.method().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("BANK", "CASH", "OTHER").contains(method)) {
            throw new BizException(1002, "付款方式只能是 BANK / CASH / OTHER");
        }
        String paymentNo = codeGenerator.next("PAY");
        jdbcTemplate.update("""
                insert into ap_payment (payment_no, invoice_id, supplier_id, pay_date, amount, method,
                                        reference_no, remark, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, paymentNo, id, invoice.get("supplier_id"),
                request.payDate() == null ? LocalDate.now() : request.payDate(), request.amount(), method,
                blankToNull(request.referenceNo()), blankToNull(request.remark()), CurrentUser.name());
        BigDecimal newPaid = paid.add(request.amount());
        String newStatus = newPaid.compareTo(gross) >= 0 ? "PAID" : "PARTIAL";
        jdbcTemplate.update("update ap_invoice set paid_amount = ?, status = ? where id = ?",
                newPaid, newStatus, id);
        operLogService.touch("AP", id, String.valueOf(invoice.get("invoice_no")),
                "发票 " + invoice.get("invoice_no") + " 付款 " + request.amount().stripTrailingZeros().toPlainString()
                        + "，状态 " + (("PAID".equals(newStatus)) ? "已付清" : "部分付款"));
        operLogService.action("付款");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentNo", paymentNo);
        result.put("paidAmount", newPaid);
        result.put("status", newStatus);
        return result;
    }

    public Map<String, Object> aging() {
        String scope = scopeSql("factory_org_id");
        List<Map<String, Object>> buckets = jdbcTemplate.queryForList("""
                select case when due_date is null or due_date >= current_date then '未到期'
                            when current_date - due_date <= 30 then '1-30天'
                            when current_date - due_date <= 60 then '31-60天'
                            when current_date - due_date <= 90 then '61-90天'
                            else '90天以上' end as "bucket",
                       count(*) as "count",
                       coalesce(sum(greatest(gross_amount - paid_amount, 0)), 0) as "amount"
                from ap_invoice
                where status in ('MATCHED', 'PARTIAL') and invoice_type <> 'CREDIT'
                """ + scope + " group by 1 ");
        List<Map<String, Object>> overdue = jdbcTemplate.queryForList("""
                select i.id, i.invoice_no as "invoiceNo", s.name as "supplierName",
                       to_char(i.due_date, 'YYYY-MM-DD') as "dueDate",
                       (current_date - i.due_date) as "overdueDays",
                       greatest(i.gross_amount - i.paid_amount, 0) as "remainingAmount"
                from ap_invoice i
                join supplier s on s.id = i.supplier_id
                where i.status in ('MATCHED', 'PARTIAL') and i.invoice_type <> 'CREDIT'
                  and i.due_date < current_date
                """ + scopeSql("i.factory_org_id") + " order by i.due_date limit 50 ");
        List<Map<String, Object>> refundable = jdbcTemplate.queryForList("""
                select i.id, i.invoice_no as "invoiceNo", s.name as "supplierName",
                       i.refundable_amount as "refundableAmount",
                       to_char(i.invoice_date, 'YYYY-MM-DD') as "invoiceDate"
                from ap_invoice i
                join supplier s on s.id = i.supplier_id
                where i.refundable_amount > 0
                """ + scopeSql("i.factory_org_id") + " order by i.id desc limit 50 ");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buckets", buckets);
        result.put("overdueInvoices", overdue);
        result.put("refundableInvoices", refundable);
        return result;
    }

    /** 红字发票是否由采购退货生成：退货单直接引用，或红票号带 RC + 退货单号前缀 */
    private boolean linkedToReturn(long creditId, String invoiceNo) {
        Integer linked = jdbcTemplate.queryForObject(
                "select count(*) from purchase_return where red_invoice_id = ?", Integer.class, creditId);
        if (linked != null && linked > 0) {
            return true;
        }
        Integer prefixed = jdbcTemplate.queryForObject("""
                select count(*) from purchase_return pr where ? like 'RC' || pr.return_no || '%'
                """, Integer.class, invoiceNo);
        return prefixed != null && prefixed > 0;
    }

    /** 作废红字发票时回退：暂估匹配加回来、蓝票金额恢复、待退款重算 */
    private void revertCredit(long creditId, Map<String, Object> credit) {
        long blueId = ((Number) credit.get("related_invoice_id")).longValue();
        Map<String, Object> blue = loadInvoiceForUpdate(blueId);
        Integer refunds = jdbcTemplate.queryForObject(
                "select count(*) from ap_refund where invoice_id = ?", Integer.class, blueId);
        if (refunds != null && refunds > 0) {
            throw new BizException(2014, "蓝字发票已有供应商退款记录，不能再作废红字发票");
        }
        List<Map<String, Object>> matches = jdbcTemplate.queryForList("""
                select accrual_item_id as "accrualItemId", invoice_item_id as "invoiceItemId", qty, amount
                from ap_invoice_match where invoice_id = ? and qty < 0
                """, creditId);
        for (Map<String, Object> match : matches) {
            jdbcTemplate.update("""
                    update ap_accrual_item
                    set matched_qty = least(matched_qty - ?, qty),
                        matched_amount = least(matched_amount - ?, amount)
                    where id = ?
                    """, match.get("qty"), match.get("amount"), match.get("accrualItemId"));
            jdbcTemplate.update("""
                    update ap_invoice_item ii
                    set matched_qty = least(ii.matched_qty - ?, ii.qty)
                    from ap_invoice_item ci
                    where ci.id = ? and ii.invoice_id = ?
                      and ii.purchase_order_item_id = ci.purchase_order_item_id
                    """, decimal(match.get("qty")).negate(), match.get("invoiceItemId"), blueId);
        }
        jdbcTemplate.update("delete from ap_invoice_match where invoice_id = ?", creditId);
        BigDecimal creditGross = decimal(credit.get("gross_amount"));
        BigDecimal newGross = decimal(blue.get("gross_amount")).subtract(creditGross);
        BigDecimal newNet = decimal(blue.get("net_amount")).subtract(decimal(credit.get("net_amount")));
        BigDecimal newTax = decimal(blue.get("tax_amount")).subtract(decimal(credit.get("tax_amount")));
        BigDecimal paid = decimal(blue.get("paid_amount"));
        BigDecimal refundable = paid.subtract(newGross).max(BigDecimal.ZERO);
        String newStatus;
        if (newGross.signum() == 0 && paid.signum() == 0) {
            newStatus = "RED_REVERSED";
        } else if (paid.compareTo(newGross) > 0) {
            newStatus = "PAID";
        } else if (paid.signum() == 0) {
            newStatus = "MATCHED";
        } else {
            newStatus = "PARTIAL";
        }
        jdbcTemplate.update("""
                update ap_invoice set gross_amount = ?, net_amount = ?, tax_amount = ?,
                       refundable_amount = ?, status = ?
                where id = ?
                """, newGross, newNet, newTax, refundable, newStatus, blueId);
        refreshAccruals(blueId);
    }

    private void assertNotCreditType(InvoiceRequest request) {
        if (request != null && request.invoiceType() != null
                && "CREDIT".equalsIgnoreCase(request.invoiceType().trim())) {
            throw new BizException(1002, "红字发票请走「开红字发票」入口，需要关联蓝字发票");
        }
    }

    private void validateInvoice(InvoiceRequest request, Long excludeId) {
        if (request == null || request.invoiceNo() == null || request.invoiceNo().trim().isEmpty()) {
            throw new BizException(1002, "发票号不能为空");
        }
        if (request.supplierId() == null) {
            throw new BizException(1002, "请选择供应商");
        }
        if (request.invoiceDate() != null && request.invoiceDate().isAfter(LocalDate.now())) {
            throw new BizException(1002, "开票日期不能晚于今天");
        }
        if (request.taxRate() != null
                && (request.taxRate().signum() < 0 || request.taxRate().compareTo(ONE_HUNDRED) > 0)) {
            throw new BizException(1002, "税率必须在 0 到 100 之间");
        }
        Integer duplicate;
        if (excludeId == null) {
            duplicate = jdbcTemplate.queryForObject(
                    "select count(*) from ap_invoice where invoice_no = ?", Integer.class,
                    request.invoiceNo().trim());
        } else {
            duplicate = jdbcTemplate.queryForObject(
                    "select count(*) from ap_invoice where invoice_no = ? and id <> ?", Integer.class,
                    request.invoiceNo().trim(), excludeId);
        }
        if (duplicate != null && duplicate > 0) {
            throw new BizException(2015, "发票号已存在");
        }
        loadSupplier(request.supplierId());
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少添加一条发票明细");
        }
        for (InvoiceItemRequest item : request.items()) {
            if (item.purchaseOrderItemId() == null) {
                throw new BizException(1002, "发票明细必须选择采购行");
            }
            if (item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "发票数量必须大于 0");
            }
            if (item.price() == null || item.price().signum() < 0) {
                throw new BizException(1002, "发票单价不能为负数");
            }
            Map<String, Object> poi = loadPurchaseItem(item.purchaseOrderItemId(), request.supplierId());
            BigDecimal received = decimal(poi.get("received_qty"));
            BigDecimal invoiced;
            if (excludeId == null) {
                invoiced = jdbcTemplate.queryForObject("""
                        select coalesce(sum(ii.qty), 0) from ap_invoice_item ii
                        join ap_invoice i on i.id = ii.invoice_id
                        where ii.purchase_order_item_id = ? and i.status <> 'CANCELLED'
                        """, BigDecimal.class, item.purchaseOrderItemId());
            } else {
                invoiced = jdbcTemplate.queryForObject("""
                        select coalesce(sum(ii.qty), 0) from ap_invoice_item ii
                        join ap_invoice i on i.id = ii.invoice_id
                        where ii.purchase_order_item_id = ? and i.status <> 'CANCELLED'
                          and i.id <> ?
                        """, BigDecimal.class, item.purchaseOrderItemId(), excludeId);
            }
            BigDecimal available = received.subtract(invoiced == null ? BigDecimal.ZERO : invoiced);
            if (item.qty().compareTo(available) > 0) {
                throw new BizException(1002, "发票数量超过已入库未开票数量 "
                        + available.stripTrailingZeros().toPlainString());
            }
        }
    }

    private Map<String, Object> loadPurchaseItem(Long purchaseOrderItemId, Long supplierId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select poi.id, poi.price, poi.qty, poi.received_qty, o.supplier_id, o.id as order_id
                from purchase_order_item poi
                join purchase_order o on o.id = poi.order_id
                where poi.id = ?
                """, purchaseOrderItemId);
        if (rows.isEmpty()) {
            throw new BizException(1002, "采购明细不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (supplierId != null && !supplierId.equals(((Number) row.get("supplier_id")).longValue())) {
            throw new BizException(1002, "采购明细不属于所选供应商");
        }
        return row;
    }

    private Map<String, Object> loadSupplier(Long supplierId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, code, credit_days, payment_term, enabled from supplier where id = ?
                """, supplierId);
        if (rows.isEmpty()) {
            throw new BizException(2006, "供应商不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> loadInvoiceForUpdate(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, invoice_no, invoice_type, status, supplier_id, purchase_order_id, invoice_date,
                       tax_rate, net_amount, tax_amount, gross_amount, paid_amount, refundable_amount,
                       related_invoice_id, factory_org_id
                from ap_invoice where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2012, "发票不存在");
        }
        return rows.get(0);
    }

    /** 重新汇总受影响暂估的已匹配金额与状态 */
    private void refreshAccruals(long invoiceId) {
        List<Long> accrualIds = jdbcTemplate.queryForList("""
                select distinct ai.accrual_id
                from ap_invoice_match m
                join ap_accrual_item ai on ai.id = m.accrual_item_id
                where m.invoice_id = ?
                """, Long.class, invoiceId);
        for (Long accrualId : accrualIds) {
            refreshAccrual(accrualId);
        }
    }

    private void refreshAccrual(long accrualId) {
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

    private static BigDecimal[] splitTax(BigDecimal gross, BigDecimal taxRate) {
        BigDecimal net = gross.multiply(ONE_HUNDRED)
                .divide(ONE_HUNDRED.add(taxRate), 2, RoundingMode.HALF_UP);
        BigDecimal tax = gross.subtract(net);
        return new BigDecimal[]{net, tax};
    }

    private static String invoiceTypeOf(String type) {
        if (type == null || type.isBlank()) {
            return "VAT_SPECIAL";
        }
        String value = type.trim().toUpperCase(java.util.Locale.ROOT);
        return List.of("VAT_SPECIAL", "VAT_NORMAL", "RECEIPT").contains(value) ? value : "VAT_SPECIAL";
    }

    private void assertStatus(Map<String, Object> invoice, String expected, String message) {
        if (!expected.equals(String.valueOf(invoice.get("status")))) {
            throw new BizException(2014, message);
        }
    }

    /** 补入库单号：入库单归仓储，暂估表里只存 id */
    private void fillInboundNos(List<Map<String, Object>> rows) {
        Map<Long, InboundOrderPort.InboundOrderView> inbounds = inboundViews(rows);
        for (Map<String, Object> row : rows) {
            Object id = row.get("inboundOrderId");
            InboundOrderPort.InboundOrderView view = id == null ? null : inbounds.get(((Number) id).longValue());
            row.put("inboundNo", view == null ? null : view.inboundNo());
        }
    }

    private Map<Long, InboundOrderPort.InboundOrderView> inboundViews(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> row.get("inboundOrderId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of() : inboundOrderPort.inbounds(ids);
    }

    /** 暂估单价取采购明细上的价格，只查采购自己的表 */
    private Map<Long, BigDecimal> purchasePrices(List<Long> purchaseItemIds) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        List<Long> ids = purchaseItemIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, price from purchase_order_item where id in (" + marks + ")", ids.toArray())) {
            map.put(((Number) row.get("id")).longValue(), decimal(row.get("price")));
        }
        return map;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
