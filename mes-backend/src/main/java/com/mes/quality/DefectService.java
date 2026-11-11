package com.mes.quality;

import com.mes.common.BizException;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.platform.audit.OperLogService;
import com.mes.process.api.ProcessPort;
import com.mes.production.api.ReworkPort;
import com.mes.production.api.ReworkRequest;
import com.mes.production.api.WorkOrderPort;
import com.mes.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 不良品处置：返修、让步接收、报废、退供应商。
 * 返修会真的开一张返修工单；让步接收会把数量降级放行入成品仓；报废与退供应商只留痕。
 *
 * <p>工单号、工序序号、合格数回写都属于生产模块（走 {@link WorkOrderPort}），
 * 产品名称问 masterdata、工序名称问 process，不良台账自己只记事实与处置结论。</p>
 */
@Service
public class DefectService {

    private static final Set<String> DISPOSITIONS = Set.of(
            "REWORK", "CONCESSION", "SCRAP", "RETURN_SUPPLIER");

    private final JdbcTemplate jdbcTemplate;
    private final ReworkPort reworkService;
    private final InventoryPort inventoryService;
    private final NoticePort notice;
    private final OperLogService operLogService;
    private final WorkOrderPort workOrderPort;
    private final ProcessPort processPort;
    private final MasterDataPort masterData;

    public DefectService(JdbcTemplate jdbcTemplate,
                         ReworkPort reworkService,
                         InventoryPort inventoryService,
                         NoticePort notice,
                         OperLogService operLogService,
                         WorkOrderPort workOrderPort,
                         ProcessPort processPort,
                         MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.reworkService = reworkService;
        this.inventoryService = inventoryService;
        this.notice = notice;
        this.operLogService = operLogService;
        this.workOrderPort = workOrderPort;
        this.processPort = processPort;
        this.masterData = masterData;
    }

    public record DisposeRequest(@NotBlank String disposition, String remark) {
    }

