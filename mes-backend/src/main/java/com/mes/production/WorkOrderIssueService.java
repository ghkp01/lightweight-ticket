package com.mes.production;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.inventory.api.InventoryPort;
import com.mes.inventory.api.BatchMove;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
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
 * 生产领料单服务。
 *
 * 领料的三个入口（整单、按工序、单物料扫码、以及 PC 批量）全部收口到这里：
 * 先校验剩余需求与库存，再生成一张领料单，最后按批次扣减库存并回写工单 BOM。
 * 领料单是账实核对与追责的凭据，库存流水通过 issue_id 回指单据。
 */
@Service
public class WorkOrderIssueService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final MasterDataPort masterData;

    public WorkOrderIssueService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                                 InventoryPort inventoryService,
                                 MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.masterData = masterData;
    }

    public record IssueLine(Long productId, BigDecimal qty) {
    }

    public record IssueBatchRequest(Long warehouseId, String issuer, String team, String source,
                                    String remark, List<IssueLine> items) {
    }

    public record ReturnLine(Long productId, BigDecimal qty, Long warehouseId) {
    }

    public record ReturnRequest(Long warehouseId, String returner, String team, String source,
                                String reason, List<ReturnLine> items) {
    }

    /** 一行领料计划：主料不够时拆成「主料 + 替代料」两次扣减 */
    private record PlannedIssue(long materialId, String materialName,
                                BigDecimal mainQty, Long subMaterialId, String subMaterialName,
                                BigDecimal subQty) {
    }

    // ---------------------------------------------------------------- 对外入口

    /** PC 批量领料：一次提交多行，同一事务内完成 */
    @Transactional
    public Map<String, Object> issueBatch(long workOrderId, IssueBatchRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请填写领料明细");
        }
        return doIssue(workOrderId, request.warehouseId(), request.issuer(), request.team(),
                normalizeSource(request.source()), request.remark(), request.items());
    }

    /** 整单领料：把工单所有待领 BOM 一次领完，可多次调用补齐 */
    @Transactional
    public Map<String, Object> issueAll(long workOrderId) {
        List<IssueLine> lines = toLines(pendingLines(workOrderId, null));
        if (lines.isEmpty()) {
            throw new BizException(2008, "该工单没有待领物料，请检查工单 BOM 或工艺路线");
        }
        return doIssue(workOrderId, null, null, null, "PC", "整单领料", lines);
    }

    /** 按工序领料：只领该工序的待领物料 */
    @Transactional
    public Map<String, Object> issueByOperation(long workOrderId, int operationSeq) {
        List<IssueLine> lines = toLines(pendingLines(workOrderId, operationSeq));
        if (lines.isEmpty()) {
            throw new BizException(2008, "第 " + operationSeq + " 道工序没有待领物料");
        }
        Map<String, Object> result = doIssue(workOrderId, null, null, null, "PC",
                "第 " + operationSeq + " 道工序领料", lines);
        result.put("operationSeq", operationSeq);
        return result;
    }

    /** 单物料领料：车间扫码场景 */
    @Transactional
    public Map<String, Object> issueOne(long workOrderId, Long productId, BigDecimal qty,
                                        Long warehouseId, String issuer, String team, String source) {
        if (productId == null || qty == null || qty.signum() <= 0) {
            throw new BizException(1002, "请提供物料和领料数量");
        }
        Map<String, Object> result = doIssue(workOrderId, warehouseId, issuer, team,
                normalizeSource(source), null, List.of(new IssueLine(productId, qty)));
        BigDecimal remaining = jdbcTemplate.queryForObject("""
                select coalesce(max(b.plan_qty - b.issued_qty), 0) from work_order_bom b
                where b.work_order_id = ? and b.material_id = ? and b.source <> 'SUBSTITUTE'
                """, BigDecimal.class, workOrderId, productId);
        result.put("productId", productId);
        result.put("qty", qty);
        result.put("remainingQty", remaining == null ? ZERO : remaining.max(ZERO));
        return result;
    }

    /** 工单维度的领料单列表，供详情页展示 */
    public List<Map<String, Object>> listIssues(long workOrderId) {
        List<Map<String, Object>> issues = jdbcTemplate.queryForList("""
                select i.id, i.issue_no as "issueNo", i.issuer, i.team, i.operator, i.source,
                       i.total_qty as "totalQty", i.remark,
                       w.name as "warehouseName", i.warehouse_id as "warehouseId",
                       to_char(i.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       (select count(*) from production_issue_item it where it.issue_id = i.id) as "itemCount"
                from production_issue i
                join rm_warehouse w on w.id = i.warehouse_id
                where i.work_order_id = ?
                order by i.id desc
                """, workOrderId);
        for (Map<String, Object> issue : issues) {
            issue.put("items", jdbcTemplate.queryForList("""
                    select it.id, it.material_id as "materialId", p.code as "materialCode",
                           p.name as "materialName", p.unit, it.qty,
                           it.alternative_for as "alternativeFor", af.name as "alternativeForName",
                           it.batch_summary as "batchSummary"
                    from production_issue_item it
                    join rm_product p on p.id = it.material_id
                    left join rm_product af on af.id = it.alternative_for
                    where it.issue_id = ?
                    order by it.id
                    """, ((Number) issue.get("id")).longValue()));
        }
        return issues;
    }

    // ---------------------------------------------------------------- 退料

    /**
     * 生产退料：把领多了的料按原批次开单退回。
     * 校验可退量 → 生成退料单 → 逐批次回库（沿用原批次号）→ 扣减工单 BOM 已领量。
     */
    @Transactional
    public Map<String, Object> returnBatch(long workOrderId, ReturnRequest request) {
        Map<String, Object> work = loadWork(workOrderId);
        String status = String.valueOf(work.get("status"));
        if (!List.of("RELEASED", "IN_PROGRESS", "FINISHED", "REWORK").contains(status)) {
            throw new BizException(2006, "当前工单状态不允许退料");
        }
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请选择退料物料并填写退料数量");
        }
        String workNo = String.valueOf(work.get("work_no"));
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select count(*) = 0 from rm_stock_txn where txn_type = 'ISSUE' and ref_no = ?
                """, Boolean.class, workNo))) {
            throw new BizException(2012, "该工单尚未领料，不能退料");
        }

        // 同一物料多行合并；行级仓库优先级最高，其次是单据仓库、原领料仓库
        Map<Long, Long> warehouseOverride = new LinkedHashMap<>();
        Map<Long, BigDecimal> merged = new LinkedHashMap<>();
        for (ReturnLine item : request.items()) {
            if (item == null || item.productId() == null || item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "退料物料与数量必须填写完整，数量需大于 0");
            }
            merged.merge(item.productId(), item.qty(), BigDecimal::add);
            if (item.warehouseId() != null) {
                warehouseOverride.put(item.productId(), item.warehouseId());
            }
        }

        List<PlannedReturn> plan = planReturn(workOrderId, workNo, request.warehouseId(), warehouseOverride, merged);
        BigDecimal totalQty = plan.stream().map(PlannedReturn::qty).reduce(ZERO, BigDecimal::add);

        String operator = CurrentUser.name();
        String realReturner = trimToNull(request.returner());
        if (realReturner == null) {
            realReturner = operator;
        }
        if (realReturner.length() > 40) {
            realReturner = realReturner.substring(0, 40);
        }
        String realTeam = trimToNull(request.team());
        if (realTeam != null && realTeam.length() > 40) {
            realTeam = realTeam.substring(0, 40);
        }
        long headerWarehouse = request.warehouseId() != null
                ? request.warehouseId() : plan.get(0).warehouseId();
        String returnNo = codeGenerator.next("MR");
        long returnId = jdbcTemplate.queryForObject("""
                insert into production_return (return_no, work_order_id, warehouse_id, returner, team,
                                               operator, source, reason, total_qty)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, returnNo, workOrderId, headerWarehouse, realReturner, realTeam,
                operator, normalizeSource(request.source()), trimToNull(request.reason()), totalQty);

        List<Map<String, Object>> resultLines = new ArrayList<>();
        for (PlannedReturn line : plan) {
            StringBuilder summary = new StringBuilder();
            for (BatchAlloc alloc : line.allocs()) {
                inventoryService.returnMaterialBatch(line.materialId(), line.warehouseId(),
                        alloc.qty(), workNo, alloc.batchNo(), returnId);
                if (summary.length() > 0) {
                    summary.append(" / ");
                }
                summary.append(alloc.batchNo()).append("×").append(alloc.qty().stripTrailingZeros().toPlainString());
            }
            String batchSummary = summary.isEmpty() ? null : summary.toString();
            jdbcTemplate.update("""
                    insert into production_return_item (return_id, material_id, warehouse_id, qty, batch_summary)
                    values (?, ?, ?, ?, ?)
                    """, returnId, line.materialId(), line.warehouseId(), line.qty(),
                    batchSummary == null || batchSummary.length() <= 200 ? batchSummary : batchSummary.substring(0, 200));

            // 退的是替代料时，替代行自身与主料行的「替代补量」都要回退
            List<Map<String, Object>> subRow = jdbcTemplate.queryForList("""
                    select substitute_for from work_order_bom
                    where work_order_id = ? and material_id = ? and source = 'SUBSTITUTE'
                    """, workOrderId, line.materialId());
            markIssued(workOrderId, line.materialId(), line.qty().negate());
            if (!subRow.isEmpty() && subRow.get(0).get("substitute_for") != null) {
                long mainId = ((Number) subRow.get(0).get("substitute_for")).longValue();
                markSubIssued(workOrderId, mainId, line.qty().negate());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("materialId", line.materialId());
            out.put("materialName", line.materialName());
            out.put("qty", line.qty());
            out.put("warehouseId", line.warehouseId());
            out.put("batchSummary", batchSummary);
            resultLines.add(out);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("returnId", returnId);
        result.put("returnNo", returnNo);
        result.put("workOrderId", workOrderId);
        result.put("warehouseId", headerWarehouse);
        result.put("returner", realReturner);
        result.put("team", realTeam);
        result.put("source", normalizeSource(request.source()));
        result.put("totalQty", totalQty);
        result.put("itemCount", resultLines.size());
        result.put("lines", resultLines);
        return result;
    }

    /** 工单的退料单列表 */
    public List<Map<String, Object>> listReturns(long workOrderId) {
        List<Map<String, Object>> returns = jdbcTemplate.queryForList("""
                select r.id, r.return_no as "returnNo", r.returner, r.team, r.operator, r.source, r.reason,
                       r.total_qty as "totalQty", w.name as "warehouseName", r.warehouse_id as "warehouseId",
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       (select count(*) from production_return_item it where it.return_id = r.id) as "itemCount"
                from production_return r
                join rm_warehouse w on w.id = r.warehouse_id
                where r.work_order_id = ?
                order by r.id desc
                """, workOrderId);
        for (Map<String, Object> row : returns) {
            row.put("items", jdbcTemplate.queryForList("""
                    select it.id, it.material_id as "materialId", p.code as "materialCode",
                           p.name as "materialName", p.unit, it.qty, it.batch_summary as "batchSummary",
                           w.name as "warehouseName"
                    from production_return_item it
                    join rm_product p on p.id = it.material_id
                    left join rm_warehouse w on w.id = it.warehouse_id
                    where it.return_id = ?
                    order by it.id
                    """, ((Number) row.get("id")).longValue()));
        }
        return returns;
    }

    /** 可退料明细：已领、已退、可退、超出需求（领多了）的量，前端用来一键填数 */
    public List<Map<String, Object>> returnableLines(long workOrderId) {
        String workNo = jdbcTemplate.queryForObject(
                "select work_no from work_order where id = ?", String.class, workOrderId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select b.material_id as "materialId", p.code as "materialCode", p.name as "materialName",
                       p.unit, b.plan_qty as "planQty", b.source,
                       coalesce(b.issued_qty, 0) as "issuedQty",
                       coalesce((select sum(t.qty) from rm_stock_txn t
                                 where t.txn_type = 'RETURN_MAT' and t.ref_no = ? and t.product_id = b.material_id), 0)
                           as "returnedQty"
                from work_order_bom b
                join rm_product p on p.id = b.material_id
                where b.work_order_id = ?
                order by b.operation_seq nulls last, b.id
                """, workNo, workOrderId);
        for (Map<String, Object> row : rows) {
            BigDecimal issued = decimal(row.get("issuedQty"));
            BigDecimal returned = decimal(row.get("returnedQty"));
            BigDecimal plan = decimal(row.get("planQty"));
            BigDecimal returnable = issued.subtract(returned);
            if (returnable.signum() < 0) {
                returnable = ZERO;
            }
            // 领多了的量 = 已领 - 需求；建议退料量给的就是这部分，未超领则为 0
            BigDecimal over = issued.subtract(plan);
            BigDecimal suggest = over.signum() > 0 ? over.min(returnable) : ZERO;
            row.put("issuedQty", issued);
            row.put("returnedQty", returned);
            row.put("returnableQty", returnable);
            row.put("suggestReturnQty", suggest);
        }
        return rows;
    }

    // ---------------------------------------------------------------- 内部实现

    private Map<String, Object> doIssue(long workOrderId, Long warehouseId, String issuer, String team,
                                        String source, String remark, List<IssueLine> items) {
        Map<String, Object> work = loadWork(workOrderId);
        String status = String.valueOf(work.get("status"));
        if (!"RELEASED".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw new BizException(2006, "只有已下达或生产中的工单可以领料");
        }
        String workNo = String.valueOf(work.get("work_no"));
        long warehouse = warehouseId == null ? inventoryService.warehouseOfType("RAW") : warehouseId;
        assertWarehouse(warehouse);

        // 同一请求里同物料出现多行时先合并，避免重复占用可用量
        Map<Long, BigDecimal> merged = new LinkedHashMap<>();
        for (IssueLine item : items) {
            if (item == null || item.productId() == null || item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "领料明细的物料与数量不能为空，数量必须大于 0");
            }
            merged.merge(item.productId(), item.qty(), BigDecimal::add);
        }

        List<PlannedIssue> plan = planIssue(workOrderId, warehouse, merged);
        BigDecimal totalQty = ZERO;
        for (PlannedIssue line : plan) {
            totalQty = totalQty.add(line.mainQty()).add(line.subQty());
        }
        if (totalQty.signum() <= 0) {
            throw new BizException(2009, "本次没有可领数量，请检查领料数量");
        }

        String operator = CurrentUser.name();
        String realIssuer = trimToNull(issuer);
        if (realIssuer == null) {
            realIssuer = operator;
        }
        if (realIssuer.length() > 40) {
            realIssuer = realIssuer.substring(0, 40);
        }
        String realTeam = trimToNull(team);
        if (realTeam != null && realTeam.length() > 40) {
            realTeam = realTeam.substring(0, 40);
        }
        String issueNo = codeGenerator.next("PI");
        long issueId = jdbcTemplate.queryForObject("""
                insert into production_issue (issue_no, work_order_id, warehouse_id, issuer, team,
                                              operator, source, remark, total_qty)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, issueNo, workOrderId, warehouse, realIssuer, realTeam,
                operator, source, trimToNull(remark), totalQty);

        List<Map<String, Object>> resultLines = new ArrayList<>();
        for (PlannedIssue line : plan) {
            String batchSummary = null;
            if (line.mainQty().signum() > 0) {
                List<BatchMove> moves = inventoryService.issueAndTrack(
                        line.materialId(), warehouse, line.mainQty(), workNo, "生产领料", issueId);
                batchSummary = summarizeBatches(moves);
                insertIssueItem(issueId, line.materialId(), line.mainQty(), null, batchSummary);
            }
            if (line.subQty().signum() > 0 && line.subMaterialId() != null) {
                List<BatchMove> moves = inventoryService.issueAndTrack(
                        line.subMaterialId(), warehouse, line.subQty(), workNo,
                        "主料 " + line.materialName() + " 不足，替代发料", issueId);
                insertIssueItem(issueId, line.subMaterialId(), line.subQty(), line.materialId(),
                        summarizeBatches(moves));
                // 替代料自身的 BOM 行累计消耗，主料行只把「替代补量」单独记一列
                markIssued(workOrderId, line.subMaterialId(), line.subQty());
                markSubIssued(workOrderId, line.materialId(), line.subQty());
            }
            BigDecimal lineTotal = line.mainQty().add(line.subQty());
            if (lineTotal.signum() > 0) {
                markIssued(workOrderId, line.materialId(), lineTotal);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("materialId", line.materialId());
            out.put("materialName", line.materialName());
            out.put("mainQty", line.mainQty());
            out.put("alternativeMaterialId", line.subMaterialId());
            out.put("alternativeMaterialName", line.subMaterialName());
            out.put("alternativeQty", line.subQty());
            out.put("qty", lineTotal);
            out.put("batchSummary", batchSummary);
            resultLines.add(out);
        }
        if ("RELEASED".equals(status)) {
            jdbcTemplate.update("update work_order set status = 'IN_PROGRESS', updated_at = now() where id = ?",
                    workOrderId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueId", issueId);
        result.put("issueNo", issueNo);
        result.put("workOrderId", workOrderId);
        result.put("warehouseId", warehouse);
        result.put("issuer", realIssuer);
        result.put("team", realTeam);
        result.put("source", source);
        result.put("totalQty", totalQty);
        result.put("itemCount", resultLines.size());
        result.put("lines", resultLines);
        return result;
    }

    /**
     * 外协工序的料不在本厂领：料随「外协发出」走，这里领了会既扣库存又让外协发料对不上。
     */
    private void assertNotOutsourced(long workOrderId, long materialId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.seq, m.name as "materialName"
                from work_order_bom b
                join work_order_operation o
                     on o.work_order_id = b.work_order_id and o.seq = b.operation_seq
                join rm_product m on m.id = b.material_id
                where b.work_order_id = ? and b.material_id = ? and b.source <> 'SUBSTITUTE'
                  and o.outsource_status in ('PENDING_SEND', 'SENT')
                limit 1
                """, workOrderId, materialId);
        if (!rows.isEmpty()) {
            throw new BizException(2040, rows.get(0).get("materialName") + " 属于第 "
                    + rows.get(0).get("seq") + " 道外协工序，料由外协单发出，不在本厂领料");
        }
    }

    /** 校验剩余需求与库存，算出每行「主料多少 + 替代料多少」，此阶段不写任何单据 */
    private List<PlannedIssue> planIssue(long workOrderId, long warehouseId, Map<Long, BigDecimal> merged) {
        Map<Long, BigDecimal> stockLeft = new java.util.HashMap<>();
        List<PlannedIssue> plan = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : merged.entrySet()) {
            long materialId = entry.getKey();
            BigDecimal need = entry.getValue();
            assertNotOutsourced(workOrderId, materialId);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select b.id as "bomId", b.plan_qty - b.issued_qty as "remainingQty",
                           m.name as "materialName", m.unit,
                           s.material_id as "subMaterialId", sm.name as "subMaterialName"
                    from work_order_bom b
                    join rm_product m on m.id = b.material_id
                    left join work_order_bom s
                           on s.work_order_id = b.work_order_id and s.substitute_for = b.material_id
                    left join rm_product sm on sm.id = s.material_id
                    where b.work_order_id = ? and b.material_id = ? and b.source <> 'SUBSTITUTE'
                    """, workOrderId, materialId);
            if (rows.isEmpty()) {
                throw new BizException(2008, materialName(materialId)
                        + " 不在工单 BOM 中；非 BOM 物料请到出库管理开单领用");
            }
            Map<String, Object> row = rows.get(0);
            String material = String.valueOf(row.get("materialName"));
            BigDecimal remaining = decimal(row.get("remainingQty"));
            if (remaining.signum() <= 0) {
                throw new BizException(2009, material + " 已领足，无需继续领料");
            }
            if (need.compareTo(remaining) > 0) {
                throw new BizException(2010, material + " 领料数量超过剩余需求，最多可领 "
                        + remaining.stripTrailingZeros().toPlainString() + " " + row.get("unit"));
            }
            BigDecimal mainStock = stockLeft.computeIfAbsent(materialId,
                    key -> inventoryService.stock(key, warehouseId));
            BigDecimal mainTake = need.min(mainStock);
            if (mainTake.signum() < 0) {
                mainTake = ZERO;
            }
            stockLeft.put(materialId, mainStock.subtract(mainTake));

            BigDecimal gap = need.subtract(mainTake);
            BigDecimal subTake = ZERO;
            Long subMaterialId = row.get("subMaterialId") == null ? null
                    : ((Number) row.get("subMaterialId")).longValue();
            if (gap.signum() > 0) {
                if (subMaterialId == null) {
                    throw new BizException(3003, material + " 库存不足，可用 "
                            + mainStock.stripTrailingZeros().toPlainString() + "，需要 "
                            + need.stripTrailingZeros().toPlainString());
                }
                BigDecimal subStock = stockLeft.computeIfAbsent(subMaterialId,
                        key -> inventoryService.stock(key, warehouseId));
                subTake = gap.min(subStock);
                if (subTake.signum() < 0) {
                    subTake = ZERO;
                }
                stockLeft.put(subMaterialId, subStock.subtract(subTake));
                if (subTake.compareTo(gap) < 0) {
                    throw new BizException(3003, material + " 主料可用 "
                            + mainStock.stripTrailingZeros().toPlainString() + "，替代料 "
                            + row.get("subMaterialName") + " 可用 "
                            + subStock.stripTrailingZeros().toPlainString()
                            + "，合计仍不足 " + need.stripTrailingZeros().toPlainString());
                }
            }
            String subMaterialName = row.get("subMaterialName") == null ? null
                    : String.valueOf(row.get("subMaterialName"));
            plan.add(new PlannedIssue(materialId, material, mainTake, subMaterialId, subMaterialName, subTake));
        }
        return plan;
    }

    /** 待领 BOM 行；operationSeq 为空表示整单 */
    private List<Map<String, Object>> pendingLines(long workOrderId, Integer operationSeq) {
        List<Object> args = new ArrayList<>();
        args.add(workOrderId);
        String extra = "";
        if (operationSeq != null) {
            extra = " and b.operation_seq = ? ";
            args.add(operationSeq);
        }
        return jdbcTemplate.queryForList("""
                select b.material_id as "materialId", m.name as "materialName",
                       b.plan_qty - b.issued_qty as "needQty"
                from work_order_bom b
                join rm_product m on m.id = b.material_id
                where b.work_order_id = ? and b.source <> 'SUBSTITUTE' and b.plan_qty > b.issued_qty
                  -- 已转外协的工序，料由外协单发出，整单领料时自动跳过
                  and not exists (select 1 from work_order_operation o
                                  where o.work_order_id = b.work_order_id
                                    and o.seq = b.operation_seq
                                    and o.outsource_status in ('PENDING_SEND', 'SENT'))
                """ + extra + " order by b.operation_seq nulls last, b.id", args.toArray());
    }

    private List<IssueLine> toLines(List<Map<String, Object>> rows) {
        List<IssueLine> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            lines.add(new IssueLine(((Number) row.get("materialId")).longValue(), decimal(row.get("needQty"))));
        }
        return lines;
    }

    private void insertIssueItem(long issueId, long materialId, BigDecimal qty, Long alternativeFor,
                                 String batchSummary) {
        jdbcTemplate.update("""
                insert into production_issue_item (issue_id, material_id, qty, alternative_for, batch_summary)
                values (?, ?, ?, ?, ?)
                """, issueId, materialId, qty, alternativeFor, batchSummary);
    }

    private void markIssued(long workOrderId, long materialId, BigDecimal qty) {
        jdbcTemplate.update("""
                update work_order_bom set issued_qty = greatest(0, issued_qty + ?)
                where work_order_id = ? and material_id = ?
                """, qty, workOrderId, materialId);
    }

    private void markSubIssued(long workOrderId, long materialId, BigDecimal qty) {
        jdbcTemplate.update("""
                update work_order_bom set sub_issued_qty = greatest(0, sub_issued_qty + ?)
                where work_order_id = ? and material_id = ?
                """, qty, workOrderId, materialId);
    }

    private record BatchAlloc(String batchNo, BigDecimal qty) {
    }

    private record PlannedReturn(long materialId, String materialName, long warehouseId,
                                 BigDecimal qty, List<BatchAlloc> allocs) {
    }

    /** 校验可退量、定仓库、按原批次算出退回明细，此阶段不写任何单据 */
    private List<PlannedReturn> planReturn(long workOrderId, String workNo, Long docWarehouse,
                                           Map<Long, Long> warehouseOverride, Map<Long, BigDecimal> merged) {
        List<PlannedReturn> plan = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : merged.entrySet()) {
            long materialId = entry.getKey();
            BigDecimal qty = entry.getValue();
            String name = materialName(materialId);
            BigDecimal issued = jdbcTemplate.queryForObject("""
                    select coalesce(-sum(qty), 0) from rm_stock_txn
                    where txn_type = 'ISSUE' and ref_no = ? and product_id = ?
                    """, BigDecimal.class, workNo, materialId);
            BigDecimal returned = jdbcTemplate.queryForObject("""
                    select coalesce(sum(qty), 0) from rm_stock_txn
                    where txn_type = 'RETURN_MAT' and ref_no = ? and product_id = ?
                    """, BigDecimal.class, workNo, materialId);
            BigDecimal returnable = (issued == null ? ZERO : issued)
                    .subtract(returned == null ? ZERO : returned);
            if (returnable.signum() <= 0) {
                throw new BizException(2013, name + " 没有可退数量，请检查是否已经退完");
            }
            if (qty.compareTo(returnable) > 0) {
                throw new BizException(2013, name + " 可退数量为 "
                        + returnable.stripTrailingZeros().toPlainString() + "，本次退料 "
                        + qty.stripTrailingZeros().toPlainString());
            }
            long warehouse = warehouseOverride.getOrDefault(materialId, 0L);
            if (warehouse == 0L && docWarehouse != null) {
                warehouse = docWarehouse;
            }
            if (warehouse == 0L) {
                warehouse = lastIssueWarehouse(workNo, materialId);
            }
            if (warehouse == 0L) {
                warehouse = inventoryService.warehouseOfType("RAW");
            }
            assertWarehouse(warehouse);
            plan.add(new PlannedReturn(materialId, name, warehouse, qty,
                    allocateBatches(workNo, materialId, qty)));
        }
        return plan;
    }

    /** 该物料最近一次领料的仓库，退料默认回到这里，避免「从线边仓领、退回原料仓」 */
    private long lastIssueWarehouse(String workNo, long materialId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select warehouse_id from rm_stock_txn
                where txn_type = 'ISSUE' and ref_no = ? and product_id = ?
                order by id desc limit 1
                """, workNo, materialId);
        return rows.isEmpty() ? 0L : ((Number) rows.get(0).get("warehouse_id")).longValue();
    }

    /**
     * 按原领料批次分配退料数量：后领先退，逐批退回原批次号；
     * 历史退料留下的兜底批次（RET-工单号）对不上原批次时，剩余部分继续用兜底批次。
     */
    private List<BatchAlloc> allocateBatches(String workNo, long materialId, BigDecimal qty) {
        List<Map<String, Object>> issues = jdbcTemplate.queryForList("""
                select batch_no as "batchNo", coalesce(-sum(qty), 0) as "issued", max(id) as "lastId"
                from rm_stock_txn
                where txn_type = 'ISSUE' and ref_no = ? and product_id = ? and batch_no is not null
                group by batch_no
                order by max(id) desc
                """, workNo, materialId);
        Map<String, BigDecimal> returnedByBatch = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select batch_no as "batchNo", coalesce(sum(qty), 0) as "returned"
                from rm_stock_txn
                where txn_type = 'RETURN_MAT' and ref_no = ? and product_id = ? and batch_no is not null
                group by batch_no
                """, workNo, materialId)) {
            returnedByBatch.put(String.valueOf(row.get("batchNo")), decimal(row.get("returned")));
        }

        BigDecimal remaining = qty;
        List<BatchAlloc> allocs = new ArrayList<>();
        for (Map<String, Object> issue : issues) {
            if (remaining.signum() <= 0) {
                break;
            }
            String batchNo = String.valueOf(issue.get("batchNo"));
            BigDecimal available = decimal(issue.get("issued"))
                    .subtract(returnedByBatch.getOrDefault(batchNo, ZERO));
            if (available.signum() <= 0) {
                continue;
            }
            BigDecimal take = available.min(remaining);
            allocs.add(new BatchAlloc(batchNo, take));
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            allocs.add(new BatchAlloc("RET-" + workNo, remaining));
        }
        return allocs;
    }

    private Map<String, Object> loadWork(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, work_no, status, product_id from work_order where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException("生产工单不存在");
        }
        return rows.get(0);
    }

    private void assertWarehouse(long warehouseId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from rm_warehouse where id = ? and enabled = true", Integer.class, warehouseId);
        if (count == null || count == 0) {
            throw new BizException(1002, "领料仓库不存在或已停用");
        }
    }

    private String summarizeBatches(List<BatchMove> moves) {
        if (moves == null || moves.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (BatchMove move : moves) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(move.batchNo()).append("×").append(move.qty().stripTrailingZeros().toPlainString());
        }
        String value = text.toString();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private String materialName(long productId) {
        return masterData.product(productId).map(ProductInfo::name).orElse("该物料");
    }

    private String normalizeSource(String source) {
        return "SCAN".equalsIgnoreCase(String.valueOf(source)) ? "SCAN" : "PC";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BigDecimal decimal(Object value) {
        return value == null ? ZERO : new BigDecimal(String.valueOf(value));
    }
}
