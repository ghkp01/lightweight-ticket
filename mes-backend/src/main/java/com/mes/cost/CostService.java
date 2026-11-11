package com.mes.cost;

import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.cost.api.CostPort;
import com.mes.cost.api.WorkOrderCost;
import com.mes.inventory.api.InventoryPort;
import com.mes.platform.config.ConfigService;
import com.mes.production.api.WorkOrderCostPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单成本核算：料（领料流水金额）+ 工（报工数量 × 工序单价）+ 费（人工 × 配置比例）。
 * 成本在完工时结算成快照，也可以随时重算；库存侧的成本由 InventoryService 的移动加权负责。
 */
@Service
public class CostService implements CostPort {

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final InventoryPort inventory;
    private final WorkOrderCostPort workOrderCost;

    public CostService(JdbcTemplate jdbcTemplate, ConfigService configService, InventoryPort inventory,
                       WorkOrderCostPort workOrderCost) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.inventory = inventory;
        this.workOrderCost = workOrderCost;
    }

    /** 制造费用比例(%)，按人工成本摊 */
    public BigDecimal overheadRate() {
        String value = configService.stringValue("manufacturing.overhead.rate", "10");
        try {
            BigDecimal rate = new BigDecimal(value.trim());
            return rate.signum() < 0 ? BigDecimal.ZERO : rate;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 结算单张工单成本并写入快照。
     * 材料成本 = 领料流水金额合计取负（退料回流为正，自动抵减）；
     * 人工成本 = 报工数量 × 工序单价（不良品同样消耗工时，按报工量计）；
     * 单位成本 = 总成本 ÷ 合格数量。
     */
    @Override
    @Transactional
    public WorkOrderCost recalc(long workOrderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, work_no, product_id, status, plan_qty, qualified_qty
                from rm_work_order where id = ?
                """, workOrderId);
        if (rows.isEmpty()) {
            throw new BizException(2010, "生产工单不存在");
        }
        Map<String, Object> work = rows.get(0);
        String workNo = String.valueOf(work.get("work_no"));

        BigDecimal material = materialCost(workNo);
        BigDecimal labor = laborCost(workOrderId);
        BigDecimal overhead = labor.multiply(overheadRate())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        // 外协费单列：外协干的活不写报工，人工里本来就没有它，再加一笔会重复
        BigDecimal outsource = outsourceCost(workOrderId);
        BigDecimal total = material.add(labor).add(overhead).add(outsource);
        BigDecimal qualified = decimal(work.get("qualified_qty"));
        BigDecimal unit = qualified.signum() > 0
                ? total.divide(qualified, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 成本字段属于工单，写入规则（递 version、动 updated_at）也在工单侧，这里发命令就好
        workOrderCost.writeCost(workOrderId, material, labor, overhead, outsource, total, unit);

        return new WorkOrderCost(workOrderId, workNo, qualified,
                material, labor, overhead, outsource, total, unit);
    }

    /** 批量重算：status 为空时只补算没有成本快照的工单 */
    @Override
    @Transactional
    public int recalcAll(String status) {
        String sql = status == null || status.isBlank()
                ? "select id from rm_work_order where costed_at is null order by id"
                : "select id from rm_work_order where status = ? order by id";
        List<Map<String, Object>> rows = status == null || status.isBlank()
                ? jdbcTemplate.queryForList(sql)
                : jdbcTemplate.queryForList(sql, status);
        for (Map<String, Object> row : rows) {
            recalc(((Number) row.get("id")).longValue());
        }
        return rows.size();
    }

    /** 成本报表：工单维度，含单位成本与毛利率 */
    public PageResult workOrders(String keyword, String status, String from, String to, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (w.work_no ilike ? or p.code ilike ? or p.name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and w.status = ? ");
            args.add(status);
        }
        Date start = parseDate(from);
        Date end = parseDate(to);
        if (start != null) {
            where.append(" and w.created_at::date >= ? ");
            args.add(start);
        }
        if (end != null) {
            where.append(" and w.created_at::date <= ? ");
            args.add(end);
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from rm_work_order w join rm_product p on p.id = w.product_id
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select w.id, w.work_no as "workNo", p.code as "productCode", p.name as "productName",
                       p.unit, p.price, w.status, w.plan_qty as "planQty",
                       w.qualified_qty as "qualifiedQty",
                       w.material_cost as "materialCost", w.labor_cost as "laborCost",
                       w.overhead_cost as "overheadCost", w.outsource_cost as "outsourceCost",
                       w.total_cost as "totalCost",
                       w.unit_cost as "unitCost",
                       case when w.qualified_qty > 0 then round(w.total_cost / w.qualified_qty, 2) end
                           as "unitCostExact",
                       case when p.price > 0 and w.unit_cost > 0
                            then round((p.price - w.unit_cost) / p.price * 100, 2) end as "grossMargin",
                       to_char(w.costed_at, 'YYYY-MM-DD HH24:MI') as "costedAt",
                       to_char(w.created_at, 'YYYY-MM-DD') as "createdDate"
                from rm_work_order w
                join rm_product p on p.id = w.product_id
                """ + where + " order by w.id desc limit ? offset ?", pageArgs.toArray());
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    /** 工单成本构成：材料按物料汇总，人工按工序汇总 */
    public Map<String, Object> detail(long workOrderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, work_no as "workNo", product_id as "productId", status,
                       plan_qty as "planQty", qualified_qty as "qualifiedQty",
                       material_cost as "materialCost", labor_cost as "laborCost",
                       overhead_cost as "overheadCost", outsource_cost as "outsourceCost",
                       total_cost as "totalCost",
                       unit_cost as "unitCost",
                       to_char(costed_at, 'YYYY-MM-DD HH24:MI') as "costedAt"
                from rm_work_order where id = ?
                """, workOrderId);
        if (rows.isEmpty()) {
            throw new BizException(2010, "生产工单不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        String workNo = String.valueOf(detail.get("workNo"));
        detail.put("materials", materialLines(workNo));
        detail.put("labors", laborLines(workOrderId));
        detail.put("outsources", outsourceLines(workOrderId));
        detail.put("overheadRate", overheadRate());
        return detail;
    }

    /** 产品库存成本：移动平均成本与档案成本的差异，方便发现成本不准的物料 */
    public List<Map<String, Object>> products(String keyword) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select p.id, p.code, p.name, p.spec, p.unit, p.category,
                       p.cost, p.avg_cost as "avgCost", p.price,
                       round(p.avg_cost - p.cost, 4) as "costDiff",
                       case when p.cost > 0
                            then round((p.avg_cost - p.cost) / p.cost * 100, 2) end as "costDiffRate"
                from rm_product p
                where p.enabled = true and (p.code ilike ? or p.name ilike ?)
                order by p.code
                """, like, like);
        // 结存与金额走库存契约：结存是本模块要的量，金额口径（移动平均 × 结存）留在本模块算
        Map<Long, BigDecimal> stocks = inventory.totalStocks(rows.stream()
                .map(row -> ((Number) row.get("id")).longValue()).toList());
        for (Map<String, Object> row : rows) {
            BigDecimal qty = stocks.getOrDefault(((Number) row.get("id")).longValue(), BigDecimal.ZERO);
            Object avgCost = row.get("avgCost");
            BigDecimal cost = avgCost == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(avgCost));
            row.put("stockQty", qty);
            row.put("stockAmount", qty.multiply(cost).setScale(2, RoundingMode.HALF_UP));
        }
        return rows;
    }

    /** 成本概览：区间内完工工单的成本合计与构成占比 */
    public Map<String, Object> summary(String from, String to) {
        Date start = parseDate(from);
        Date end = parseDate(to);
        StringBuilder where = new StringBuilder(" where w.costed_at is not null ");
        List<Object> args = new ArrayList<>();
        if (start != null) {
            where.append(" and w.costed_at::date >= ? ");
            args.add(start);
        }
        if (end != null) {
            where.append(" and w.costed_at::date <= ? ");
            args.add(end);
        }
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select count(*) as "workOrders",
                       coalesce(sum(w.total_cost), 0) as "totalCost",
                       coalesce(sum(w.material_cost), 0) as "materialCost",
                       coalesce(sum(w.labor_cost), 0) as "laborCost",
                       coalesce(sum(w.overhead_cost), 0) as "overheadCost",
                       coalesce(sum(w.outsource_cost), 0) as "outsourceCost",
                       coalesce(sum(w.qualified_qty), 0) as "qualifiedQty"
                from rm_work_order w
                """ + where, args.toArray());
        BigDecimal totalCost = decimal(row.get("totalCost"));
        BigDecimal qualified = decimal(row.get("qualifiedQty"));
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("avgUnitCost", qualified.signum() > 0
                ? totalCost.divide(qualified, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        result.put("materialRate", percent(decimal(row.get("materialCost")), totalCost));
        result.put("laborRate", percent(decimal(row.get("laborCost")), totalCost));
        result.put("overheadRate", percent(decimal(row.get("overheadCost")), totalCost));
        result.put("outsourceRate", percent(decimal(row.get("outsourceCost")), totalCost));
        result.put("overheadRatio", overheadRate());
        return result;
    }

    private BigDecimal materialCost(String workNo) {
        BigDecimal amount = jdbcTemplate.queryForObject("""
                select coalesce(sum(amount), 0) from rm_stock_txn
                where ref_no = ?
                  and ((ref_type = 'WORK_ORDER' and txn_type = 'ISSUE')
                       or (ref_type = 'WORK_ORDER_RETURN' and txn_type = 'RETURN_MAT'))
                """, BigDecimal.class, workNo);
        return amount == null ? BigDecimal.ZERO : amount.negate();
    }

    private BigDecimal laborCost(long workOrderId) {
        BigDecimal amount = jdbcTemplate.queryForObject("""
                select coalesce(sum(r.qty * o.unit_price), 0)
                from work_report r
                join rm_work_order_operation o on o.id = r.operation_id
                where r.work_order_id = ?
                """, BigDecimal.class, workOrderId);
        return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** 外协费：外协单上按「收回合格数 × 单价」累出来的金额，未收回的部分不计 */
    private BigDecimal outsourceCost(long workOrderId) {
        BigDecimal amount = jdbcTemplate.queryForObject("""
                select coalesce(sum(amount), 0) from rm_outsource_order where work_order_id = ?
                """, BigDecimal.class, workOrderId);
        return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** 外协明细：哪道工序、给谁做、发了多少料、多少钱 */
    private List<Map<String, Object>> outsourceLines(long workOrderId) {
        return jdbcTemplate.queryForList("""
                select o.order_no as "orderNo", op.seq as "operationSeq", op.name as "operationName",
                       s.name as "supplierName", o.qty, o.received_qty as "receivedQty",
                       o.defect_qty as "defectQty", o.price, o.amount, o.status,
                       (select coalesce(sum(i.qty), 0) from rm_outsource_order_item i
                        where i.order_id = o.id) as "materialQty"
                from rm_outsource_order o
                join rm_work_order_operation op on op.id = o.operation_id
                left join rm_supplier s on s.id = o.supplier_id
                where o.work_order_id = ?
                order by o.id
                """, workOrderId);
    }

    private List<Map<String, Object>> materialLines(String workNo) {
        return jdbcTemplate.queryForList("""
                select p.code as "productCode", p.name as "productName", p.unit,
                       sum(case when t.txn_type = 'ISSUE' then -t.qty else t.qty end) as "qty",
                       sum(-t.amount) as "amount"
                from rm_stock_txn t
                join rm_product p on p.id = t.product_id
                where t.ref_no = ?
                  and ((t.ref_type = 'WORK_ORDER' and t.txn_type = 'ISSUE')
                       or (t.ref_type = 'WORK_ORDER_RETURN' and t.txn_type = 'RETURN_MAT')
                       -- 外协发出的料也是这单的料，不算进来材料成本就少一块
                       or t.ref_type = 'OUTSOURCE')
                group by p.code, p.name, p.unit
                order by p.code
                """, workNo);
    }

    private List<Map<String, Object>> laborLines(long workOrderId) {
        return jdbcTemplate.queryForList("""
                select o.seq, o.name as "processName", o.unit_price as "unitPrice",
                       coalesce(sum(r.qty), 0) as "qty",
                       coalesce(sum(r.qualified_qty), 0) as "qualifiedQty",
                       round(coalesce(sum(r.qty * o.unit_price), 0), 2) as "amount"
                from rm_work_order_operation o
                left join work_report r on r.operation_id = o.id
                where o.work_order_id = ?
                group by o.seq, o.name, o.unit_price
                order by o.seq
                """, workOrderId);
    }

    private BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private Date parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(1002, "日期格式不正确，应为 yyyy-MM-dd：" + value);
        }
    }
}