    public List<Map<String, Object>> list(String status, String keyword, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        // 工单号属于生产、产品名属于 masterdata：各自换成 id 集合，正文里不再 join 别人的表
        Set<Long> workOrderIds = workOrderPort.searchIds(keyword);
        Set<Long> productIds = masterData.searchProductIds(keyword);
        String workOrderCondition = workOrderIds.isEmpty()
                ? "1 = 0"
                : "d.work_order_id in (" + String.join(", ",
                        java.util.Collections.nCopies(workOrderIds.size(), "?")) + ")";
        String productCondition = productIds.isEmpty()
                ? "1 = 0"
                : "d.product_id in (" + String.join(", ",
                        java.util.Collections.nCopies(productIds.size(), "?")) + ")";
        StringBuilder sql = new StringBuilder("""
                select d.id, d.defect_no as "defectNo", d.work_order_id as "workOrderId",
                       d.operation_id as "operationId",
                       d.process_id as "processId",
                       d.product_id as "productId",
                       d.qty, d.source, d.reason, d.disposition, d.status,
                       d.rework_work_order_id as "reworkWorkOrderId",
                       d.handled_by as "handledBy",
                       to_char(d.handled_at, 'YYYY-MM-DD HH24:MI') as "handledAt",
                       to_char(d.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       d.remark
                from defect_record d
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" and d.status = ? ");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (d.defect_no ilike ? or ").append(workOrderCondition)
                    .append(" or ").append(productCondition).append(") ");
            args.add("%" + keyword.trim() + "%");
            args.addAll(workOrderIds);
            args.addAll(productIds);
        }
        sql.append(" order by d.id desc limit ?");
        args.add(safeLimit);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        MasterDataViews.fillProductNames(masterData, rows);
        fillOperationAndProcess(rows);
        fillWorkNos(rows);
        return rows;
    }

    /** 工序名称与工序序号属于生产、工序名称的另一份在 process；这里都按 id 批量补 */
    private void fillOperationAndProcess(List<Map<String, Object>> rows) {
        List<Long> operationIds = rows.stream()
                .map(row -> row.get("operationId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        Map<Long, WorkOrderPort.WorkOrderOperation> operations = operationIds.isEmpty()
                ? Map.of() : workOrderPort.operations(operationIds);
        List<Long> processIds = rows.stream()
                .map(row -> row.get("processId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        Map<Long, String> processNames = processIds.isEmpty() ? Map.of() : processPort.names(processIds);
        for (Map<String, Object> row : rows) {
            Object operationId = row.get("operationId");
            WorkOrderPort.WorkOrderOperation operation =
                    operationId == null ? null : operations.get(((Number) operationId).longValue());
            row.put("operationName", operation == null ? null : operation.name());
            row.put("operationSeq", operation == null ? null : operation.seq());
            Object processId = row.get("processId");
            row.put("processName", processId == null ? null : processNames.get(((Number) processId).longValue()));
        }
    }

    /** 工单号（含返修工单号）属于生产模块 */
    private void fillWorkNos(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .flatMap(row -> java.util.stream.Stream.of(row.get("workOrderId"), row.get("reworkWorkOrderId")))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> workNos = workOrderPort.workNos(ids);
        for (Map<String, Object> row : rows) {
            Object workOrderId = row.get("workOrderId");
            row.put("workNo", workOrderId == null ? null : workNos.get(((Number) workOrderId).longValue()));
            Object reworkId = row.get("reworkWorkOrderId");
            row.put("reworkWorkNo", reworkId == null ? null : workNos.get(((Number) reworkId).longValue()));
        }
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", count("select count(*) from defect_record where status = 'PENDING'"));
        result.put("pendingQty", online("""
                select coalesce(sum(qty), 0) from defect_record where status = 'PENDING'
                """));
        result.put("rework", count("select count(*) from defect_record where disposition = 'REWORK'"));
        result.put("concession", count("select count(*) from defect_record where disposition = 'CONCESSION'"));
        result.put("scrap", count("select count(*) from defect_record where disposition = 'SCRAP'"));
        result.put("returnSupplier", count("""
                select count(*) from defect_record where disposition = 'RETURN_SUPPLIER'
                """));
        return result;
    }

    @Transactional
    public Map<String, Object> dispose(long id, DisposeRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select d.id, d.defect_no as "defectNo", d.work_order_id as "workOrderId",
                       d.product_id as "productId", d.qty, d.source, d.status, d.reason,
                       d.operation_id as "operationId"
                from defect_record d
                where d.id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2019, "不良记录不存在");
        }
        Map<String, Object> defect = rows.get(0);
        // 工序序号来自生产模块（不良挂在哪道工序，返修就从哪道工序起做）
        Object operationIdOfDefect = defect.get("operationId");
        WorkOrderPort.WorkOrderOperation operation = operationIdOfDefect == null ? null
                : workOrderPort.operations(List.of(((Number) operationIdOfDefect).longValue()))
                        .get(((Number) operationIdOfDefect).longValue());
        defect.put("operationSeq", operation == null ? null : operation.seq());
        if (!"PENDING".equals(String.valueOf(defect.get("status")))) {
            throw new BizException(2019, "该不良记录已处置，不能重复处理");
        }
        String disposition = request.disposition() == null
                ? "" : request.disposition().trim().toUpperCase(Locale.ROOT);
        if (!DISPOSITIONS.contains(disposition)) {
            throw new BizException(1002, "处置方式只能是返修、让步接收、报废或退供应商");
        }
        BigDecimal qty = new BigDecimal(String.valueOf(defect.get("qty")));
        Long workOrderId = defect.get("workOrderId") == null
                ? null : ((Number) defect.get("workOrderId")).longValue();
        Long productId = defect.get("productId") == null
                ? null : ((Number) defect.get("productId")).longValue();
        Long reworkId = null;
        String remark = request.remark();

        switch (disposition) {
            case "REWORK" -> {
                if (productId == null) {
                    throw new BizException(2019, "该不良记录没有关联产品，无法开返修工单");
                }
                WorkOrderPort.WorkOrderRef order = workOrderId == null ? null
                        : workOrderPort.workOrder(workOrderId);
                // 不良是哪道工序发现的，返修就从哪道工序起做
                Integer fromSeq = defect.get("operationSeq") == null
                        ? null : ((Number) defect.get("operationSeq")).intValue();
                reworkId = reworkService.createRework(new ReworkRequest(productId, qty, workOrderId,
                        order == null ? null : order.salesOrderId(),
                        order == null ? null : order.salesOrderItemId(),
                        order == null ? null : order.planEnd(), defect.get("reason") == null
                        ? "不良处置返修" : String.valueOf(defect.get("reason")), fromSeq));
            }
            case "CONCESSION" -> {
                if (productId == null) {
                    throw new BizException(2019, "该不良记录没有关联产品，无法让步接收入库");
                }
                long finishedWarehouse = inventoryService.warehouseOfType("FINISHED");
                // 让步接收的货也来自某张工单，批次沿用该工单号，追溯不会断
                String concessionBatch = workOrderId == null ? "ADJ-" + defect.get("defectNo")
                        : workOrderPort.workNo(workOrderId);
                inventoryService.inboundWithBatch(productId, finishedWarehouse, qty, "DEFECT_CONCESSION",
                        String.valueOf(defect.get("defectNo")), "让步接收入库",
                        concessionBatch, "PRODUCTION", workOrderId);
                if (workOrderId != null) {
                    // 合格数回写是生产的字段口径
                    workOrderPort.addQualifiedQty(workOrderId, qty);
                }
            }
            default -> {
                // 报废与退供应商只留痕，不动库存
            }
        }

        jdbcTemplate.update("""
                update defect_record set disposition = ?, status = 'DISPOSED', rework_work_order_id = ?,
                       handled_by = ?, handled_at = now(), remark = coalesce(?, remark)
                where id = ?
                """, disposition, reworkId, CurrentUser.name(), remark, id);
        notice.pushToRoles(List.of("QC", "PLANNER"), NoticeMessage.of("INFO",
                "不良 " + defect.get("defectNo") + " 已处置",
                "处置方式：" + disposition + (remark == null ? "" : "，备注：" + remark),
                "DEFECT", String.valueOf(defect.get("defectNo"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("disposition", disposition);
        result.put("reworkWorkOrderId", reworkId);
        result.put("reworkFromSeq", defect.get("operationSeq"));
        if (reworkId != null) {
            result.put("reworkWorkNo", workOrderPort.workNo(reworkId));
        }
        operLogService.touch("DEFECT", id, String.valueOf(defect.get("defectNo")),
                "不良单 " + defect.get("defectNo") + " 处置为" + dispositionLabel(disposition));
        operLogService.action("处置");
        return result;
    }

    private static String dispositionLabel(String disposition) {
        return switch (disposition == null ? "" : disposition) {
            case "REWORK" -> "返修";
            case "CONCESSION" -> "让步接收";
            case "SCRAP" -> "报废";
            case "RETURN_SUPPLIER" -> "退供应商";
            default -> disposition == null ? "-" : disposition;
        };
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private BigDecimal online(String sql) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }
}
