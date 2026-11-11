package com.mes.quality;

import com.mes.common.BizException;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 过程能力与 SPC：
 * - 计量型（有上下限的检验项）给 X̄ 控制图与 Cpk；
 * - 计数型（有检验数/不良数）给不良率 P 控制图，控制限随样本量变化。
 *
 * 口径说明：
 * - 实测值取 inspection_item_result.measured 里能转成数字的部分，空值与非数字跳过；
 * - 标准差用样本标准差（n-1），Cpk = min(USL−均值, 均值−LSL) ÷ (3σ)；
 * - 只有单侧限时按单侧算 Cpk，没有任何限值时只给均值与 σ，不给 Cpk；
 * - P 图中心线用区间内合计不良率，控制限 p̄ ± 3√(p̄(1−p̄)/n_i)，下限裁到 0。
 */
@Service
public class SpcService {

    /** 过程能力经验阈值：1.33 一般视为可接受，1.0 以下说明过程能力不足 */
    private static final double CPK_WARN = 1.33;
    private static final double CPK_BAD = 1.0;
    private static final int MAX_POINTS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final MasterDataPort masterData;

    public SpcService(JdbcTemplate jdbcTemplate, MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.masterData = masterData;
    }

    /** 可做 SPC 的检验项：有上下限的计量型优先，其次是有抽样数据的计数型 */
    public List<Map<String, Object>> items(Long productId) {
        StringBuilder where = new StringBuilder(" where i.enabled ");
        List<Object> args = new ArrayList<>();
        if (productId != null) {
            where.append(" and i.product_id = ? ");
            args.add(productId);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.sort_no as "sortNo",
                       i.item_code as "itemCode", i.item_name as "itemName", i.standard,
                       i.method, i.value_type as "valueType", i.lower_limit as "lowerLimit",
                       i.upper_limit as "upperLimit", i.unit,
                       (select count(*) from inspection_item_result r where r.item_id = i.id) as "resultCount"
                from inspection_item i
                """ + where, args.toArray());
        MasterDataViews.fillProductNames(masterData, rows);
        // 原来按 p.code 排序，join 去掉后改到内存里排（空编码排最后）
        rows.sort(Comparator.comparing((Map<String, Object> row) -> (String) row.get("productCode"),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(row -> row.get("sortNo") == null
                        ? Integer.MAX_VALUE : ((Number) row.get("sortNo")).intValue())
                .thenComparingLong(row -> ((Number) row.get("id")).longValue()));
        return rows;
    }

    /**
     * 计量型控制图：逐张检验单一个点，给均值线与 ±3σ 控制限，并算 Cpk。
     */
    public Map<String, Object> chart(Long itemId, Long productId, String from, String to) {
        Date start = parseDate(from, Date.valueOf(LocalDate.now().minusDays(30)));
        Date end = parseDate(to, Date.valueOf(LocalDate.now()));
        if (start.after(end)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (itemId != null) {
            where.append(" and r.item_id = ? ");
            args.add(itemId);
        }
        if (productId != null) {
            where.append(" and ins.product_id = ? ");
            args.add(productId);
        }
        args.add(start);
        args.add(end);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select r.item_id as "itemId", r.item_name as "itemName", r.measured,
                       ins.inspection_no as "inspectionNo",
                       to_char(coalesce(ins.inspected_at, ins.created_at), 'MM-DD HH24:MI') as "label"
                from inspection_item_result r
                join inspection ins on ins.id = r.inspection_id
                """ + where + " and ins.inspected_at::date between ? and ? order by ins.id, r.id", args.toArray());

        List<Map<String, Object>> points = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        String itemName = null;
        Long resolvedItemId = itemId;
        for (Map<String, Object> row : rows) {
            Double value = parseNumber(row.get("measured"));
            if (value == null) {
                continue;
            }
            if (itemName == null) {
                itemName = String.valueOf(row.get("itemName"));
            }
            if (resolvedItemId == null && row.get("itemId") != null) {
                resolvedItemId = ((Number) row.get("itemId")).longValue();
            }
            values.add(value);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", row.get("label"));
            point.put("inspectionNo", row.get("inspectionNo"));
            point.put("value", round(value, 4));
            points.add(point);
        }
        if (points.size() > MAX_POINTS) {
            points = new ArrayList<>(points.subList(points.size() - MAX_POINTS, points.size()));
            values = new ArrayList<>(values.subList(values.size() - MAX_POINTS, values.size()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", resolvedItemId);
        result.put("itemName", itemName == null ? "未指定检验项" : itemName);
        result.put("from", String.valueOf(start));
        result.put("to", String.valueOf(end));
        result.put("points", points);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", values.size());
        if (values.size() >= 2) {
            double mean = mean(values);
            double sigma = stdev(values);
            stats.put("mean", round(mean, 4));
            stats.put("sigma", round(sigma, 4));
            stats.put("ucl", round(mean + 3 * sigma, 4));
            stats.put("lcl", round(mean - 3 * sigma, 4));
            stats.put("max", round(values.stream().mapToDouble(Double::doubleValue).max().orElse(0), 4));
            stats.put("min", round(values.stream().mapToDouble(Double::doubleValue).min().orElse(0), 4));
            // 越限点：超出 ±3σ 的点，按行标出来
            int breaches = 0;
            for (int i = 0; i < points.size(); i++) {
                Map<String, Object> point = points.get(i);
                double value = values.get(i);
                boolean breach = value > mean + 3 * sigma || value < mean - 3 * sigma;
                point.put("breach", breach);
                if (breach) {
                    breaches++;
                }
            }
            stats.put("breaches", breaches);
        }

        if (resolvedItemId != null) {
            List<Map<String, Object>> itemRows = jdbcTemplate.queryForList("""
                    select item_name as "itemName", standard, unit, lower_limit as "lowerLimit",
                           upper_limit as "upperLimit", value_type as "valueType"
                    from inspection_item where id = ?
                    """, resolvedItemId);
            if (!itemRows.isEmpty()) {
                Map<String, Object> item = itemRows.get(0);
                result.put("itemName", item.get("itemName"));
                result.put("standard", item.get("standard"));
                result.put("unit", item.get("unit"));
                double lower = item.get("lowerLimit") == null ? Double.NaN
                        : ((Number) item.get("lowerLimit")).doubleValue();
                double upper = item.get("upperLimit") == null ? Double.NaN
                        : ((Number) item.get("upperLimit")).doubleValue();
                stats.put("lowerLimit", Double.isNaN(lower) ? null : round(lower, 4));
                stats.put("upperLimit", Double.isNaN(upper) ? null : round(upper, 4));
                if (values.size() >= 2) {
                    Map<String, Object> capability = capability(values, lower, upper);
                    stats.putAll(capability);
                }
            }
        }
        result.put("stats", stats);
        return result;
    }

    /** 不良率 P 控制图：按检验单逐点，控制限随样本量变化 */
    public Map<String, Object> pChart(Long productId, String from, String to) {
        Date start = parseDate(from, Date.valueOf(LocalDate.now().minusDays(30)));
        Date end = parseDate(to, Date.valueOf(LocalDate.now()));
        if (start.after(end)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        StringBuilder where = new StringBuilder(" where ins.inspected_at::date between ? and ? ");
        List<Object> args = new ArrayList<>();
        args.add(start);
        args.add(end);
        if (productId != null) {
            where.append(" and ins.product_id = ? ");
            args.add(productId);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select ins.inspection_no as "inspectionNo", ins.type,
                       ins.product_id as "productId",
                       ins.inspect_qty as "inspectQty", ins.defect_qty as "defectQty",
                       to_char(ins.inspected_at, 'MM-DD HH24:MI') as "label"
                from inspection ins
                """ + where + " order by ins.id", args.toArray());
        MasterDataViews.fillProductNames(masterData, rows);
        if (rows.size() > MAX_POINTS) {
            rows = new ArrayList<>(rows.subList(rows.size() - MAX_POINTS, rows.size()));
        }

        BigDecimal totalInspect = BigDecimal.ZERO;
        BigDecimal totalDefect = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            totalInspect = totalInspect.add(decimal(row.get("inspectQty")));
            totalDefect = totalDefect.add(decimal(row.get("defectQty")));
        }
        double pbar = totalInspect.signum() == 0 ? 0
                : totalDefect.doubleValue() / totalInspect.doubleValue();

        List<Map<String, Object>> points = new ArrayList<>();
        int breaches = 0;
        for (Map<String, Object> row : rows) {
            double n = decimal(row.get("inspectQty")).doubleValue();
            double defect = decimal(row.get("defectQty")).doubleValue();
            double p = n <= 0 ? 0 : defect / n;
            double sigma = n <= 0 ? 0 : Math.sqrt(pbar * (1 - pbar) / n);
            double ucl = Math.min(1, pbar + 3 * sigma);
            double lcl = Math.max(0, pbar - 3 * sigma);
            boolean breach = p > ucl + 1e-9 || p < lcl - 1e-9;
            if (breach) {
                breaches++;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", row.get("label"));
            point.put("inspectionNo", row.get("inspectionNo"));
            point.put("type", row.get("type"));
            point.put("productCode", row.get("productCode"));
            point.put("sampleQty", round(n, 2));
            point.put("defectQty", round(defect, 2));
            point.put("rate", round(p * 100, 2));
            point.put("center", round(pbar * 100, 2));
            point.put("ucl", round(ucl * 100, 2));
            point.put("lcl", round(lcl * 100, 2));
            point.put("breach", breach);
            points.add(point);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", rows.size());
        stats.put("inspectQty", totalInspect);
        stats.put("defectQty", totalDefect);
        stats.put("centerRate", round(pbar * 100, 2));
        stats.put("breaches", breaches);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", String.valueOf(start));
        result.put("to", String.valueOf(end));
        result.put("points", points);
        result.put("stats", stats);
        return result;
    }

    /** 过程能力汇总：按检验项算 Cpk，列出能力不足的项目 */
    public List<Map<String, Object>> capability(Long productId) {
        StringBuilder where = new StringBuilder(" where i.enabled and (i.lower_limit is not null or i.upper_limit is not null) ");
        List<Object> args = new ArrayList<>();
        if (productId != null) {
            where.append(" and i.product_id = ? ");
            args.add(productId);
        }
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.sort_no as "sortNo",
                       i.item_name as "itemName", i.item_code as "itemCode", i.standard, i.unit,
                       i.lower_limit as "lowerLimit", i.upper_limit as "upperLimit"
                from inspection_item i
                """ + where, args.toArray());
        MasterDataViews.fillProductNames(masterData, items);
        // 原来按 p.code 排序，join 去掉后改到内存里排
        items.sort(Comparator.comparing((Map<String, Object> row) -> (String) row.get("productCode"),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(row -> row.get("sortNo") == null
                        ? Integer.MAX_VALUE : ((Number) row.get("sortNo")).intValue()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            long itemId = ((Number) item.get("id")).longValue();
            List<String> measured = jdbcTemplate.queryForList("""
                    select measured from inspection_item_result
                    where item_id = ? and measured is not null order by id desc limit 200
                    """, String.class, itemId);
            List<Double> values = new ArrayList<>();
            for (String text : measured) {
                Double value = parseNumber(text);
                if (value != null) {
                    values.add(value);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("sampleCount", values.size());
            if (values.size() >= 2) {
                double mean = mean(values);
                double sigma = stdev(values);
                double lower = item.get("lowerLimit") == null ? Double.NaN
                        : ((Number) item.get("lowerLimit")).doubleValue();
                double upper = item.get("upperLimit") == null ? Double.NaN
                        : ((Number) item.get("upperLimit")).doubleValue();
                row.put("mean", round(mean, 4));
                row.put("sigma", round(sigma, 4));
                Map<String, Object> capability = capability(values, lower, upper);
                row.putAll(capability);
                Double cpk = (Double) capability.get("cpk");
                row.put("level", cpk == null ? "数据不足" : cpk >= CPK_WARN ? "充足"
                        : cpk >= CPK_BAD ? "偏低" : "不足");
                row.put("alert", cpk != null && cpk < CPK_WARN);
            } else {
                row.put("level", "数据不足");
                row.put("alert", false);
            }
            result.add(row);
        }
        result.sort((a, b) -> {
            Double left = a.get("cpk") == null ? 999d : ((Number) a.get("cpk")).doubleValue();
            Double right = b.get("cpk") == null ? 999d : ((Number) b.get("cpk")).doubleValue();
            return Double.compare(left, right);
        });
        return result;
    }

    /** 均值、σ、Cp、Cpk、越限数；只有单侧限时按单侧算 */
    private Map<String, Object> capability(List<Double> values, double lower, double upper) {
        Map<String, Object> result = new LinkedHashMap<>();
        double mean = mean(values);
        double sigma = stdev(values);
        if (sigma <= 0) {
            result.put("cp", null);
            result.put("cpk", null);
            result.put("outOfSpec", 0);
            result.put("note", "实测值没有波动，无法计算过程能力");
            return result;
        }
        Double cp = null;
        Double cpk = null;
        if (!Double.isNaN(lower) && !Double.isNaN(upper)) {
            cp = (upper - lower) / (6 * sigma);
            cpk = Math.min(upper - mean, mean - lower) / (3 * sigma);
        } else if (!Double.isNaN(upper)) {
            cpk = (upper - mean) / (3 * sigma);
        } else if (!Double.isNaN(lower)) {
            cpk = (mean - lower) / (3 * sigma);
        }
        long outOfSpec = values.stream()
                .filter(value -> (!Double.isNaN(upper) && value > upper)
                        || (!Double.isNaN(lower) && value < lower))
                .count();
        result.put("cp", cp == null ? null : round(cp, 2));
        result.put("cpk", cpk == null ? null : round(cpk, 2));
        result.put("outOfSpec", outOfSpec);
        return result;
    }

    private Double parseNumber(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** 样本标准差（n-1），n < 2 返回 0 */
    private double stdev(List<Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = mean(values);
        double sum = 0;
        for (double value : values) {
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    private double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
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
