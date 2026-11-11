package com.mes.purchase;

import com.mes.purchase.api.PurchaseDocPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购单据的只读入口：单号、单价、退货金额。
 *
 * <p>和 {@link SupplyReader} 一样是薄实现，只读不写，不参与建单与匹配的规则。</p>
 */
@Component
public class PurchaseDocReader implements PurchaseDocPort {

    private final JdbcTemplate jdbcTemplate;

    public PurchaseDocReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Long, String> orderNos(Collection<Long> purchaseOrderIds) {
        Map<Long, String> map = new LinkedHashMap<>();
        List<Long> ids = distinct(purchaseOrderIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, order_no from purchase_order where id in (" + marks(ids) + ")", ids.toArray())) {
            map.put(((Number) row.get("id")).longValue(), String.valueOf(row.get("order_no")));
        }
        return map;
    }

    @Override
    public Map<Long, BigDecimal> itemPrices(Collection<Long> purchaseOrderItemIds) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        List<Long> ids = distinct(purchaseOrderItemIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, price from purchase_order_item where id in (" + marks(ids) + ")",
                ids.toArray())) {
            map.put(((Number) row.get("id")).longValue(),
                    row.get("price") == null ? BigDecimal.ZERO : decimal(row.get("price")));
        }
        return map;
    }

    @Override
    public Map<Long, ReturnStat> completedReturnStatByInbound(Collection<Long> inboundOrderIds) {
        Map<Long, ReturnStat> map = new LinkedHashMap<>();
        List<Long> ids = distinct(inboundOrderIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select inbound_order_id as "inboundOrderId", count(*) as cnt,
                       coalesce(sum(total_amount), 0) as amount
                from purchase_return
                where status = 'COMPLETED' and inbound_order_id in (""" + marks(ids) + """
                )
                group by inbound_order_id
                """, ids.toArray())) {
            map.put(((Number) row.get("inboundOrderId")).longValue(),
                    new ReturnStat(((Number) row.get("cnt")).intValue(), decimal(row.get("amount"))));
        }
        return map;
    }

    @Override
    public ReturnStat completedReturnsSince(LocalDate from) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select count(*) as cnt, coalesce(sum(total_amount), 0) as amount
                from purchase_return
                where status = 'COMPLETED' and completed_at >= ?
                """, java.sql.Date.valueOf(from));
        return new ReturnStat(((Number) row.get("cnt")).intValue(), decimal(row.get("amount")));
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
