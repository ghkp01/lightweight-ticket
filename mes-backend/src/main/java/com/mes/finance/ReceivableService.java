package com.mes.finance;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.security.CurrentUser;
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

/**
 * 应收：销售发票、收款、核销、账龄。
 *
 * <p>与报价/订单口径保持一致：单价默认含税，未税金额、税额、价税合计三者自洽；
 * 外币发票按单据日期的记账汇率折本位币，汇率缺失时拒绝开票，不静默按 1 折算。</p>
 */
@Service
public class ReceivableService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final PostingService postingService;

    public ReceivableService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                             PostingService postingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.postingService = postingService;
    }

    public record InvoiceItem(Long productId, BigDecimal qty, BigDecimal price) {
    }

    public record InvoiceRequest(Long customerId, Long salesOrderId, String invoiceType,
                                 LocalDate invoiceDate, LocalDate dueDate, String currency,
                                 BigDecimal taxRate, Boolean priceIncludesTax,
                                 List<InvoiceItem> items, String remark) {
    }

    public record ShipmentInvoiceRequest(BigDecimal taxRate, Integer creditDays,
                                         LocalDate dueDate, String remark) {
    }

    public record ApplyLine(Long invoiceId, BigDecimal amount) {
    }

    public record ReceiptRequest(Long customerId, LocalDate receiptDate, String currency,
                                 BigDecimal amount, String method, String referenceNo,
                                 List<ApplyLine> applies, String remark) {
    }

    // ---------------------------------------------------------------- 概览

    public Map<String, Object> summary() {
        Map<String, Object> outstanding = jdbcTemplate.queryForMap("""
                select coalesce(sum(case when i.invoice_type = 'CREDIT' then -i.base_total
                                         else i.base_total - round(i.received_amount * i.exchange_rate, 2) end), 0)
                           as "outstanding",
                       coalesce(sum(case when i.invoice_type <> 'CREDIT' and i.due_date < current_date
                                         then i.base_total - round(i.received_amount * i.exchange_rate, 2)
                                         else 0 end), 0) as "overdue"
                from ar_invoice i
                where i.status in ('ISSUED', 'PARTIAL')
                """);
        BigDecimal received = jdbcTemplate.queryForObject("""
                select coalesce(sum(base_amount), 0) from ar_receipt
                where status = 'POSTED' and date_trunc('month', receipt_date) = date_trunc('month', current_date)
                """, BigDecimal.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outstanding", outstanding.get("outstanding"));
        result.put("overdue", outstanding.get("overdue"));
        result.put("receivedThisMonth", received == null ? BigDecimal.ZERO : received);
        return result;
    }

    // ---------------------------------------------------------------- 发票

    public PageResult invoices(String keyword, String status, Long customerId, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (status != null && !status.isBlank()) {
            where.append(" and i.status = ? ");
            args.add(status.trim());
        }
        if (customerId != null) {
            where.append(" and i.customer_id = ? ");
            args.add(customerId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (i.invoice_no ilike ? or c.name ilike ? or s.shipment_no ilike ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from ar_invoice i
                join rm_customer c on c.id = i.customer_id
                left join rm_shipment s on s.id = i.shipment_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.id, i.invoice_no as "invoiceNo", i.invoice_type as "invoiceType",
                       i.customer_id as "customerId", c.name as "customerName",
                       i.invoice_date as "invoiceDate", i.due_date as "dueDate",
                       i.currency, i.tax_rate as "taxRate", i.price_includes_tax as "priceIncludesTax",
                       i.net_amount as "netAmount", i.tax_amount as "taxAmount",
                       i.total_amount as "totalAmount", i.base_total as "baseTotal",
                       i.received_amount as "receivedAmount",
                       (i.total_amount - i.received_amount) as "remaining",
                       i.status, i.shipment_id as "shipmentId", s.shipment_no as "shipmentNo",
                       i.remark
                from ar_invoice i
                join rm_customer c on c.id = i.customer_id
                left join rm_shipment s on s.id = i.shipment_id
                """ + where + " order by i.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> invoiceDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.id, i.invoice_no as "invoiceNo", i.invoice_type as "invoiceType",
                       i.customer_id as "customerId", c.name as "customerName",
                       i.sales_order_id as "salesOrderId", i.shipment_id as "shipmentId",
                       s.shipment_no as "shipmentNo",
                       i.invoice_date as "invoiceDate", i.due_date as "dueDate",
                       i.currency, i.exchange_rate as "exchangeRate",
                       i.tax_rate as "taxRate", i.price_includes_tax as "priceIncludesTax",
                       i.net_amount as "netAmount", i.tax_amount as "taxAmount",
                       i.total_amount as "totalAmount", i.base_net as "baseNet",
                       i.base_tax as "baseTax", i.base_total as "baseTotal",
                       i.received_amount as "receivedAmount",
                       (i.total_amount - i.received_amount) as "remaining",
                       i.status, i.remark, i.created_by as "createdBy",
                       to_char(i.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from ar_invoice i
                join rm_customer c on c.id = i.customer_id
                left join rm_shipment s on s.id = i.shipment_id
                where i.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "发票不存在");
        }
        Map<String, Object> invoice = new LinkedHashMap<>(rows.get(0));
        invoice.put("items", jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", p.code as "productCode", p.name as "productName",
                       i.qty, i.price, i.amount
                from ar_invoice_item i
                left join rm_product p on p.id = i.product_id
                where i.invoice_id = ? order by i.id
                """, id));
        invoice.put("applies", jdbcTemplate.queryForList("""
                select a.id, a.receipt_id as "receiptId", r.receipt_no as "receiptNo",
                       r.receipt_date as "receiptDate", a.amount, a.base_amount as "baseAmount"
                from ar_receipt_apply a
                join ar_receipt r on r.id = a.receipt_id
                where a.invoice_id = ? and r.status = 'POSTED'
                order by a.id
                """, id));
        invoice.put("voucherNo", oneOrNull("""
                select voucher_no from fin_voucher
                where biz_type = 'AR_INVOICE' and biz_id = ? and status = 'POSTED'
                """, id));
        return invoice;
    }

    @Transactional
    public long createInvoice(InvoiceRequest request) {
        return createInvoiceInternal(request, null);
    }

    /** 从发货单开票：数量取发货数量，单价取订单含税单价，币种取订单币种 */
    @Transactional
    public long invoiceFromShipment(long shipmentId, ShipmentInvoiceRequest request) {
        List<Map<String, Object>> shipments = jdbcTemplate.queryForList("""
                select s.id, s.shipment_no as "shipmentNo", s.sales_order_id as "salesOrderId",
                       s.customer_id as "customerId", s.status, so.currency
                from rm_shipment s
                join rm_sales_order so on so.id = s.sales_order_id
                where s.id = ?
                """, shipmentId);
        if (shipments.isEmpty()) {
            throw new BizException(1002, "发货单不存在");
        }
        Map<String, Object> shipment = shipments.get(0);
        String status = String.valueOf(shipment.get("status"));
        if (!List.of("SHIPPED", "SIGNED").contains(status)) {
            throw new BizException(1002, "只有已发货或已签收的发货单可以开票");
        }
        Integer exists = jdbcTemplate.queryForObject("""
                select count(*) from ar_invoice where shipment_id = ? and status <> 'CANCELLED'
                """, Integer.class, shipmentId);
        if (exists != null && exists > 0) {
            throw new BizException(1002, "这张发货单已经开过票");
        }
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select si.product_id as "productId", si.qty, soi.price
                from rm_shipment_item si
                join rm_sales_order_item soi on soi.id = si.sales_order_item_id
                where si.shipment_id = ?
                """, shipmentId);
        if (items.isEmpty()) {
            throw new BizException(1002, "发货单没有明细，不能开票");
        }
        List<InvoiceItem> lines = items.stream()
                .map(row -> new InvoiceItem(
                        row.get("productId") == null ? null : ((Number) row.get("productId")).longValue(),
                        (BigDecimal) row.get("qty"), (BigDecimal) row.get("price")))
                .toList();
        LocalDate invoiceDate = LocalDate.now();
        int creditDays = request == null || request.creditDays() == null ? 30 : request.creditDays();
        LocalDate dueDate = request == null ? null : request.dueDate();
        if (dueDate == null) {
            dueDate = invoiceDate.plusDays(creditDays);
        }
        InvoiceRequest invoice = new InvoiceRequest(
                ((Number) shipment.get("customerId")).longValue(),
                ((Number) shipment.get("salesOrderId")).longValue(),
                "NORMAL", invoiceDate, dueDate,
                shipment.get("currency") == null ? "CNY" : String.valueOf(shipment.get("currency")),
                request == null ? null : request.taxRate(), true, lines,
                request == null ? null : request.remark());
        return createInvoiceInternal(invoice, shipmentId);
    }

    @Transactional
    public void cancelInvoice(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, invoice_no as "invoiceNo", status, received_amount as "receivedAmount"
                from ar_invoice where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "发票不存在");
        }
        Map<String, Object> invoice = rows.get(0);
        if ("CANCELLED".equals(invoice.get("status"))) {
            throw new BizException(1002, "发票已作废");
        }
        BigDecimal received = new BigDecimal(String.valueOf(invoice.get("receivedAmount")));
        if (received.signum() > 0) {
            throw new BizException(1002, "发票已有收款，不能直接作废，请先取消对应收款单");
        }
        List<Long> voucherIds = jdbcTemplate.queryForList("""
                select id from fin_voucher
                where biz_type = 'AR_INVOICE' and biz_id = ? and status = 'POSTED'
                """, Long.class, id);
        if (!voucherIds.isEmpty()) {
            postingService.reverse(voucherIds.get(0), "销售发票作废 " + invoice.get("invoiceNo"));
        }
        jdbcTemplate.update("update ar_invoice set status = 'CANCELLED' where id = ?", id);
    }

    // ---------------------------------------------------------------- 待开票发货单

    public PageResult invoiceableShipments(String keyword, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                 where s.status in ('SHIPPED', 'SIGNED')
                   and not exists (select 1 from ar_invoice i
                                    where i.shipment_id = s.id and i.status <> 'CANCELLED')
                """);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (s.shipment_no ilike ? or c.name ilike ? or so.order_no ilike ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from rm_shipment s
                join rm_customer c on c.id = s.customer_id
                join rm_sales_order so on so.id = s.sales_order_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select s.id, s.shipment_no as "shipmentNo", s.status,
                       s.customer_id as "customerId", c.name as "customerName",
                       s.sales_order_id as "salesOrderId", so.order_no as "orderNo", so.currency,
                       to_char(coalesce(s.shipped_at, s.created_at), 'YYYY-MM-DD') as "shippedAt",
                       (select coalesce(sum(si.qty), 0) from rm_shipment_item si
                         where si.shipment_id = s.id) as "qty",
                       (select coalesce(sum(round(si.qty * soi.price, 2)), 0)
                          from rm_shipment_item si
                          join rm_sales_order_item soi on soi.id = si.sales_order_item_id
                         where si.shipment_id = s.id) as "amount"
                from rm_shipment s
                join rm_customer c on c.id = s.customer_id
                join rm_sales_order so on so.id = s.sales_order_id
                """ + where + " order by s.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    // ---------------------------------------------------------------- 收款与核销

    public PageResult receipts(String keyword, Long customerId, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (customerId != null) {
            where.append(" and r.customer_id = ? ");
            args.add(customerId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (r.receipt_no ilike ? or c.name ilike ? or r.reference_no ilike ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from ar_receipt r join rm_customer c on c.id = r.customer_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select r.id, r.receipt_no as "receiptNo", r.customer_id as "customerId",
                       c.name as "customerName", r.receipt_date as "receiptDate",
                       r.currency, r.amount, r.base_amount as "baseAmount", r.method,
                       r.reference_no as "referenceNo", r.status, r.remark,
                       r.created_by as "createdBy", r.applied_later as "appliedLater",
                       (r.amount - coalesce((select sum(a.amount) from ar_receipt_apply a
                                              where a.receipt_id = r.id), 0)) as "unappliedAmount",
                       (select count(*) from ar_receipt_apply a where a.receipt_id = r.id) as "applyCount"
                from ar_receipt r join rm_customer c on c.id = r.customer_id
                """ + where + " order by r.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    @Transactional
    public long createReceipt(ReceiptRequest request) {
        if (request.customerId() == null) {
            throw new BizException(1002, "请选择客户");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new BizException(1002, "收款金额必须大于 0");
        }
        requireCustomer(request.customerId());
        String currency = normalizeCurrency(request.currency());
        LocalDate receiptDate = request.receiptDate() == null ? LocalDate.now() : request.receiptDate();
        BigDecimal rate = postingService.rateFor(currency, receiptDate);
        BigDecimal baseAmount = money(request.amount().multiply(rate));

        List<ApplyLine> applies = request.applies() == null ? List.of()
                : request.applies().stream()
                .filter(line -> line.invoiceId() != null && line.amount() != null
                        && line.amount().signum() > 0)
                .toList();
        BigDecimal applied = applies.stream().map(ApplyLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (applied.compareTo(request.amount()) > 0) {
            throw new BizException(1002, "核销金额合计不能超过收款金额");
        }

        List<Map<String, Object>> invoices = new ArrayList<>();
        for (ApplyLine line : applies) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select id, invoice_no as "invoiceNo", customer_id as "customerId",
                           currency, invoice_type as "invoiceType", status,
                           total_amount as "totalAmount", received_amount as "receivedAmount"
                    from ar_invoice where id = ? for update
                    """, line.invoiceId());
            if (rows.isEmpty()) {
                throw new BizException(1002, "发票不存在：" + line.invoiceId());
            }
            Map<String, Object> invoice = rows.get(0);
            if (!"ISSUED".equals(invoice.get("status")) && !"PARTIAL".equals(invoice.get("status"))) {
                throw new BizException(1002, "发票 " + invoice.get("invoiceNo") + " 当前状态不能核销");
            }
            if (!"NORMAL".equals(invoice.get("invoiceType"))) {
                throw new BizException(1002, "红字发票不能收款核销：" + invoice.get("invoiceNo"));
            }
            if (((Number) invoice.get("customerId")).longValue() != request.customerId()) {
                throw new BizException(1002, "发票 " + invoice.get("invoiceNo") + " 不属于当前客户");
            }
            if (!currency.equals(String.valueOf(invoice.get("currency")))) {
                throw new BizException(1002, "收款币种与发票 " + invoice.get("invoiceNo") + " 不一致");
            }
            BigDecimal remain = new BigDecimal(String.valueOf(invoice.get("totalAmount")))
                    .subtract(new BigDecimal(String.valueOf(invoice.get("receivedAmount"))));
            if (line.amount().compareTo(remain) > 0) {
                throw new BizException(1002, "发票 " + invoice.get("invoiceNo")
                        + " 剩余应收 " + remain + "，核销金额超出");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", invoice.get("id"));
            row.put("amount", line.amount());
            invoices.add(row);
        }

        String receiptNo = codeGenerator.next("RC");
        Long receiptId = jdbcTemplate.queryForObject("""
                insert into ar_receipt (receipt_no, customer_id, receipt_date, currency, exchange_rate,
                                        amount, base_amount, method, reference_no, remark, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, receiptNo, request.customerId(), receiptDate, currency, rate,
                request.amount(), baseAmount,
                request.method() == null || request.method().isBlank() ? "BANK" : request.method(),
                blankToNull(request.referenceNo()), blankToNull(request.remark()), CurrentUser.name());
        if (receiptId == null) {
            throw new BizException(1002, "收款单创建失败");
        }
        for (Map<String, Object> invoice : invoices) {
            BigDecimal amount = (BigDecimal) invoice.get("amount");
            jdbcTemplate.update("""
                    insert into ar_receipt_apply (receipt_id, invoice_id, amount, base_amount)
                    values (?, ?, ?, ?)
                    """, receiptId, invoice.get("id"), amount, money(amount.multiply(rate)));
            jdbcTemplate.update("""
                    update ar_invoice
                    set received_amount = received_amount + ?,
                        status = case when received_amount + ? >= total_amount then 'PAID' else 'PARTIAL' end
                    where id = ?
                    """, amount, amount, invoice.get("id"));
        }
        BigDecimal appliedBase = money(applied.multiply(rate));
        BigDecimal advanceBase = baseAmount.subtract(appliedBase);
        List<PostingService.PostingLine> receiptLines = new ArrayList<>();
        receiptLines.add(new PostingService.PostingLine("BANK", "销售收款", baseAmount, BigDecimal.ZERO));
        if (appliedBase.signum() > 0) {
            receiptLines.add(new PostingService.PostingLine("AR", "销售收款核销", BigDecimal.ZERO, appliedBase));
        }
        if (advanceBase.signum() > 0) {
            receiptLines.add(new PostingService.PostingLine("AR_ADVANCE", "未核销收款转预收", BigDecimal.ZERO, advanceBase));
        }
        postingService.post(receiptDate, "AR_RECEIPT", receiptId, receiptNo, currency, rate,
                "销售收款 " + receiptNo, receiptLines);
        return receiptId;
    }

    /** 预收核销：把收款单未核销的部分核销到后续发票上，生成预收转应收的凭证 */
    @Transactional
    public Map<String, Object> applyAdvance(long receiptId, List<ApplyLine> applies) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, receipt_no as "receiptNo", status, customer_id as "customerId",
                       currency, exchange_rate as "exchangeRate", amount,
                       applied_later as "appliedLater"
                from ar_receipt where id = ? for update
                """, receiptId);
        if (rows.isEmpty()) {
            throw new BizException(1002, "收款单不存在");
        }
        Map<String, Object> receipt = rows.get(0);
        if (!"POSTED".equals(receipt.get("status"))) {
            throw new BizException(1002, "收款单已取消，不能核销");
        }
        BigDecimal already = jdbcTemplate.queryForObject("""
                select coalesce(sum(amount), 0) from ar_receipt_apply where receipt_id = ?
                """, BigDecimal.class, receiptId);
        BigDecimal available = new BigDecimal(String.valueOf(receipt.get("amount")))
                .subtract(already == null ? BigDecimal.ZERO : already);
        List<ApplyLine> lines = applies == null ? List.of() : applies.stream()
                .filter(line -> line.invoiceId() != null && line.amount() != null
                        && line.amount().signum() > 0)
                .toList();
        if (lines.isEmpty()) {
            throw new BizException(1002, "请填写要核销的发票金额");
        }
        BigDecimal requested = lines.stream().map(ApplyLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (requested.compareTo(available) > 0) {
            throw new BizException(1002, "核销金额超过预收余额 " + available);
        }
        String currency = String.valueOf(receipt.get("currency"));
        BigDecimal rate = new BigDecimal(String.valueOf(receipt.get("exchangeRate")));
        long customerId = ((Number) receipt.get("customerId")).longValue();
        String receiptNo = String.valueOf(receipt.get("receiptNo"));

        for (ApplyLine line : lines) {
            List<Map<String, Object>> invoices = jdbcTemplate.queryForList("""
                    select id, invoice_no as "invoiceNo", customer_id as "customerId",
                           currency, invoice_type as "invoiceType", status,
                           total_amount as "totalAmount", received_amount as "receivedAmount"
                    from ar_invoice where id = ? for update
                    """, line.invoiceId());
            if (invoices.isEmpty()) {
                throw new BizException(1002, "发票不存在：" + line.invoiceId());
            }
            Map<String, Object> invoice = invoices.get(0);
            if (!"ISSUED".equals(invoice.get("status")) && !"PARTIAL".equals(invoice.get("status"))) {
                throw new BizException(1002, "发票 " + invoice.get("invoiceNo") + " 当前状态不能核销");
            }
            if (!"NORMAL".equals(invoice.get("invoiceType"))) {
                throw new BizException(1002, "红字发票不能核销预收：" + invoice.get("invoiceNo"));
            }
            if (((Number) invoice.get("customerId")).longValue() != customerId) {
                throw new BizException(1002, "发票 " + invoice.get("invoiceNo") + " 不属于当前客户");
            }
            if (!currency.equals(String.valueOf(invoice.get("currency")))) {
                throw new BizException(1002, "预收币种与发票 " + invoice.get("invoiceNo") + " 不一致");
            }
            BigDecimal remain = new BigDecimal(String.valueOf(invoice.get("totalAmount")))
                    .subtract(new BigDecimal(String.valueOf(invoice.get("receivedAmount"))));
            if (line.amount().compareTo(remain) > 0) {
                throw new BizException(1002, "发票 " + invoice.get("invoiceNo")
                        + " 剩余应收 " + remain + "，核销金额超出");
            }
            BigDecimal baseAmount = money(line.amount().multiply(rate));
            Long applyId = jdbcTemplate.queryForObject("""
                    insert into ar_receipt_apply (receipt_id, invoice_id, amount, base_amount)
                    values (?, ?, ?, ?) returning id
                    """, Long.class, receiptId, line.invoiceId(), line.amount(), baseAmount);
            jdbcTemplate.update("""
                    update ar_invoice
                    set received_amount = received_amount + ?,
                        status = case when received_amount + ? >= total_amount then 'PAID' else 'PARTIAL' end
                    where id = ?
                    """, line.amount(), line.amount(), line.invoiceId());
            postingService.post(LocalDate.now(), "AR_ADVANCE_APPLY", applyId == null ? 0 : applyId,
                    receiptNo, currency, rate, "预收核销 " + receiptNo,
                    List.of(new PostingService.PostingLine("AR_ADVANCE", "预收核销", baseAmount, BigDecimal.ZERO),
                            new PostingService.PostingLine("AR", "预收转应收", BigDecimal.ZERO, baseAmount)));
        }
        jdbcTemplate.update("update ar_receipt set applied_later = true where id = ?", receiptId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", requested);
        result.put("remaining", available.subtract(requested));
        return result;
    }

    @Transactional
    public void cancelReceipt(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, receipt_no as "receiptNo", status, currency, exchange_rate as "exchangeRate",
                       applied_later as "appliedLater"
                from ar_receipt where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "收款单不存在");
        }
        Map<String, Object> receipt = rows.get(0);
        if ("CANCELLED".equals(receipt.get("status"))) {
            throw new BizException(1002, "收款单已取消");
        }
        if (Boolean.TRUE.equals(receipt.get("appliedLater"))) {
            throw new BizException(1002, "该收款单已经做过预收核销，不能直接取消");
        }
        List<Long> voucherIds = jdbcTemplate.queryForList("""
                select id from fin_voucher
                where biz_type = 'AR_RECEIPT' and biz_id = ? and status = 'POSTED'
                """, Long.class, id);
        if (!voucherIds.isEmpty()) {
            postingService.reverse(voucherIds.get(0), "收款单取消 " + receipt.get("receiptNo"));
        }
        List<Map<String, Object>> applies = jdbcTemplate.queryForList("""
                select invoice_id as "invoiceId", amount from ar_receipt_apply where receipt_id = ?
                """, id);
        for (Map<String, Object> apply : applies) {
            BigDecimal amount = (BigDecimal) apply.get("amount");
            jdbcTemplate.update("""
                    update ar_invoice
                    set received_amount = greatest(received_amount - ?, 0),
                        status = case when received_amount - ? <= 0 then 'ISSUED'
                                      when received_amount - ? >= total_amount then 'PAID'
                                      else 'PARTIAL' end
                    where id = ?
                    """, amount, amount, amount, apply.get("invoiceId"));
        }
        jdbcTemplate.update("update ar_receipt set status = 'CANCELLED' where id = ?", id);
    }

    // ---------------------------------------------------------------- 账龄

    public Map<String, Object> aging() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select c.name as "customerName", i.currency,
                       coalesce(sum(case when i.invoice_type = 'CREDIT' then -i.total_amount * i.exchange_rate
                                         when i.due_date >= current_date
                                         then (i.total_amount - i.received_amount) * i.exchange_rate
                                         else 0 end), 0) as "notDue",
                       coalesce(sum(case when i.invoice_type = 'NORMAL' and i.due_date < current_date
                                         and current_date - i.due_date <= 30
                                         then (i.total_amount - i.received_amount) * i.exchange_rate
                                         else 0 end), 0) as "d1",
                       coalesce(sum(case when i.invoice_type = 'NORMAL'
                                         and current_date - i.due_date between 31 and 60
                                         then (i.total_amount - i.received_amount) * i.exchange_rate
                                         else 0 end), 0) as "d31",
                       coalesce(sum(case when i.invoice_type = 'NORMAL'
                                         and current_date - i.due_date between 61 and 90
                                         then (i.total_amount - i.received_amount) * i.exchange_rate
                                         else 0 end), 0) as "d61",
                       coalesce(sum(case when i.invoice_type = 'NORMAL'
                                         and current_date - i.due_date > 90
                                         then (i.total_amount - i.received_amount) * i.exchange_rate
                                         else 0 end), 0) as "d90",
                       coalesce(sum(case when i.invoice_type = 'CREDIT' then -i.total_amount * i.exchange_rate
                                         else (i.total_amount - i.received_amount) * i.exchange_rate end), 0) as "total"
                from ar_invoice i
                join rm_customer c on c.id = i.customer_id
                where i.status in ('ISSUED', 'PARTIAL')
                group by c.name, i.currency
                order by "total" desc
                """);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("asOf", LocalDate.now().toString());
        return result;
    }

    // ---------------------------------------------------------------- 内部

    private long createInvoiceInternal(InvoiceRequest request, Long shipmentId) {
        if (request.customerId() == null) {
            throw new BizException(1002, "请选择客户");
        }
        requireCustomer(request.customerId());
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "发票明细不能为空");
        }
        String invoiceType = request.invoiceType() == null || request.invoiceType().isBlank()
                ? "NORMAL" : request.invoiceType().trim().toUpperCase();
        if (!List.of("NORMAL", "CREDIT").contains(invoiceType)) {
            throw new BizException(1002, "发票类型只支持 NORMAL / CREDIT");
        }
        String currency = normalizeCurrency(request.currency());
        LocalDate invoiceDate = request.invoiceDate() == null ? LocalDate.now() : request.invoiceDate();
        LocalDate dueDate = request.dueDate() == null ? invoiceDate.plusDays(30) : request.dueDate();
        if (dueDate.isBefore(invoiceDate)) {
            throw new BizException(1002, "到期日不能早于开票日期");
        }
        BigDecimal rate = postingService.rateFor(currency, invoiceDate);
        BigDecimal taxRate = request.taxRate() == null ? new BigDecimal("0.1300") : request.taxRate();
        if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(1002, "税率必须在 0~1 之间");
        }
        boolean includesTax = request.priceIncludesTax() == null || request.priceIncludesTax();

        BigDecimal entered = BigDecimal.ZERO;
        List<Object[]> itemRows = new ArrayList<>();
        for (InvoiceItem item : request.items()) {
            if (item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "明细数量必须大于 0");
            }
            if (item.price() == null || item.price().signum() < 0) {
                throw new BizException(1002, "明细单价不能为负");
            }
            BigDecimal amount = money(item.qty().multiply(item.price()));
            entered = entered.add(amount);
            itemRows.add(new Object[]{item.productId(), item.qty(), item.price(), amount});
        }
        if (entered.signum() <= 0) {
            throw new BizException(1002, "发票金额必须大于 0");
        }
        BigDecimal net;
        BigDecimal tax;
        BigDecimal total;
        if (includesTax) {
            total = entered;
            net = taxRate.signum() == 0 ? total
                    : total.divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
            tax = total.subtract(net);
        } else {
            net = entered;
            tax = money(net.multiply(taxRate));
            total = net.add(tax);
        }
        BigDecimal baseTotal = money(total.multiply(rate));
        BigDecimal baseTax = money(tax.multiply(rate));
        BigDecimal baseNet = baseTotal.subtract(baseTax);

        String invoiceNo = codeGenerator.next("AR");
        Long invoiceId = jdbcTemplate.queryForObject("""
                insert into ar_invoice (invoice_no, invoice_type, customer_id, sales_order_id, shipment_id,
                                        invoice_date, due_date, currency, exchange_rate, tax_rate,
                                        price_includes_tax, net_amount, tax_amount, total_amount,
                                        base_net, base_tax, base_total, remark, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, invoiceNo, invoiceType, request.customerId(),
                request.salesOrderId(), shipmentId, invoiceDate, dueDate, currency, rate, taxRate,
                includesTax, net, tax, total, baseNet, baseTax, baseTotal,
                blankToNull(request.remark()), CurrentUser.name());
        if (invoiceId == null) {
            throw new BizException(1002, "发票创建失败");
        }
        for (Object[] row : itemRows) {
            jdbcTemplate.update("""
                    insert into ar_invoice_item (invoice_id, product_id, qty, price, amount)
                    values (?, ?, ?, ?, ?)
                    """, invoiceId, row[0], row[1], row[2], row[3]);
        }

        List<PostingService.PostingLine> lines;
        if ("CREDIT".equals(invoiceType)) {
            lines = List.of(
                    new PostingService.PostingLine("REVENUE", "销售退货冲减收入", baseNet, BigDecimal.ZERO),
                    new PostingService.PostingLine("OUTPUT_TAX", "销售退货冲减销项税", baseTax, BigDecimal.ZERO),
                    new PostingService.PostingLine("AR", "销售退货冲减应收", BigDecimal.ZERO, baseTotal));
        } else {
            lines = List.of(
                    new PostingService.PostingLine("AR", "销售应收", baseTotal, BigDecimal.ZERO),
                    new PostingService.PostingLine("REVENUE", "销售收入", BigDecimal.ZERO, baseNet),
                    new PostingService.PostingLine("OUTPUT_TAX", "销项税额", BigDecimal.ZERO, baseTax));
        }
        postingService.post(invoiceDate, "AR_INVOICE", invoiceId, invoiceNo, currency, rate,
                "销售发票 " + invoiceNo, lines);
        return invoiceId;
    }

    private void requireCustomer(long customerId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from rm_customer where id = ? and enabled", Integer.class, customerId);
        if (count == null || count == 0) {
            throw new BizException(1002, "客户不存在或已停用");
        }
    }

    private String normalizeCurrency(String currency) {
        String value = currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase();
        if (!List.of("CNY", "USD", "EUR").contains(value)) {
            throw new BizException(1002, "币种只支持 CNY / USD / EUR");
        }
        return value;
    }

    private String oneOrNull(String sql, Object... args) {
        List<String> rows = jdbcTemplate.queryForList(sql, String.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
