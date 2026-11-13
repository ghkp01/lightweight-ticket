package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.quality.api.InspectionPort;
import com.mes.warehouse.api.InboundOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商绩效：准时率、来料合格率、退货率、采购金额与价格趋势。
 * 全部按明细现算，不落汇总表，改了数据重算就是新结果。
 *
 * 口径：
 * - 准时率 = 入库完成日 ≤ 采购单预计到货日的批次数 ÷ 有预计到货日的批次数
 * - 来料合格率 = (检验数 − 不良数) ÷ 检验数，只算 INCOMING 类型检验
 * - 退货率 = 采购退货金额 ÷ 采购金额
 * - 综合得分 = 准时率 × 40% + 合格率 × 40% + (1 − 退货率) × 20%
 *
 * <p>收货批次来自仓储契约、检验记录来自质检契约，采购这边只做「挂到供应商身上」的拼装：
 * 预计到货日在采购单上，所以准时与否的判断留在采购。</p>
 */
@Service
public class SupplierPerformanceService {

    private static final BigDecimal W_ON_TIME = new BigDecimal("0.4");
    private static final BigDecimal W_QUALITY = new BigDecimal("0.4");
    private static final BigDecimal W_RETURN = new BigDecimal("0.2");

    private final JdbcTemplate jdbcTemplate;
    private final MasterDataPort masterData;
    private final InboundOrderPort inboundOrderPort;
    private final InspectionPort inspectionPort;

    public SupplierPerformanceService(JdbcTemplate jdbcTemplate, MasterDataPort masterData,
                                      InboundOrderPort inboundOrderPort, InspectionPort inspectionPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.masterData = masterData;
        this.inboundOrderPort = inboundOrderPort;
        this.inspectionPort = inspectionPort;
    }

    /** 供应商绩效排行：按采购金额倒序，区间默认近 90 天 */
    public List<Map<String, Object>> ranking(String from, String to, Long supplierId) {
        return rankingRows(from, to, supplierId);
    }

    /** 绩效排行分页：筛选与 KPI 都在服务端算，页面只拿当前页和汇总 */
    public RankingPage rankingPage(String from, String to, Long supplierId,
                                   String keyword, Boolean onlyRisk, int page, int size) {
        List<Map<String, Object>> filtered = rankingRows(from, to, supplierId).stream()
                .filter(row -> matches(row, keyword, Boolean.TRUE.equals(onlyRisk)))
                .toList();
        Map<String, Object> summary = summaryOf(filtered);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = Math.min((safePage - 1) * safeSize, filtered.size());
        int end = Math.min(offset + safeSize, filtered.size());
        return new RankingPage(filtered.subList(offset, end), filtered.size(), safePage, safeSize, summary);
    }

    public record RankingPage(List<Map<String, Object>> records, long total, int page, int size,
                              Map<String, Object> summary) {
    }

    private boolean matches(Map<String, Object> row, String keyword, boolean onlyRisk) {
        if (onlyRisk) {
            boolean hasData = Boolean.TRUE.equals(row.get("hasData"));
            BigDecimal score = row.get("score") == null ? BigDecimal.valueOf(100) : decimal(row.get("score"));
            if (hasData && score.compareTo(BigDecimal.valueOf(80)) >= 0) {
                return false;
            }
            BigDecimal onTime = row.get("onTimeRate") == null
                    ? BigDecimal.valueOf(100) : decimal(row.get("onTimeRate"));
            BigDecimal pass = row.get("passRate") == null
                    ? BigDecimal.valueOf(100) : decimal(row.get("passRate"));
            if (onTime.compareTo(BigDecimal.valueOf(90)) >= 0
                    && pass.compareTo(BigDecimal.valueOf(95)) >= 0) {
                return false;
            }
        }
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String text = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        return java.util.stream.Stream.of(row.get("code"), row.get("name"), row.get("contact"))
                .map(value -> value == null ? "" : String.valueOf(value).toLowerCase(java.util.Locale.ROOT))
                .anyMatch(value -> value.contains(text));
    }

