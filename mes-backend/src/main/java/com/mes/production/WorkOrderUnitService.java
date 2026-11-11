package com.mes.production;

import com.mes.common.BizException;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单件序列号（SN）管理。
 *
 * 产品启用「单件管理」后，工单下达时按计划数量生成件码（工单号-序号），
 * 车间扫件码开工 / 完工，每件的当前工序、经手人、时间都留痕。
 * 完工时同步累加该工序完成数并写报工流水，保证工序进度、看板、报表口径一致。
 */
@Service
public class WorkOrderUnitService {

    private final JdbcTemplate jdbcTemplate;
    private final com.mes.cost.api.CostPort costService;

    public WorkOrderUnitService(JdbcTemplate jdbcTemplate, com.mes.cost.api.CostPort costService) {
        this.jdbcTemplate = jdbcTemplate;
        this.costService = costService;
    }

    public record FinishRequest(String worker, Boolean qualified, String remark) {
    }

    // ---------------------------------------------------------------- 生成与查询

    /** 工单下达时生成件码；已有件码时不重复生成 */
    @Transactional
    public int generateForWorkOrder(long workOrderId) {
        // 读工单上的追溯方式快照，不再实时读产品开关：产品改动不该让在制工单突然要/不要件码
        Map<String, Object> work = jdbcTemplate.queryForMap("""
                select id, work_no, plan_qty, status, trace_mode from work_order where id = ?
                """, workOrderId);
        if (!"SERIAL".equals(String.valueOf(work.get("trace_mode")))) {
            return 0;
        }
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from work_order_unit where work_order_id = ?", Integer.class, workOrderId);
        if (existing != null && existing > 0) {
            return existing;
        }
        BigDecimal plan = new BigDecimal(String.valueOf(work.get("plan_qty")));
        if (plan.stripTrailingZeros().scale() > 0) {
            throw new BizException(1002, "启用单件管理的产品，计划数量必须是整数");
        }
        int count = plan.intValueExact();
        if (count <= 0) {
            return 0;
        }
        String workNo = String.valueOf(work.get("work_no"));
        List<Object[]> args = new ArrayList<>();
        for (int seq = 1; seq <= count; seq++) {
            args.add(new Object[]{workOrderId, workNo + "-" + String.format("%03d", seq), seq});
        }
        jdbcTemplate.batchUpdate("""
                insert into work_order_unit (work_order_id, unit_no, seq_no)
                values (?, ?, ?)
                on conflict (work_order_id, seq_no) do nothing
                """, args);
        return count;
    }

