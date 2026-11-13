package com.mes.report;

import com.mes.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月结报表：库存月结 + 往来（采购 / 应付、销售 / 应收）月结。
 *
 * 库存口径：
 * - 期初 = 该月 1 日之前所有流水的累计（数量与金额）；
 * - 本月入库 / 出库 = 当月流水按正负拆开合计；
 * - 期末 = 期初 + 入库 − 出库，与库存流水天然自洽，不做额外调整分录。
 *
 * 往来口径：
 * - 采购额取采购单下单金额（不含已取消）；
 * - 暂估取采购入库完成时生成的暂估金额；
 * - 应付发票取当月登记的蓝字发票减去红字发票；
 * - 销售额取销售订单下单金额（不含已取消），发货额取当月发货单金额。
 */
@Service
public class MonthCloseService {

    private final JdbcTemplate jdbcTemplate;

    public MonthCloseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 可选月份：按流水最早月份到当前月 */
    public List<String> months() {
        List<String> rows = jdbcTemplate.queryForList("""
                select to_char(month, 'YYYY-MM') from generate_series(
                    date_trunc('month', coalesce((select min(created_at) from rm_stock_txn), now()) - interval '1 month'),
                    date_trunc('month', now()),
                    interval '1 month') as month
                order by month desc
                """, String.class);
        return rows;
    }