    /** 汇总口径与页面一致：只统计区间内有往来的供应商 */
    private Map<String, Object> summaryOf(List<Map<String, Object>> rows) {
        List<Map<String, Object>> active = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("hasData")))
                .toList();
        BigDecimal totalAmount = active.stream()
                .map(row -> decimal(row.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> withReceipt = active.stream()
                .filter(row -> intOf(row.get("judgedBatches")) > 0)
                .toList();
        BigDecimal onTime = withReceipt.isEmpty() ? BigDecimal.ZERO
                : withReceipt.stream().map(row -> decimal(row.get("onTimeRate")))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(withReceipt.size()), 2, RoundingMode.HALF_UP);
        List<Map<String, Object>> withInspection = active.stream()
                .filter(row -> intOf(row.get("inspections")) > 0)
                .toList();
        BigDecimal pass = withInspection.isEmpty() ? BigDecimal.ZERO
                : withInspection.stream().map(row -> decimal(row.get("passRate")))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(withInspection.size()), 2, RoundingMode.HALF_UP);
        long risk = active.stream()
                .filter(row -> decimal(row.get("score")).compareTo(BigDecimal.valueOf(80)) < 0)
                .count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", active.size());
        summary.put("totalAmount", totalAmount);
        summary.put("onTime", onTime);
        summary.put("pass", pass);
        summary.put("risk", risk);
        return summary;
    }

    private List<Map<String, Object>> rankingRows(String from, String to, Long supplierId) {
        Date start = parseDate(from, Date.valueOf(java.time.LocalDate.now().minusDays(90)));
        Date end = parseDate(to, Date.valueOf(java.time.LocalDate.now()));
        if (start.after(end)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        StringBuilder supplierWhere = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (supplierId != null) {
            supplierWhere.append(" and s.id = ? ");
            args.add(supplierId);
        }
        // 采购金额 / 单数
        String ordersSql = """
                select supplier_id, count(*) as "orders",
                       coalesce(sum(total_amount), 0) as "amount"
                from purchase_order
                where status <> 'CANCELLED' and order_date between ? and ?
                group by supplier_id
                """;
        // 退货
        String returnSql = """
                select supplier_id, coalesce(sum(total_amount), 0) as "returnAmount"
                from purchase_return
                where status = 'COMPLETED' and completed_at::date between ? and ?
                group by supplier_id
                """;
        // 收货批次与准时情况：入库单来自仓储契约，预计到货日在本模块的采购单上
        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();
        List<InboundOrderPort.InboundOrderView> receiptsInRange =
                inboundOrderPort.completedInbounds(null, startDate, endDate);
        Map<Long, Map<String, Object>> ordersOfInbound = ordersOf(receiptsInRange);
        // 来料检验：质检给原始检验行，供应商由入库单接上
        List<InspectionPort.IncomingInspection> incoming = inspectionPort.incoming(startDate, endDate);
        Map<Long, Long> supplierOfInbound = supplierOfInbound(incoming, receiptsInRange);
        Map<String, Map<String, Object>> orders = selectPairs(ordersSql, start, end);
        Map<String, Map<String, Object>> receipts = aggregateReceipts(receiptsInRange, ordersOfInbound);
        Map<String, Map<String, Object>> qc = aggregateInspections(incoming, supplierOfInbound);
        Map<String, Map<String, Object>> returns = selectPairs(returnSql, start, end);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select s.id, s.code, s.name, s.category as "category", s.rating,
                       s.lead_days as "leadDays",
                       s.enabled, coalesce(s.contact, '') as "contact"
                from supplier s
                """ + supplierWhere + " order by s.code", args.toArray());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> supplier : rows) {
            long id = ((Number) supplier.get("id")).longValue();
            Map<String, Object> order = orders.get(String.valueOf(id));
            Map<String, Object> receipt = receipts.get(String.valueOf(id));
            Map<String, Object> qcRow = qc.get(String.valueOf(id));
            Map<String, Object> returnRow = returns.get(String.valueOf(id));

            BigDecimal amount = decimal(order == null ? null : order.get("amount"));
            int orderCount = order == null ? 0 : intOf(order.get("orders"));
            int batches = receipt == null ? 0 : intOf(receipt.get("batches"));
            int judged = receipt == null ? 0 : intOf(receipt.get("judged"));
            int onTime = receipt == null ? 0 : intOf(receipt.get("onTime"));
            BigDecimal inspectQty = decimal(qcRow == null ? null : qcRow.get("inspectQty"));
            BigDecimal defectQty = decimal(qcRow == null ? null : qcRow.get("defectQty"));
            int inspections = qcRow == null ? 0 : intOf(qcRow.get("inspections"));
            BigDecimal returnAmount = decimal(returnRow == null ? null : returnRow.get("returnAmount"));

            BigDecimal onTimeRate = rate(BigDecimal.valueOf(onTime), BigDecimal.valueOf(judged));
            BigDecimal passRate = rate(inspectQty.subtract(defectQty), inspectQty);
            BigDecimal returnRate = rate(returnAmount, amount);
            boolean hasData = orderCount > 0 || batches > 0 || inspections > 0;

            Map<String, Object> item = new LinkedHashMap<>(supplier);
            item.put("orders", orderCount);
            item.put("amount", amount);
            item.put("batches", batches);
            item.put("judgedBatches", judged);
            item.put("onTimeBatches", onTime);
            item.put("onTimeRate", onTimeRate);
            item.put("inspections", inspections);
            item.put("inspectQty", inspectQty);
            item.put("defectQty", defectQty);
            item.put("passRate", passRate);
            item.put("returnAmount", returnAmount);
            item.put("returnRate", returnRate);
            // 完全没有往来数据时不打分，显示「-」，避免空供应商被排到前面
            BigDecimal score = hasData ? score(onTimeRate, passRate, returnRate, judged > 0, inspections > 0) : null;
            item.put("score", score);
            item.put("grade", grade(score));
            item.put("hasData", hasData);
            result.add(item);
        }
        result.sort((a, b) -> decimal(b.get("amount")).compareTo(decimal(a.get("amount"))));
        return result;
    }

    /** 单个供应商的绩效明细：月度价格趋势、物料分布、准时情况与最近订单 */
    public Map<String, Object> detail(long supplierId, String from, String to) {
        List<Map<String, Object>> ranking = ranking(from, to, supplierId);
        if (ranking.isEmpty()) {
            throw new BizException(4001, "供应商不存在");
        }
        Date start = parseDate(from, Date.valueOf(java.time.LocalDate.now().minusDays(180)));
        Date end = parseDate(to, Date.valueOf(java.time.LocalDate.now()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", ranking.get(0));
        List<Map<String, Object>> priceTrend = jdbcTemplate.queryForList("""
                select to_char(po.order_date, 'YYYY-MM') as "month",
                       i.product_id as "productId",
                       round(sum(i.qty), 2) as "qty",
                       round(sum(i.amount) / nullif(sum(i.qty), 0), 4) as "avgPrice",
                       round(sum(i.amount), 2) as "amount"
                from purchase_order_item i
                join purchase_order po on po.id = i.order_id
                where po.supplier_id = ? and po.status <> 'CANCELLED'
                  and po.order_date between ? and ?
                group by to_char(po.order_date, 'YYYY-MM'), i.product_id
                order by to_char(po.order_date, 'YYYY-MM') desc, sum(i.amount) desc
                """, supplierId, start, end);
        MasterDataViews.fillProductNames(masterData, priceTrend);
        result.put("priceTrend", priceTrend);
        List<Map<String, Object>> materials = jdbcTemplate.queryForList("""
                select i.product_id as "productId",
                       round(sum(i.qty), 2) as "qty",
                       round(sum(i.amount), 2) as "amount",
                       round(sum(i.amount) / nullif(sum(i.qty), 0), 4) as "avgPrice",
                       round(min(i.price), 4) as "minPrice",
                       round(max(i.price), 4) as "maxPrice"
                from purchase_order_item i
                join purchase_order po on po.id = i.order_id
                where po.supplier_id = ? and po.status <> 'CANCELLED'
                  and po.order_date between ? and ?
                group by i.product_id
                order by sum(i.amount) desc
                """, supplierId, start, end);
        MasterDataViews.fillProductNames(masterData, materials);
        result.put("materials", materials);
        result.put("receipts", receiptRows(supplierId, start.toLocalDate(), end.toLocalDate()));
        result.put("inspections", inspectionRows(supplierId, start.toLocalDate(), end.toLocalDate()));
        result.put("from", String.valueOf(start));
        result.put("to", String.valueOf(end));
        return result;
    }

    /** 某个供应商的收货明细：入库单来自仓储契约，预计到货日来自采购单 */
    private List<Map<String, Object>> receiptRows(long supplierId, LocalDate from, LocalDate to) {
        List<InboundOrderPort.InboundOrderView> views = inboundOrderPort.completedInbounds(supplierId, from, to);
        Map<Long, Map<String, Object>> orders = ordersOf(views);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InboundOrderPort.InboundOrderView view : views) {
            Map<String, Object> order = orders.get(view.purchaseOrderId());
            LocalDate expected = order == null ? null : (LocalDate) order.get("expectedDate");
            LocalDate completed = view.completedDate();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("inboundNo", view.inboundNo());
            row.put("orderNo", order == null ? null : order.get("orderNo"));
            row.put("expectedDate", expected == null ? null : expected.toString());
            row.put("completedDate", completed == null ? null : completed.toString());
            row.put("onTime", expected == null || completed == null ? null : !completed.isAfter(expected));
            row.put("delayDays", expected == null || completed == null
                    ? null : ChronoUnit.DAYS.between(expected, completed));
            rows.add(row);
            if (rows.size() >= 50) {
                break;
            }
        }
        return rows;
    }

    /** 某个供应商的来料检验记录：检验行来自质检契约，供应商由入库单接上 */
    private List<Map<String, Object>> inspectionRows(long supplierId, LocalDate from, LocalDate to) {
        List<InspectionPort.IncomingInspection> incoming = inspectionPort.incoming(from, to);
        Map<Long, Long> supplierOfInbound = supplierOfInbound(incoming, List.of());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InspectionPort.IncomingInspection inspection : incoming) {
            Long owner = inspection.inboundOrderId() == null
                    ? null : supplierOfInbound.get(inspection.inboundOrderId());
            if (owner == null || owner != supplierId) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("inspectionNo", inspection.inspectionNo());
            row.put("productId", inspection.productId());
            row.put("inspectQty", inspection.inspectQty());
            row.put("defectQty", inspection.defectQty());
            row.put("result", inspection.result());
            row.put("inspectedAt", inspection.inspectedAt());
            rows.add(row);
            if (rows.size() >= 50) {
                break;
            }
        }
        MasterDataViews.fillProductNames(masterData, rows);
        return rows;
    }

    /** 入库单对应的采购单：单号与预计到货日都在本模块 */
    private Map<Long, Map<String, Object>> ordersOf(List<InboundOrderPort.InboundOrderView> views) {
        Map<Long, Map<String, Object>> map = new LinkedHashMap<>();
        List<Long> ids = views.stream()
                .map(InboundOrderPort.InboundOrderView::purchaseOrderId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, order_no, expected_date from purchase_order where id in (" + marks + ")",
                ids.toArray())) {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("orderNo", String.valueOf(row.get("order_no")));
            java.sql.Date expected = (java.sql.Date) row.get("expected_date");
            order.put("expectedDate", expected == null ? null : expected.toLocalDate());
            map.put(((Number) row.get("id")).longValue(), order);
        }
        return map;
    }

    /** 按入库单把检验记录挂到供应商身上；范围内没收货的入库单再补问一次 */
    private Map<Long, Long> supplierOfInbound(List<InspectionPort.IncomingInspection> incoming,
                                              List<InboundOrderPort.InboundOrderView> known) {
        Map<Long, Long> map = new LinkedHashMap<>();
        for (InboundOrderPort.InboundOrderView view : known) {
            if (view.supplierId() != null) {
                map.put(view.id(), view.supplierId());
            }
        }
        List<Long> missing = incoming.stream()
                .map(InspectionPort.IncomingInspection::inboundOrderId)
                .filter(java.util.Objects::nonNull)
                .filter(id -> !map.containsKey(id))
                .distinct()
                .toList();
        if (missing.isEmpty()) {
            return map;
        }
        for (Map.Entry<Long, InboundOrderPort.InboundOrderView> entry
                : inboundOrderPort.inbounds(missing).entrySet()) {
            if (entry.getValue().supplierId() != null) {
                map.put(entry.getKey(), entry.getValue().supplierId());
            }
        }
        return map;
    }

    /** 按供应商汇总收货批次：批次总数、可判断批次数、准时批次数 */
    private Map<String, Map<String, Object>> aggregateReceipts(List<InboundOrderPort.InboundOrderView> views,
                                                              Map<Long, Map<String, Object>> orders) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (InboundOrderPort.InboundOrderView view : views) {
            if (view.supplierId() == null) {
                continue;
            }
            Map<String, Object> row = map.computeIfAbsent(String.valueOf(view.supplierId()), key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("batches", 0);
                created.put("judged", 0);
                created.put("onTime", 0);
                return created;
            });
            row.put("batches", ((Number) row.get("batches")).intValue() + 1);
            Map<String, Object> order = orders.get(view.purchaseOrderId());
            LocalDate expected = order == null ? null : (LocalDate) order.get("expectedDate");
            if (expected == null || view.completedDate() == null) {
                continue;
            }
            row.put("judged", ((Number) row.get("judged")).intValue() + 1);
            if (!view.completedDate().isAfter(expected)) {
                row.put("onTime", ((Number) row.get("onTime")).intValue() + 1);
            }
        }
        return map;
    }

    /** 按供应商汇总来料检验：检验单数、检验数量、不良数量 */
    private Map<String, Map<String, Object>> aggregateInspections(List<InspectionPort.IncomingInspection> incoming,
                                                                  Map<Long, Long> supplierOfInbound) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (InspectionPort.IncomingInspection inspection : incoming) {
            Long supplier = inspection.inboundOrderId() == null
                    ? null : supplierOfInbound.get(inspection.inboundOrderId());
            if (supplier == null) {
                continue;
            }
            Map<String, Object> row = map.computeIfAbsent(String.valueOf(supplier), key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("inspections", 0);
                created.put("inspectQty", BigDecimal.ZERO);
                created.put("defectQty", BigDecimal.ZERO);
                return created;
            });
            row.put("inspections", ((Number) row.get("inspections")).intValue() + 1);
            row.put("inspectQty", ((BigDecimal) row.get("inspectQty")).add(inspection.inspectQty()));
            row.put("defectQty", ((BigDecimal) row.get("defectQty")).add(inspection.defectQty()));
        }
        return map;
    }

    private Map<String, Map<String, Object>> selectPairs(String sql, Date start, Date end) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler handler = rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                String label = rs.getMetaData().getColumnLabel(i);
                row.put(label, rs.getObject(i));
            }
            map.put(String.valueOf(row.get("supplier_id")), row);
        };
        jdbcTemplate.query(sql, handler, start, end);
        return map;
    }

    /** 综合得分：缺数据的项按 0 分权重剔除，避免「没检验过」被算成满分 */
    private BigDecimal score(BigDecimal onTimeRate, BigDecimal passRate, BigDecimal returnRate,
                             boolean hasReceipt, boolean hasInspection) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        if (hasReceipt) {
            total = total.add(onTimeRate.multiply(W_ON_TIME));
            weight = weight.add(W_ON_TIME);
        }
        if (hasInspection) {
            total = total.add(passRate.multiply(W_QUALITY));
            weight = weight.add(W_QUALITY);
        }
        BigDecimal returnScore = BigDecimal.ONE.subtract(returnRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO)
                .multiply(new BigDecimal("100"));
        total = total.add(returnScore.multiply(W_RETURN));
        weight = weight.add(W_RETURN);
        if (weight.signum() == 0) {
            return null;
        }
        // 各项都是百分数，按权重加权后再除以权重和，结果仍是 0~100
        return total.divide(weight, 2, RoundingMode.HALF_UP);
    }

    private String grade(BigDecimal score) {
        if (score == null) {
            return "-";
        }
        double value = score.doubleValue();
        if (value >= 90) {
            return "A";
        }
        if (value >= 80) {
            return "B";
        }
        if (value >= 70) {
            return "C";
        }
        return "D";
    }

    /** 比率统一成百分数，分母为 0 时给 0 */
    private BigDecimal rate(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private Date parseDate(String value, Date fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Date.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(1002, "日期格式应为 yyyy-MM-dd：" + value);
        }
    }
}