    /** 工单维度的件列表、位置分布与汇总 */
    public Map<String, Object> listByWorkOrder(long workOrderId) {
        List<Map<String, Object>> units = jdbcTemplate.queryForList("""
                select u.id, u.unit_no as "unitNo", u.seq_no as "seqNo", u.status,
                       u.current_operation_seq as "currentOperationSeq",
                       o.name as "currentOperationName",
                       u.last_action_at as "lastActionAt",
                       to_char(u.last_action_at, 'MM-DD HH24:MI') as "lastActionText",
                       (select l.worker from work_order_unit_log l
                        where l.unit_id = u.id order by l.id desc limit 1) as "lastWorker"
                from work_order_unit u
                left join work_order_operation o on o.id = u.current_operation_id
                where u.work_order_id = ?
                order by u.seq_no
                """, workOrderId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", units.size());
        summary.put("pending", units.stream().filter(u -> "PENDING".equals(u.get("status"))).count());
        summary.put("inProgress", units.stream().filter(u -> "IN_PROGRESS".equals(u.get("status"))).count());
        summary.put("finished", units.stream().filter(u -> "FINISHED".equals(u.get("status"))).count());
        summary.put("scrapped", units.stream().filter(u -> "SCRAPPED".equals(u.get("status"))).count());

        // 位置分布：按当前工序聚合，已完工 / 已报废单独归堆
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> unit : units) {
            String key;
            String label;
            if ("FINISHED".equals(unit.get("status"))) {
                key = "done";
                label = "已完工";
            } else if ("SCRAPPED".equals(unit.get("status"))) {
                key = "scrapped";
                label = "已报废";
            } else if (unit.get("currentOperationSeq") == null) {
                key = "waiting";
                label = "待投产";
            } else {
                key = "op-" + unit.get("currentOperationSeq");
                label = String.valueOf(unit.get("currentOperationName"));
            }
            Map<String, Object> bucket = grouped.computeIfAbsent(key, k -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", k);
                item.put("label", label);
                item.put("count", 0);
                item.put("units", new ArrayList<String>());
                return item;
            });
            bucket.put("count", ((Number) bucket.get("count")).intValue() + 1);
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) bucket.get("units");
            list.add(String.valueOf(unit.get("unitNo")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("units", units);
        result.put("summary", summary);
        result.put("distribution", new ArrayList<>(grouped.values()));
        return result;
    }

    /** 跨工单的件列表：按件码 / 工单号 / 产品搜，按状态与当前工序筛，车间和计划都用这一页找件 */
    public Map<String, Object> search(String keyword, String status, Integer operationSeq, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder where = new StringBuilder("""
                 where (u.unit_no ilike ? or w.work_no ilike ? or p.name ilike ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(like);
        args.add(like);
        args.add(like);
        if (operationSeq != null) {
            where.append(" and u.current_operation_seq = ? ");
            args.add(operationSeq);
        }

        // 状态计数按「关键词 + 工序」的全量算，不受状态筛选影响，便于在页签上看到各状态有多少件
        List<Map<String, Object>> statusRows = jdbcTemplate.queryForList("""
                select u.status, count(*) as cnt
                from work_order_unit u
                join work_order w on w.id = u.work_order_id
                join rm_product p on p.id = w.product_id
                """ + where + " group by u.status", args.toArray());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", 0);
        summary.put("pending", 0L);
        summary.put("inProgress", 0L);
        summary.put("finished", 0L);
        summary.put("scrapped", 0L);
        long all = 0;
        for (Map<String, Object> row : statusRows) {
            long cnt = ((Number) row.get("cnt")).longValue();
            all += cnt;
            switch (String.valueOf(row.get("status"))) {
                case "PENDING" -> summary.put("pending", cnt);
                case "IN_PROGRESS" -> summary.put("inProgress", cnt);
                case "FINISHED" -> summary.put("finished", cnt);
                case "SCRAPPED" -> summary.put("scrapped", cnt);
                default -> {
                }
            }
        }
        summary.put("total", all);

        StringBuilder pageWhere = new StringBuilder(where);
        List<Object> pageArgs = new ArrayList<>(args);
        if (status != null && !status.isBlank()) {
            pageWhere.append(" and u.status = ? ");
            pageArgs.add(status);
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from work_order_unit u
                join work_order w on w.id = u.work_order_id
                join rm_product p on p.id = w.product_id
                """ + pageWhere, Long.class, pageArgs.toArray());

        List<Object> listArgs = new ArrayList<>(pageArgs);
        listArgs.add(size);
        listArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select u.id, u.unit_no as "unitNo", u.seq_no as "seqNo", u.status,
                       u.current_operation_seq as "currentOperationSeq",
                       o.name as "currentOperationName",
                       u.work_order_id as "workOrderId", w.work_no as "workNo", w.status as "workOrderStatus",
                       p.code as "productCode", p.name as "productName",
                       to_char(u.last_action_at, 'MM-DD HH24:MI') as "lastActionText",
                       (select l.worker from work_order_unit_log l
                        where l.unit_id = u.id order by l.id desc limit 1) as "lastWorker"
                from work_order_unit u
                join work_order w on w.id = u.work_order_id
                join rm_product p on p.id = w.product_id
                left join work_order_operation o on o.id = u.current_operation_id
                """ + pageWhere + """
                 order by u.last_action_at desc nulls last, u.id desc
                 limit ? offset ?
                """, listArgs.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("size", size);
        result.put("summary", summary);
        return result;
    }

    /** 件当前所在工序的下拉选项，按工序序号去重 */
    public List<Map<String, Object>> operationOptions() {
        return jdbcTemplate.queryForList("""
                select distinct o.seq, o.name
                from work_order_operation o
                where exists (select 1 from work_order_unit u where u.work_order_id = o.work_order_id)
                order by o.seq
                """);
    }

    /** 件码唯一，扫一下就知道它在哪道工序、谁最后动过 */
    public Map<String, Object> resolve(String unitNo) {
        Map<String, Object> unit = loadUnit(unitNo);
        long workOrderId = ((Number) unit.get("work_order_id")).longValue();
        Long currentOperationId = unit.get("current_operation_id") == null ? null
                : ((Number) unit.get("current_operation_id")).longValue();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unitNo", unit.get("unit_no"));
        result.put("seqNo", unit.get("seq_no"));
        result.put("status", unit.get("status"));
        result.put("workOrderId", workOrderId);
        result.put("workNo", unit.get("work_no"));
        result.put("productCode", unit.get("product_code"));
        result.put("productName", unit.get("product_name"));

        Map<String, Object> current;
        if (currentOperationId != null) {
            current = operationInfo(currentOperationId);
        } else if ("PENDING".equals(unit.get("status"))) {
            // 刚下达、还没扫过开工的件：把它即将开工的第一道待做工序当作当前工序
            current = firstPendingOperation(workOrderId);
        } else {
            current = null;
        }
        result.put("currentOperation", current);
        Map<String, Object> next = nextOperation(workOrderId, current == null ? null : currentSeq(current));
        result.put("nextOperation", next);
        result.put("canStart", current != null && "PENDING".equals(unit.get("status")));
        result.put("canFinish", current != null && "IN_PROGRESS".equals(unit.get("status")));
        result.put("canScrap", !"FINISHED".equals(unit.get("status")) && !"SCRAPPED".equals(unit.get("status")));
        result.put("logs", logs(((Number) unit.get("id")).longValue()));
        return result;
    }

    // ---------------------------------------------------------------- 流转

    /** 开工：件进入当前工序 */
    @Transactional
    public Map<String, Object> start(String unitNo, String worker) {
        Map<String, Object> unit = loadUnit(unitNo);
        long unitId = ((Number) unit.get("id")).longValue();
        long workOrderId = ((Number) unit.get("work_order_id")).longValue();
        String status = String.valueOf(unit.get("status"));
        if ("FINISHED".equals(status) || "SCRAPPED".equals(status)) {
            throw new BizException(2021, "该件已结束流转，不能再开工");
        }
        Map<String, Object> operation = currentOrFirstOperation(unit, workOrderId);
        if (operation == null) {
            throw new BizException(2008, "该工单没有待生产的工序");
        }
        long operationId = ((Number) operation.get("id")).longValue();
        int operationSeq = ((Number) operation.get("seq")).intValue();
        if ("PENDING".equals(status)) {
            jdbcTemplate.update("""
                    update work_order_unit set status = 'IN_PROGRESS', current_operation_id = ?,
                           current_operation_seq = ?, last_action_at = now()
                    where id = ?
                    """, operationId, operationSeq, unitId);
            writeLog(unitId, workOrderId, operationId, operationSeq, "START", worker, null);
        }
        return resolve(unitNo);
    }

    /**
     * 完工：件在当前工序完成，工序完成数 +1 并写报工流水。
     * 合格则流转到下一道，末道合格置为已完工；不合格置为报废。
     */
    @Transactional
    public Map<String, Object> finish(String unitNo, FinishRequest request) {
        Map<String, Object> unit = loadUnit(unitNo);
        long unitId = ((Number) unit.get("id")).longValue();
        long workOrderId = ((Number) unit.get("work_order_id")).longValue();
        String status = String.valueOf(unit.get("status"));
        if ("FINISHED".equals(status) || "SCRAPPED".equals(status)) {
            throw new BizException(2021, "该件已结束流转，不能重复完工");
        }
        Map<String, Object> operation = currentOrFirstOperation(unit, workOrderId);
        if (operation == null) {
            throw new BizException(2008, "该工单没有待生产的工序");
        }
        long operationId = ((Number) operation.get("id")).longValue();
        int operationSeq = ((Number) operation.get("seq")).intValue();
        boolean qualified = request == null || request.qualified() == null || request.qualified();
        String worker = request != null && request.worker() != null && !request.worker().isBlank()
                ? request.worker().trim()
                : CurrentUser.name();
        String remark = request == null ? null : request.remark();

        // 1) 工序完成数 +1，写报工流水（与手工报工同一张表，看板与报表口径一致）
        jdbcTemplate.update("""
                update work_order_operation
                set done_qty = done_qty + 1,
                    status = case when done_qty + 1 >= plan_qty then 'DONE' else 'IN_PROGRESS' end
                where id = ?
                """, operationId);
        jdbcTemplate.update("""
                insert into work_report (work_order_id, operation_id, qty, qualified_qty, defect_qty, worker, remark)
                values (?, ?, 1, ?, ?, ?, ?)
                """, workOrderId, operationId, qualified ? BigDecimal.ONE : BigDecimal.ZERO,
                qualified ? BigDecimal.ZERO : BigDecimal.ONE, worker, "SN:" + unitNo);

        if (!qualified) {
            jdbcTemplate.update("""
                    update work_order_unit set status = 'SCRAPPED', current_operation_id = null,
                           current_operation_seq = null, last_action_at = now()
                    where id = ?
                    """, unitId);
            writeLog(unitId, workOrderId, operationId, operationSeq, "FINISH", worker,
                    remark == null ? "本工序不合格，件报废" : remark);
            syncWorkOrderProgress(workOrderId);
            return resolve(unitNo);
        }

        // 2) 合格：流转到下一道；没有下一道则整件完工
        Map<String, Object> next = nextOperation(workOrderId, operationSeq);
        writeLog(unitId, workOrderId, operationId, operationSeq, "FINISH", worker, remark);
        if (next == null) {
            jdbcTemplate.update("""
                    update work_order_unit set status = 'FINISHED', current_operation_id = null,
                           current_operation_seq = null, last_action_at = now()
                    where id = ?
                    """, unitId);
        } else {
            jdbcTemplate.update("""
                    update work_order_unit set status = 'PENDING', current_operation_id = ?,
                           current_operation_seq = ?, last_action_at = now()
                    where id = ?
                    """, ((Number) next.get("id")).longValue(), ((Number) next.get("seq")).intValue(), unitId);
        }
        syncWorkOrderProgress(workOrderId);
        return resolve(unitNo);
    }

    /** 报废：件在未完工前直接判废，不影响工序完成数 */
    @Transactional
    public Map<String, Object> scrap(String unitNo, String reason, String worker) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(1002, "报废必须填写原因");
        }
        Map<String, Object> unit = loadUnit(unitNo);
        long unitId = ((Number) unit.get("id")).longValue();
        String status = String.valueOf(unit.get("status"));
        if ("FINISHED".equals(status) || "SCRAPPED".equals(status)) {
            throw new BizException(2021, "该件已结束流转，不能重复报废");
        }
        long workOrderId = ((Number) unit.get("work_order_id")).longValue();
        Long operationId = unit.get("current_operation_id") == null ? null
                : ((Number) unit.get("current_operation_id")).longValue();
        Integer operationSeq = unit.get("current_operation_seq") == null ? null
                : ((Number) unit.get("current_operation_seq")).intValue();
        jdbcTemplate.update("""
                update work_order_unit set status = 'SCRAPPED', current_operation_id = null,
                       current_operation_seq = null, last_action_at = now()
                where id = ?
                """, unitId);
        writeLog(unitId, workOrderId, operationId, operationSeq, "SCRAP",
                worker == null || worker.isBlank() ? CurrentUser.name() : worker.trim(), reason);
        syncWorkOrderProgress(workOrderId);
        return resolve(unitNo);
    }

    // ---------------------------------------------------------------- 内部工具

    /**
     * 件流转后回写工单达成：完成数取末道工序完成数，合格数取末道报工合格合计，
     * 全部件结束（完工或报废）时工单置为已完工；入库仍走完工检。
     */
    private void syncWorkOrderProgress(long workOrderId) {
        Integer lastSeq = jdbcTemplate.queryForObject(
                "select max(seq) from work_order_operation where work_order_id = ?", Integer.class, workOrderId);
        BigDecimal lastDone = BigDecimal.ZERO;
        BigDecimal qualifiedTotal = BigDecimal.ZERO;
        if (lastSeq != null) {
            BigDecimal done = jdbcTemplate.queryForObject("""
                    select done_qty from work_order_operation where work_order_id = ? and seq = ?
                    """, BigDecimal.class, workOrderId, lastSeq);
            lastDone = done == null ? BigDecimal.ZERO : done;
            BigDecimal qualified = jdbcTemplate.queryForObject("""
                    select coalesce(sum(r.qualified_qty), 0) from work_report r
                    join work_order_operation o on o.id = r.operation_id
                    where r.work_order_id = ? and o.seq = ?
                    """, BigDecimal.class, workOrderId, lastSeq);
            qualifiedTotal = qualified == null ? BigDecimal.ZERO : qualified;
        }
        Integer active = jdbcTemplate.queryForObject("""
                select count(*) from work_order_unit
                where work_order_id = ? and status not in ('FINISHED', 'SCRAPPED')
                """, Integer.class, workOrderId);
        boolean allUnitsDone = active != null && active == 0;
        jdbcTemplate.update("""
                update work_order set done_qty = ?, qualified_qty = ?,
                       status = case when ? then 'FINISHED' else 'IN_PROGRESS' end,
                       updated_at = now(), version = version + 1
                where id = ? and status in ('RELEASED', 'IN_PROGRESS')
                """, lastDone, qualifiedTotal, allUnitsDone, workOrderId);
        // 单件全部完工即整单完工，顺手结算成本
        if (allUnitsDone) {
            try {
                costService.recalc(workOrderId);
            } catch (Exception e) {
                // 成本结算失败不影响流转，可在成本报表里重算
            }
        }
    }

    private Map<String, Object> loadUnit(String unitNo) {
        if (unitNo == null || unitNo.isBlank()) {
            throw new BizException(1002, "请扫描件码");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select u.id, u.work_order_id, u.unit_no, u.seq_no, u.status,
                       u.current_operation_id, u.current_operation_seq,
                       w.work_no, w.status as work_status,
                       p.code as product_code, p.name as product_name
                from work_order_unit u
                join work_order w on w.id = u.work_order_id
                join rm_product p on p.id = w.product_id
                where u.unit_no = ?
                """, unitNo.trim());
        if (rows.isEmpty()) {
            throw new BizException(2022, "件码不存在：" + unitNo);
        }
        return rows.get(0);
    }