    /** 库存月结：按仓库 + 物料给出期初、入库、出库与期末 */
    public Map<String, Object> stockClose(String month) {
        String period = normalizeMonth(month);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                with base as (
                    select t.product_id, t.warehouse_id,
                           sum(case when t.created_at < to_date(?, 'YYYY-MM-DD') then t.qty else 0 end) as open_qty,
                           sum(case when t.created_at < to_date(?, 'YYYY-MM-DD') then t.amount else 0 end) as open_amount,
                           sum(case when t.created_at >= to_date(?, 'YYYY-MM-DD') and t.qty > 0 then t.qty else 0 end) as in_qty,
                           sum(case when t.created_at >= to_date(?, 'YYYY-MM-DD') and t.qty > 0 then t.amount else 0 end) as in_amount,
                           sum(case when t.created_at >= to_date(?, 'YYYY-MM-DD') and t.qty < 0 then -t.qty else 0 end) as out_qty,
                           sum(case when t.created_at >= to_date(?, 'YYYY-MM-DD') and t.qty < 0 then -t.amount else 0 end) as out_amount
                    from rm_stock_txn t
                    where t.created_at < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                    group by t.product_id, t.warehouse_id
                )
                select p.code as "productCode", p.name as "productName", p.unit,
                       w.name as "warehouseName",
                       round(b.open_qty, 4) as "openQty", round(b.open_amount, 2) as "openAmount",
                       round(b.in_qty, 4) as "inQty", round(b.in_amount, 2) as "inAmount",
                       round(b.out_qty, 4) as "outQty", round(b.out_amount, 2) as "outAmount",
                       round(b.open_qty + b.in_qty - b.out_qty, 4) as "closeQty",
                       round(b.open_amount + b.in_amount - b.out_amount, 2) as "closeAmount"
                from base b
                join rm_product p on p.id = b.product_id
                join rm_warehouse w on w.id = b.warehouse_id
                where b.open_qty <> 0 or b.in_qty <> 0 or b.out_qty <> 0
                order by w.id, p.code
                """, period + "-01", period + "-01", period + "-01", period + "-01",
                period + "-01", period + "-01", period + "-01");

        BigDecimal openQty = BigDecimal.ZERO;
        BigDecimal inQty = BigDecimal.ZERO;
        BigDecimal outQty = BigDecimal.ZERO;
        BigDecimal closeAmount = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            openQty = openQty.add(decimal(row.get("openQty")));
            inQty = inQty.add(decimal(row.get("inQty")));
            outQty = outQty.add(decimal(row.get("outQty")));
            closeAmount = closeAmount.add(decimal(row.get("closeAmount")));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rows", rows.size());
        summary.put("openQty", openQty);
        summary.put("inQty", inQty);
        summary.put("outQty", outQty);
        summary.put("closeQty", openQty.add(inQty).subtract(outQty));
        summary.put("closeAmount", closeAmount.setScale(2, RoundingMode.HALF_UP));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", period);
        result.put("periodStart", period + "-01");
        result.put("summary", summary);
        result.put("rows", rows);
        return result;
    }

    /** 库存月结明细分页：汇总仍按整月全部物料统计，明细按页返回 */
    public Map<String, Object> stockClosePage(String month, int page, int size) {
        String period = normalizeMonth(month);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String baseCte = """
                with base as (
                    select t.product_id, t.warehouse_id,
                           sum(case when t.created_at < to_date(?, 'YYYY-MM-DD') then t.qty else 0 end) as open_qty,
                           sum(case when t.created_at < to_date(?, 'YYYY-MM-DD') then t.amount else 0 end) as open_amount,
                           sum(case when t.created_at >= to_date(?, 'YYYY-MM-DD') and t.qty > 0 then t.qty else 0 end) as in_qty,
                           sum(case when t.created_at >= to_date(?, 'YYYY-MM-DD') and t.qty > 0 then t.amount else 0 end) as in_amount,
                           sum(case when t.created_at >= to_date(?, 'YYYY-MM-DD') and t.qty < 0 then -t.qty else 0 end) as out_qty,
                           sum(case when t.created_at >= to_date(?, 'YYYY-MM-DD') and t.qty < 0 then -t.amount else 0 end) as out_amount
                    from rm_stock_txn t
                    where t.created_at < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                    group by t.product_id, t.warehouse_id
                )
                """;
        Object[] dateArgs = new Object[]{period + "-01", period + "-01", period + "-01",
                period + "-01", period + "-01", period + "-01", period + "-01"};
        Map<String, Object> totals = jdbcTemplate.queryForMap(baseCte + """
                select count(*) as "rows",
                       coalesce(sum(b.open_qty), 0) as "openQty",
                       coalesce(sum(b.in_qty), 0) as "inQty",
                       coalesce(sum(b.out_qty), 0) as "outQty",
                       coalesce(sum(b.open_amount), 0) as "openAmount",
                       coalesce(sum(b.in_amount), 0) as "inAmount",
                       coalesce(sum(b.out_amount), 0) as "outAmount"
                from base b
                where b.open_qty <> 0 or b.in_qty <> 0 or b.out_qty <> 0
                """, dateArgs);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(baseCte + """
                select p.code as "productCode", p.name as "productName", p.unit,
                       w.name as "warehouseName",
                       round(b.open_qty, 4) as "openQty", round(b.open_amount, 2) as "openAmount",
                       round(b.in_qty, 4) as "inQty", round(b.in_amount, 2) as "inAmount",
                       round(b.out_qty, 4) as "outQty", round(b.out_amount, 2) as "outAmount",
                       round(b.open_qty + b.in_qty - b.out_qty, 4) as "closeQty",
                       round(b.open_amount + b.in_amount - b.out_amount, 2) as "closeAmount"
                from base b
                join rm_product p on p.id = b.product_id
                join rm_warehouse w on w.id = b.warehouse_id
                where b.open_qty <> 0 or b.in_qty <> 0 or b.out_qty <> 0
                order by w.id, p.code
                limit ? offset ?
                """, period + "-01", period + "-01", period + "-01",
                period + "-01", period + "-01", period + "-01", period + "-01",
                safeSize, (long) (safePage - 1) * safeSize);

        BigDecimal openQty = decimal(totals.get("openQty"));
        BigDecimal inQty = decimal(totals.get("inQty"));
        BigDecimal outQty = decimal(totals.get("outQty"));
        BigDecimal closeAmount = decimal(totals.get("openAmount"))
                .add(decimal(totals.get("inAmount")))
                .subtract(decimal(totals.get("outAmount")));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rows", ((Number) totals.get("rows")).intValue());
        summary.put("openQty", openQty);
        summary.put("inQty", inQty);
        summary.put("outQty", outQty);
        summary.put("closeQty", openQty.add(inQty).subtract(outQty));
        summary.put("closeAmount", closeAmount.setScale(2, RoundingMode.HALF_UP));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", period);
        result.put("periodStart", period + "-01");
        result.put("summary", summary);
        result.put("rows", rows);
        result.put("total", summary.get("rows"));
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    /** 往来月结：采购 / 应付与销售 / 应收 */
    public Map<String, Object> settlement(String month) {
        String period = normalizeMonth(month);
        Map<String, Object> purchase = jdbcTemplate.queryForMap("""
                select coalesce(sum(total_amount), 0) as "orderAmount",
                       count(*) as "orderCount"
                from rm_purchase_order
                where status <> 'CANCELLED' and order_date >= to_date(?, 'YYYY-MM-DD')
                  and order_date < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                """, period + "-01", period + "-01");
        Map<String, Object> accrual = jdbcTemplate.queryForMap("""
                select coalesce(sum(amount), 0) as "amount", count(*) as "count"
                from rm_ap_accrual
                where created_at >= to_date(?, 'YYYY-MM-DD')
                  and created_at < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                """, period + "-01", period + "-01");
        Map<String, Object> invoice = jdbcTemplate.queryForMap("""
                select coalesce(sum(case when invoice_type = 'CREDIT' then gross_amount else gross_amount end), 0)
                           as "grossAmount",
                       count(*) as "count"
                from rm_ap_invoice
                where status <> 'CANCELLED' and invoice_date >= to_date(?, 'YYYY-MM-DD')
                  and invoice_date < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                """, period + "-01", period + "-01");
        Map<String, Object> payment = jdbcTemplate.queryForMap("""
                select coalesce(sum(amount), 0) as "amount", count(*) as "count"
                from rm_ap_payment
                where pay_date >= to_date(?, 'YYYY-MM-DD')
                  and pay_date < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                """, period + "-01", period + "-01");
        // 销售/应收按已开具的销售发票（本位币）统计，与应收台账、总账口径一致；
        // 发货单金额仅作业务量参考，不再直接当作应收
        Map<String, Object> sales = jdbcTemplate.queryForMap("""
                select coalesce(sum(base_total), 0) as "orderAmount", count(*) as "orderCount"
                from rm_ar_invoice
                where status <> 'CANCELLED' and invoice_type = 'NORMAL'
                  and invoice_date >= to_date(?, 'YYYY-MM-DD')
                  and invoice_date < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                """, period + "-01", period + "-01");
        Map<String, Object> shipment = jdbcTemplate.queryForMap("""
                select coalesce(sum(i.qty * coalesce(soi.price, 0)), 0) as "amount",
                       count(distinct s.id) as "count"
                from rm_shipment s
                join rm_shipment_item i on i.shipment_id = s.id
                left join rm_sales_order_item soi on soi.id = i.sales_order_item_id
                join rm_sales_order so on so.id = s.sales_order_id and so.currency = 'CNY'
                where s.status in ('SHIPPED', 'SIGNED')
                  and s.created_at >= to_date(?, 'YYYY-MM-DD')
                  and s.created_at < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                """, period + "-01", period + "-01");
        Map<String, Object> purchaseReturn = jdbcTemplate.queryForMap("""
                select coalesce(sum(total_amount), 0) as "amount", count(*) as "count"
                from rm_purchase_return
                where status = 'COMPLETED' and completed_at >= to_date(?, 'YYYY-MM-DD')
                  and completed_at < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                """, period + "-01", period + "-01");
        Map<String, Object> salesReturn = jdbcTemplate.queryForMap("""
                select coalesce(sum(base_total), 0) as "amount", count(*) as "count"
                from rm_ar_invoice
                where status <> 'CANCELLED' and invoice_type = 'CREDIT'
                  and invoice_date >= to_date(?, 'YYYY-MM-DD')
                  and invoice_date < to_date(?, 'YYYY-MM-DD') + interval '1 month'
                """, period + "-01", period + "-01");

        BigDecimal purchaseAmount = decimal(purchase.get("orderAmount"));
        BigDecimal purchaseReturnAmount = decimal(purchaseReturn.get("amount"));
        BigDecimal salesAmount = decimal(sales.get("orderAmount"));
        BigDecimal salesReturnAmount = decimal(salesReturn.get("amount"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", period);
        result.put("purchase", purchase);
        result.put("accrual", accrual);
        result.put("invoice", invoice);
        result.put("payment", payment);
        result.put("purchaseReturn", purchaseReturn);
        result.put("sales", sales);
        result.put("shipment", shipment);
        result.put("salesReturn", salesReturn);
        result.put("purchaseNet", purchaseAmount.subtract(purchaseReturnAmount));
        result.put("salesNet", salesAmount.subtract(salesReturnAmount));
        return result;
    }

    private String normalizeMonth(String month) {
        if (month == null || month.isBlank()) {
            java.time.LocalDate now = java.time.LocalDate.now();
            return String.format("%d-%02d", now.getYear(), now.getMonthValue());
        }
        String text = month.trim();
        if (!text.matches("\\d{4}-\\d{2}")) {
            throw new BizException(1002, "月份格式应为 yyyy-MM：" + month);
        }
        int monthValue = Integer.parseInt(text.substring(5, 7));
        if (monthValue < 1 || monthValue > 12) {
            throw new BizException(1002, "月份不合法：" + month);
        }
        return text;
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    /** 供页面展示的近 N 个月列表（含月份是否已结束） */
    public List<Map<String, Object>> monthOptions(int limit) {
        int size = limit <= 0 ? 12 : Math.min(limit, 36);
        List<String> list = months();
        List<Map<String, Object>> result = new ArrayList<>();
        java.time.LocalDate now = java.time.LocalDate.now();
        String current = String.format("%d-%02d", now.getYear(), now.getMonthValue());
        for (int i = 0; i < Math.min(size, list.size()); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", list.get(i));
            row.put("current", current.equals(list.get(i)));
            result.add(row);
        }
        return result;
    }
}
