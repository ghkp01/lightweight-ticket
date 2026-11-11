package com.mes.purchase;

import com.mes.purchase.api.SupplyPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购供应的只读入口：给物料需求计算提供「在途 / 待转 / 最近价格与交期」。
 *
 * <p>单独抽一个薄实现，而不是塞进 {@link PurchaseOrderService}：
 * 那边背着建单、收货、回写的全部规则，而这里只是几条按物料汇总的查询。</p>
 */
@Component
public class SupplyReader implements SupplyPort {

    private final JdbcTemplate jdbcTemplate;

    public SupplyReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Long, BigDecimal> onWayQty(Collection<Long> productIds) {
        return qtyByProduct("""
                select i.product_id as "productId", sum(i.qty - i.received_qty) as "qty"
                from purchase_order_item i
                join purchase_order o on o.id = i.order_id
                where o.status in ('CONFIRMED', 'PARTIAL') and i.qty > i.received_qty
                  and i.product_id in (%s)
                group by i.product_id
                """, productIds);
    }

    @Override
    public Map<Long, BigDecimal> pendingRequisitionQty(Collection<Long> productIds) {
        return qtyByProduct("""
                select i.product_id as "productId", sum(i.qty - i.converted_qty) as "qty"
                from purchase_requisition_item i
                join purchase_requisition r on r.id = i.req_id
                -- 草稿也算计划内的供给：MRP 一键生成的就是草稿申请，
                -- 漏掉它会导致每跑一次算料都把同一批料再申请一遍
                where r.status in ('DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL') and i.qty > i.converted_qty
                  and i.product_id in (%s)
                group by i.product_id
                """, productIds);
    }

    @Override
    public Map<Long, PriceQuote> lastPriceQuotes(Collection<Long> productIds) {
        Map<Long, PriceQuote> map = new LinkedHashMap<>();
        List<Long> ids = distinct(productIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select distinct on (product_id) product_id as "productId", supplier_id, price, lead_days
                from supplier_price
                where (valid_to is null or valid_to >= current_date)
                  and product_id in (%s)
                order by product_id, valid_from desc, id desc
                """.formatted(marks(ids)), ids.toArray())) {
            long productId = ((Number) row.get("productId")).longValue();
            map.put(productId, new PriceQuote(productId,
                    row.get("supplier_id") == null ? null : ((Number) row.get("supplier_id")).longValue(),
                    decimal(row.get("price")),
                    row.get("lead_days") == null ? null : ((Number) row.get("lead_days")).intValue()));
        }
        return map;
    }

    @Override
    public Map<Long, PriceQuote> lastPurchaseQuotes(Collection<Long> productIds) {
        Map<Long, PriceQuote> map = new LinkedHashMap<>();
        List<Long> ids = distinct(productIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select distinct on (i.product_id) i.product_id as "productId", o.supplier_id, i.price
                from purchase_order_item i
                join purchase_order o on o.id = i.order_id
                where o.status in ('CONFIRMED', 'PARTIAL', 'RECEIVED')
                  and i.product_id in (%s)
                order by i.product_id, o.id desc
                """.formatted(marks(ids)), ids.toArray())) {
            long productId = ((Number) row.get("productId")).longValue();
            map.put(productId, new PriceQuote(productId,
                    row.get("supplier_id") == null ? null : ((Number) row.get("supplier_id")).longValue(),
                    decimal(row.get("price")), null));
        }
        return map;
    }

    @Override
    public Map<Long, SupplierBrief> suppliers(Collection<Long> supplierIds) {
        Map<Long, SupplierBrief> map = new LinkedHashMap<>();
        List<Long> ids = distinct(supplierIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, name, lead_days, buyer from supplier where id in (" + marks(ids) + ")", ids.toArray())) {
            long id = ((Number) row.get("id")).longValue();
            map.put(id, new SupplierBrief(id, String.valueOf(row.get("name")),
                    row.get("lead_days") == null ? null : ((Number) row.get("lead_days")).intValue(),
                    row.get("buyer") == null ? null : String.valueOf(row.get("buyer"))));
        }
        return map;
    }

    private Map<Long, BigDecimal> qtyByProduct(String sqlTemplate, Collection<Long> productIds) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        List<Long> ids = distinct(productIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                sqlTemplate.formatted(marks(ids)), ids.toArray())) {
            map.put(((Number) row.get("productId")).longValue(), decimal(row.get("qty")));
        }
        return map;
    }

    private static List<Long> distinct(Collection<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static String marks(List<Long> ids) {
        return String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
