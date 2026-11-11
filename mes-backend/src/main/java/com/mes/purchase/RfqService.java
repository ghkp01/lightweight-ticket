package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.platform.audit.OperLogService;
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
import java.util.Set;

/**
 * 询价比价：从申请中「需要询价」的行生成询价单，录入多家报价，比价后选定一家，
 * 选定结果沉淀到供应商价格库，并回填申请行的目标价与建议供应商。
 */
@Service
public class RfqService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final OperLogService operLogService;
    private final MasterDataPort masterData;

    public RfqService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                      OperLogService operLogService, MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.operLogService = operLogService;
        this.masterData = masterData;
    }

    public record CreateRequest(List<Long> itemIds, LocalDate deadline, String remark) {
    }

    public record QuoteItem(Long rfqItemId, BigDecimal price, String remark) {
    }

    public record QuoteRequest(Long supplierId, Integer leadDays, String paymentTerm,
                               LocalDate validUntil, String remark, List<QuoteItem> items) {
    }

    public record SelectRequest(Long quoteId) {
    }

    public PageResult list(String keyword, String status, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder where = new StringBuilder("""
                 where (f.rfq_no ilike ? or coalesce(r.req_no, '') ilike ?
                        or coalesce(r.purpose, '') ilike ?)
                """);
        List<Object> args = new ArrayList<>(List.of(like, like, like));
        if (status != null && !status.isBlank()) {
            where.append(" and f.status = ?");
            args.add(status.trim());
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from purchase_rfq f
                left join purchase_requisition r on r.id = f.requisition_id
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select f.id, f.rfq_no as "rfqNo", f.requisition_id as "requisitionId", r.req_no as "reqNo",
                       r.purpose, f.status, to_char(f.deadline, 'YYYY-MM-DD') as "deadline",
                       f.selected_quote_id as "selectedQuoteId",
                       (select count(*) from purchase_rfq_item i where i.rfq_id = f.id) as "itemCount",
                       (select count(*) from purchase_quote q where q.rfq_id = f.id) as "quoteCount",
                       (select s.name from purchase_quote q join supplier s on s.id = q.supplier_id
                         where q.id = f.selected_quote_id) as "selectedSupplierName",
                       f.remark, f.created_by as "createdBy",
                       to_char(f.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from purchase_rfq f
                left join purchase_requisition r on r.id = f.requisition_id
                """ + where + " order by f.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> summary() {
        return jdbcTemplate.queryForMap("""
                select count(*) filter (where status = 'OPEN') as "open",
                       count(*) filter (where status = 'QUOTED') as "quoted",
                       count(*) filter (where status = 'SELECTED') as "selected",
                       count(*) filter (where status = 'CANCELLED') as "cancelled",
                       (select count(*) from purchase_quote) as "quotes",
                       (select count(*) from supplier_price) as "prices"
                from purchase_rfq
                """);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select f.id, f.rfq_no as "rfqNo", f.requisition_id as "requisitionId", r.req_no as "reqNo",
                       r.purpose, f.status, to_char(f.deadline, 'YYYY-MM-DD') as "deadline",
                       f.selected_quote_id as "selectedQuoteId",
                       (select s.name from purchase_quote q join supplier s on s.id = q.supplier_id
                         where q.id = f.selected_quote_id) as "selectedSupplierName",
                       f.remark, f.created_by as "createdBy",
                       to_char(f.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from purchase_rfq f
                left join purchase_requisition r on r.id = f.requisition_id
                where f.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2010, "询价单不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId",
                       i.qty, i.requisition_item_id as "requisitionItemId", i.remark
                from purchase_rfq_item i
                where i.rfq_id = ?
                order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        List<Map<String, Object>> quotes = jdbcTemplate.queryForList("""
                select q.id, q.supplier_id as "supplierId", s.code as "supplierCode", s.name as "supplierName",
                       q.lead_days as "leadDays", q.payment_term as "paymentTerm",
                       to_char(q.valid_until, 'YYYY-MM-DD') as "validUntil",
                       q.total_amount as "totalAmount", q.status, q.remark,
                       to_char(q.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from purchase_quote q
                join supplier s on s.id = q.supplier_id
                where q.rfq_id = ?
                order by q.total_amount, q.id
                """, id);
        List<Map<String, Object>> quoteItems = jdbcTemplate.queryForList("""
                select qi.quote_id as "quoteId", qi.rfq_item_id as "rfqItemId", qi.price, qi.amount
                from purchase_quote_item qi
                join purchase_quote q on q.id = qi.quote_id
                where q.rfq_id = ?
                """, id);

        Map<Long, List<Map<String, Object>>> byQuote = new LinkedHashMap<>();
        Map<Long, BigDecimal> lowest = new LinkedHashMap<>();
        for (Map<String, Object> line : quoteItems) {
            long quoteId = ((Number) line.get("quoteId")).longValue();
            byQuote.computeIfAbsent(quoteId, key -> new ArrayList<>()).add(line);
            long rfqItemId = ((Number) line.get("rfqItemId")).longValue();
            BigDecimal price = decimal(line.get("price"));
            if (price.signum() > 0) {
                BigDecimal current = lowest.get(rfqItemId);
                if (current == null || price.compareTo(current) < 0) {
                    lowest.put(rfqItemId, price);
                }
            }
        }
        for (Map<String, Object> quote : quotes) {
            long quoteId = ((Number) quote.get("id")).longValue();
            List<Map<String, Object>> lines = byQuote.getOrDefault(quoteId, new ArrayList<>());
            for (Map<String, Object> line : lines) {
                long rfqItemId = ((Number) line.get("rfqItemId")).longValue();
                BigDecimal best = lowest.get(rfqItemId);
                line.put("lowest", best != null && decimal(line.get("price")).compareTo(best) == 0);
            }
            quote.put("items", lines);
        }
        for (Map<String, Object> item : items) {
            BigDecimal best = lowest.get(((Number) item.get("id")).longValue());
            item.put("lowestPrice", best);
        }
        detail.put("items", items);
        detail.put("quotes", quotes);
        BigDecimal bestTotal = null;
        Long bestQuoteId = null;
        for (Map<String, Object> quote : quotes) {
            BigDecimal total = decimal(quote.get("totalAmount"));
            if (total.signum() > 0 && (bestTotal == null || total.compareTo(bestTotal) < 0)) {
                bestTotal = total;
                bestQuoteId = ((Number) quote.get("id")).longValue();
            }
        }
        detail.put("bestQuoteId", bestQuoteId);
        detail.put("bestTotal", bestTotal);
        return detail;
    }

    /** 从采购申请生成询价单：只取需要询价、还有剩余数量、且没有在询价中的明细 */
    @Transactional
    public long createFromRequisition(long requisitionId, CreateRequest request) {
        List<Map<String, Object>> reqRows = jdbcTemplate.queryForList("""
                select id, req_no, status from purchase_requisition where id = ? for update
                """, requisitionId);
        if (reqRows.isEmpty()) {
            throw new BizException(2009, "采购申请不存在");
        }
        Map<String, Object> req = reqRows.get(0);
        String reqStatus = String.valueOf(req.get("status"));
        if (!List.of("APPROVED", "PARTIAL").contains(reqStatus)) {
            throw new BizException(2014, "只有已批准或部分转单的申请可以询价");
        }
        List<Long> only = request == null || request.itemIds() == null ? List.of() : request.itemIds();
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                select i.id, i.product_id, i.qty, i.converted_qty
                from purchase_requisition_item i
                where i.req_id = ? and i.need_quote = true
                order by i.id
                """, requisitionId);
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> item : candidates) {
            long itemId = ((Number) item.get("id")).longValue();
            if (!only.isEmpty() && !only.contains(itemId)) {
                continue;
            }
            BigDecimal remaining = decimal(item.get("qty")).subtract(decimal(item.get("converted_qty")));
            if (remaining.signum() <= 0) {
                continue;
            }
            Integer active = jdbcTemplate.queryForObject("""
                    select count(*) from purchase_rfq_item i
                    join purchase_rfq f on f.id = i.rfq_id
                    where i.requisition_item_id = ? and f.status <> 'CANCELLED'
                    """, Integer.class, itemId);
            if (active != null && active > 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("remaining", remaining);
            picked.add(row);
        }
        if (picked.isEmpty()) {
            throw new BizException(1002, "没有可询价的明细：请检查申请行是否勾选需询价、有剩余数量且未在询价中");
        }
        String rfqNo = codeGenerator.next("RFQ");
        Long id = jdbcTemplate.queryForObject("""
                insert into purchase_rfq (rfq_no, requisition_id, status, deadline, remark, created_by)
                values (?, ?, 'OPEN', ?, ?, ?)
                returning id
                """, Long.class, rfqNo, requisitionId,
                request == null ? null : request.deadline(),
                request == null ? null : blankToNull(request.remark()), CurrentUser.name());
        for (Map<String, Object> item : picked) {
            jdbcTemplate.update("""
                    insert into purchase_rfq_item (rfq_id, requisition_item_id, product_id, qty, remark)
                    values (?, ?, ?, ?, null)
                    """, id, item.get("id"), item.get("product_id"), item.get("remaining"));
        }
        operLogService.touch("RFQ", id, rfqNo,
                "由采购申请 " + req.get("req_no") + " 发起询价 " + rfqNo + "，共 " + picked.size() + " 行");
        operLogService.action("发起询价");
        return id;
    }

    /** 录入或覆盖某供应商的报价 */
    @Transactional
    public Map<String, Object> upsertQuote(long rfqId, QuoteRequest request) {
        Map<String, Object> rfq = loadRfqForUpdate(rfqId);
        String status = String.valueOf(rfq.get("status"));
        if (List.of("SELECTED", "CANCELLED").contains(status)) {
            throw new BizException(2014, "已选定或已取消的询价单不能再录报价");
        }
        if (request == null || request.supplierId() == null) {
            throw new BizException(1002, "请选择报价供应商");
        }
        Integer supplier = jdbcTemplate.queryForObject(
                "select count(*) from supplier where id = ? and enabled = true", Integer.class,
                request.supplierId());
        if (supplier == null || supplier == 0) {
            throw new BizException(2006, "供应商不存在或已停用");
        }
        List<Map<String, Object>> rfqItems = jdbcTemplate.queryForList("""
                select id, product_id, qty from purchase_rfq_item where rfq_id = ?
                """, rfqId);
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> item : rfqItems) {
            byId.put(((Number) item.get("id")).longValue(), item);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (QuoteItem line : request.items() == null ? List.<QuoteItem>of() : request.items()) {
            if (line == null || line.rfqItemId() == null) {
                continue;
            }
            Map<String, Object> item = byId.get(line.rfqItemId());
            if (item == null) {
                throw new BizException(1002, "报价明细不属于这张询价单");
            }
            BigDecimal price = line.price() == null ? BigDecimal.ZERO : line.price();
            if (price.signum() < 0) {
                throw new BizException(1002, "报价单价不能为负数");
            }
            BigDecimal amount = price.multiply(decimal(item.get("qty"))).setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rfqItemId", line.rfqItemId());
            row.put("price", price);
            row.put("amount", amount);
            row.put("remark", blankToNull(line.remark()));
            lines.add(row);
            total = total.add(amount);
        }
        if (lines.isEmpty()) {
            throw new BizException(1002, "请至少填写一条报价");
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                select id from purchase_quote where rfq_id = ? and supplier_id = ?
                """, rfqId, request.supplierId());
        long quoteId;
        if (existing.isEmpty()) {
            quoteId = jdbcTemplate.queryForObject("""
                    insert into purchase_quote (rfq_id, supplier_id, lead_days, payment_term, valid_until,
                                                total_amount, status, remark, created_by)
                    values (?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?)
                    returning id
                    """, Long.class, rfqId, request.supplierId(),
                    request.leadDays() == null ? 7 : Math.max(request.leadDays(), 0),
                    blankToNull(request.paymentTerm()), request.validUntil(), total,
                    blankToNull(request.remark()), CurrentUser.name());
        } else {
            quoteId = ((Number) existing.get(0).get("id")).longValue();
            jdbcTemplate.update("""
                    update purchase_quote set lead_days = ?, payment_term = ?, valid_until = ?,
                           total_amount = ?, status = 'SUBMITTED', remark = ?, created_by = ?, created_at = now()
                    where id = ?
                    """, request.leadDays() == null ? 7 : Math.max(request.leadDays(), 0),
                    blankToNull(request.paymentTerm()), request.validUntil(), total,
                    blankToNull(request.remark()), CurrentUser.name(), quoteId);
            jdbcTemplate.update("delete from purchase_quote_item where quote_id = ?", quoteId);
        }
        for (Map<String, Object> line : lines) {
            jdbcTemplate.update("""
                    insert into purchase_quote_item (quote_id, rfq_item_id, price, amount, remark)
                    values (?, ?, ?, ?, ?)
                    """, quoteId, line.get("rfqItemId"), line.get("price"), line.get("amount"),
                    line.get("remark"));
        }
        if ("OPEN".equals(status)) {
            jdbcTemplate.update("update purchase_rfq set status = 'QUOTED' where id = ?", rfqId);
        }
        operLogService.touch("RFQ", rfqId, String.valueOf(rfq.get("rfq_no")),
                "录入报价：供应商 " + request.supplierId() + "，合计 " + total.stripTrailingZeros().toPlainString());
        operLogService.action("录入报价");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quoteId", quoteId);
        result.put("totalAmount", total);
        return result;
    }

    @Transactional
    public void deleteQuote(long rfqId, long quoteId) {
        Map<String, Object> rfq = loadRfqForUpdate(rfqId);
        if ("SELECTED".equals(String.valueOf(rfq.get("status")))) {
            throw new BizException(2014, "已选定的询价单不能删除报价");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id from purchase_quote where id = ? and rfq_id = ?", quoteId, rfqId);
        if (rows.isEmpty()) {
            throw new BizException(2011, "报价不存在");
        }
        jdbcTemplate.update("delete from purchase_quote_item where quote_id = ?", quoteId);
        jdbcTemplate.update("delete from purchase_quote where id = ?", quoteId);
        Integer left = jdbcTemplate.queryForObject(
                "select count(*) from purchase_quote where rfq_id = ?", Integer.class, rfqId);
        jdbcTemplate.update("update purchase_rfq set status = ? where id = ?",
                left != null && left > 0 ? "QUOTED" : "OPEN", rfqId);
    }

    /** 选定报价：写价格库，并把申请行的目标价与建议供应商回填 */
    @Transactional
    public Map<String, Object> selectQuote(long rfqId, SelectRequest request) {
        Map<String, Object> rfq = loadRfqForUpdate(rfqId);
        String status = String.valueOf(rfq.get("status"));
        if (List.of("SELECTED", "CANCELLED").contains(status)) {
            throw new BizException(2014, "当前询价单不能再次选定");
        }
        if (request == null || request.quoteId() == null) {
            throw new BizException(1002, "请选择要选定的报价");
        }
        List<Map<String, Object>> quotes = jdbcTemplate.queryForList("""
                select q.id, q.supplier_id, q.lead_days, q.valid_until, q.total_amount, s.name as supplier_name
                from purchase_quote q join supplier s on s.id = q.supplier_id
                where q.id = ? and q.rfq_id = ?
                """, request.quoteId(), rfqId);
        if (quotes.isEmpty()) {
            throw new BizException(2011, "报价不存在或不属于这张询价单");
        }
        Map<String, Object> quote = quotes.get(0);
        long supplierId = ((Number) quote.get("supplier_id")).longValue();
        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                select qi.rfq_item_id, qi.price, i.product_id, i.requisition_item_id
                from purchase_quote_item qi
                join purchase_rfq_item i on i.id = qi.rfq_item_id
                where qi.quote_id = ?
                """, request.quoteId());
        String rfqNo = String.valueOf(rfq.get("rfq_no"));
        for (Map<String, Object> line : lines) {
            jdbcTemplate.update("""
                    insert into supplier_price (supplier_id, product_id, price, lead_days, valid_from, valid_to,
                                                source, source_no, remark)
                    values (?, ?, ?, ?, current_date, ?, 'RFQ', ?, ?)
                    """, supplierId, line.get("product_id"), line.get("price"),
                    quote.get("lead_days"), quote.get("valid_until"), rfqNo, "询价单 " + rfqNo + " 选定");
            Object reqItemId = line.get("requisition_item_id");
            if (reqItemId != null) {
                jdbcTemplate.update("""
                        update purchase_requisition_item set target_price = ?, suggest_supplier_id = ?
                        where id = ?
                        """, line.get("price"), supplierId, reqItemId);
            }
        }
        jdbcTemplate.update("update purchase_quote set status = 'REJECTED' where rfq_id = ?", rfqId);
        jdbcTemplate.update("update purchase_quote set status = 'SELECTED' where id = ?", request.quoteId());
        jdbcTemplate.update("""
                update purchase_rfq set status = 'SELECTED', selected_quote_id = ? where id = ?
                """, request.quoteId(), rfqId);
        operLogService.touch("RFQ", rfqId, rfqNo,
                "选定报价：供应商 " + quote.get("supplier_name") + "，金额 "
                        + decimal(quote.get("total_amount")).stripTrailingZeros().toPlainString());
        operLogService.action("选定报价");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rfqId", rfqId);
        result.put("quoteId", request.quoteId());
        result.put("supplierId", supplierId);
        result.put("totalAmount", quote.get("total_amount"));
        result.put("priceCount", lines.size());
        return result;
    }

    @Transactional
    public void cancel(long rfqId) {
        Map<String, Object> rfq = loadRfqForUpdate(rfqId);
        if (!List.of("OPEN", "QUOTED").contains(String.valueOf(rfq.get("status")))) {
            throw new BizException(2014, "只有待报价或已报价的询价单可以取消");
        }
        jdbcTemplate.update("update purchase_rfq set status = 'CANCELLED' where id = ?", rfqId);
        operLogService.touch("RFQ", rfqId, String.valueOf(rfq.get("rfq_no")),
                "取消询价单 " + rfq.get("rfq_no"));
        operLogService.action("取消");
    }

    public List<Map<String, Object>> prices(Long supplierId, Long productId, String keyword,
                                           boolean validOnly, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                select sp.id, sp.supplier_id as "supplierId", s.code as "supplierCode", s.name as "supplierName",
                       sp.product_id as "productId",
                       sp.price, sp.lead_days as "leadDays",
                       to_char(sp.valid_from, 'YYYY-MM-DD') as "validFrom",
                       to_char(sp.valid_to, 'YYYY-MM-DD') as "validTo",
                       sp.source, sp.source_no as "sourceNo", sp.remark,
                       (sp.valid_to is null or sp.valid_to >= current_date) as "valid"
                from supplier_price sp
                join supplier s on s.id = sp.supplier_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (supplierId != null) {
            sql.append(" and sp.supplier_id = ?");
            args.add(supplierId);
        }
        if (productId != null) {
            sql.append(" and sp.product_id = ?");
            args.add(productId);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 产品编码/名称的模糊匹配交给 masterdata，本模块只按 id 集合过滤
            sql.append(" and (s.name ilike ? or coalesce(sp.source_no, '') ilike ?");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
            Set<Long> productIds = masterData.searchProductIds(keyword);
            if (!productIds.isEmpty()) {
                sql.append(" or sp.product_id in (")
                        .append(String.join(",", productIds.stream().map(id -> "?").toList()))
                        .append(")");
                args.addAll(productIds);
            }
            sql.append(")");
        }
        if (validOnly) {
            sql.append(" and (sp.valid_to is null or sp.valid_to >= current_date)");
        }
        int size = limit == null || limit <= 0 ? 200 : Math.min(limit, 500);
        sql.append(" order by sp.id desc limit ").append(size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        MasterDataViews.fillProductNames(masterData, rows);
        return rows;
    }

    private Map<String, Object> loadRfqForUpdate(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, rfq_no, status, requisition_id, selected_quote_id
                from purchase_rfq where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2010, "询价单不存在");
        }
        return rows.get(0);
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