    private Map<String, Object> currentOrFirstOperation(Map<String, Object> unit, long workOrderId) {
        if (unit.get("current_operation_id") != null) {
            return operationInfo(((Number) unit.get("current_operation_id")).longValue());
        }
        return firstPendingOperation(workOrderId);
    }

    /** 工单上第一道未做完的工序，用于件还没绑定工序时判断它该在哪道开工 */
    private Map<String, Object> firstPendingOperation(long workOrderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, seq, name, plan_qty as "planQty", done_qty as "doneQty"
                from work_order_operation
                where work_order_id = ? and done_qty < plan_qty
                order by seq limit 1
                """, workOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> operationInfo(long operationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, seq, name, plan_qty as "planQty", done_qty as "doneQty"
                from work_order_operation where id = ?
                """, operationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Integer currentSeq(Map<String, Object> operation) {
        return operation.get("seq") == null ? null : ((Number) operation.get("seq")).intValue();
    }

    private Map<String, Object> nextOperation(long workOrderId, Integer currentSeq) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, seq, name, plan_qty as "planQty", done_qty as "doneQty"
                from work_order_operation
                where work_order_id = ? and seq > ?
                order by seq limit 1
                """, workOrderId, currentSeq == null ? 0 : currentSeq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void writeLog(long unitId, long workOrderId, Long operationId, Integer operationSeq,
                          String action, String worker, String remark) {
        jdbcTemplate.update("""
                insert into work_order_unit_log (unit_id, work_order_id, operation_id, operation_seq,
                                                 action, worker, operator, remark)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, unitId, workOrderId, operationId, operationSeq, action, worker,
                CurrentUser.name(), remark);
    }

    private List<Map<String, Object>> logs(long unitId) {
        return jdbcTemplate.queryForList("""
                select l.action, l.operation_seq as "operationSeq", o.name as "operationName",
                       l.worker, l.remark,
                       to_char(l.created_at, 'MM-DD HH24:MI') as "createdAt"
                from work_order_unit_log l
                left join work_order_operation o on o.id = l.operation_id
                where l.unit_id = ?
                order by l.id desc
                """, unitId);
    }
}
