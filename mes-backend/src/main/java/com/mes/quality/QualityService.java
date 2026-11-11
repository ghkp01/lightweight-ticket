package com.mes.quality;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.platform.audit.OperLogService;
import com.mes.production.api.ReworkPort;
import com.mes.production.api.ReworkRequest;
import com.mes.production.api.WorkOrderPort;
import com.mes.quality.api.DefectPort;
import com.mes.quality.api.DefectRequest;
import com.mes.sales.api.SalesOrderPort;
import com.mes.task.api.TaskDraft;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import com.mes.warehouse.api.InboundCompletionPort;
import com.mes.warehouse.api.InboundOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检验单：来料 / 工序 / 完工三类，判定后联动库存、工单与不良台账。
 *
 * <p>联动的东西都不是自己的表：工单状态与合格数是生产的规则（走 {@link WorkOrderPort}），
 * 订单推进到待发货是销售的规则（走 {@link SalesOrderPort}），入库单状态与整单入库是仓储的
 * （走 {@link InboundOrderPort} / {@link InboundCompletionPort}），产品与工序名称分别问
 * masterdata 与 process。质检只保留「判定」这一件事。</p>
 */
@Service
public class QualityService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final InboundCompletionPort warehouseService;
    private final InboundOrderPort inboundOrderPort;
    private final ReworkPort reworkService;
    private final WorkOrderPort workOrderPort;
    private final SalesOrderPort salesOrderPort;
    private final DefectPort defectWriter;
    private final InspectionItemService inspectionItemService;
    private final SamplingService samplingService;
    private final OperLogService operLogService;
    private final MasterDataPort masterData;
    private final TaskPort taskService;

    public QualityService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator, InventoryPort inventoryService,
                          InboundCompletionPort warehouseService, InboundOrderPort inboundOrderPort,
                          ReworkPort reworkService, WorkOrderPort workOrderPort, SalesOrderPort salesOrderPort,
                          DefectPort defectWriter, InspectionItemService inspectionItemService,
                          SamplingService samplingService, OperLogService operLogService,
                          MasterDataPort masterData, TaskPort taskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.warehouseService = warehouseService;
        this.inboundOrderPort = inboundOrderPort;
        this.reworkService = reworkService;
        this.workOrderPort = workOrderPort;
        this.salesOrderPort = salesOrderPort;
        this.defectWriter = defectWriter;
        this.inspectionItemService = inspectionItemService;
        this.samplingService = samplingService;
        this.operLogService = operLogService;
        this.masterData = masterData;
        this.taskService = taskService;
    }

    public record InspectionRequest(String type, String refType, String refNo, Long workOrderId, Long productId,
                                    BigDecimal inspectQty, BigDecimal defectQty, String inspector, String remark,
                                    Long inboundOrderId, Integer reworkFromSeq,
                                    Long operationId, Integer operationSeq,
                                    List<InspectionItemService.ItemResultRequest> items,
                                    String samplePlan, BigDecimal aql) {
    }

    public PageResult list(String type, String result, String keyword, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        // 产品名属于 masterdata：先按关键字换成 id 集合，分页仍留在 inspection 上
        Set<Long> productIds = masterData.searchProductIds(keyword);
        String productCondition = productIds.isEmpty()
                ? "1 = 0"
                : "i.product_id in (" + String.join(", ",
                        java.util.Collections.nCopies(productIds.size(), "?")) + ")";
        List<Object> args = new ArrayList<>();
        args.add(like);
        args.addAll(productIds);
        args.add(like);
        StringBuilder where = new StringBuilder(
                " where (i.inspection_no ilike ? or " + productCondition + " or i.ref_no ilike ?) ");
        if (type != null && !type.isBlank()) {
            where.append(" and i.type = ? ");
            args.add(type);
        }
        if (result != null && !result.isBlank()) {
            where.append(" and i.result = ? ");
            args.add(result);
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from inspection i
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select i.id, i.inspection_no as "inspectionNo", i.type, i.ref_type as "refType",
                       i.ref_no as "refNo", i.product_id as "productId",
                       i.inspect_qty as "inspectQty", i.defect_qty as "defectQty", i.result,
                       round((i.inspect_qty - i.defect_qty) * 100.0 / nullif(i.inspect_qty, 0), 2) as "passRate",
                       i.inspector, i.remark,
                       i.operation_id as "operationId", i.operation_seq as "operationSeq",
                       i.sample_plan as "samplePlan", i.sample_qty as "sampleQty",
                       i.accept_qty as "acceptQty", i.reject_qty as "rejectQty",
                       (select count(*) from inspection_item_result r where r.inspection_id = i.id) as "itemCount",
                       to_char(i.inspected_at, 'YYYY-MM-DD HH24:MI') as "inspectedAt"
                from inspection i
                """ + where + " order by i.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillProductNames(masterData, records);
        fillOperationNames(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    /** 补工序名称：工单工序属于生产模块，按 id 批量问一次 */
    private void fillOperationNames(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> row.get("operationId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, WorkOrderPort.WorkOrderOperation> operations = workOrderPort.operations(ids);
        for (Map<String, Object> row : rows) {
            Object id = row.get("operationId");
            WorkOrderPort.WorkOrderOperation operation =
                    id == null ? null : operations.get(((Number) id).longValue());
            row.put("operationName", operation == null ? null : operation.name());
        }
    }

    /**
     * 完工检与来料检的产品由来源单据决定：前端没传产品时从工单 / 入库单明细补齐，
     * 避免「工单已选、数量已填」却因为产品为空建不了单。
     */
    private InspectionRequest fillProduct(InspectionRequest request) {
        if (request == null || request.productId() != null) {
            return request;
        }
        Long productId = null;
        if (request.workOrderId() != null) {
            productId = workOrderPort.productIdOf(request.workOrderId());
        }
        if (productId == null && request.inboundOrderId() != null) {
            // 来料检验的产品取入库单第一行的物料：入库明细属于仓储模块
            productId = inboundOrderPort.lines(request.inboundOrderId()).stream()
                    .map(InboundOrderPort.InboundLine::productId)
                    .findFirst()
                    .orElse(null);
        }
        if (productId == null) {
            return request;
        }
        return new InspectionRequest(request.type(), request.refType(), request.refNo(), request.workOrderId(),
                productId, request.inspectQty(), request.defectQty(), request.inspector(), request.remark(),
                request.inboundOrderId(), request.reworkFromSeq(), request.operationId(), request.operationSeq(),
                request.items(), request.samplePlan(), request.aql());
    }

    @Transactional
    public Map<String, Object> create(InspectionRequest request) {
        request = fillProduct(request);
        if (request.type() == null || !List.of("INCOMING", "PROCESS", "FINAL").contains(request.type())) {
            throw new BizException(1002, "检验类型不正确");
        }
        if (request.productId() == null) {
            throw new BizException(1002, "请选择产品，或选择带产品的生产工单 / 待检入库单");
        }
        if (request.inspectQty() == null || request.inspectQty().signum() <= 0) {
            throw new BizException(1002, "检验数量必须大于 0");
        }
        // 扫码检验带上来的工序：校验归属，避免把 A 工单的工序记到 B 工单上
        Long operationId = request.operationId();
        Integer operationSeq = request.operationSeq();
        Long operationProcessId = null;
        if (operationId != null || operationSeq != null) {
            if (request.workOrderId() == null) {
                throw new BizException(1002, "工序检验要同时指定所属工单");
            }
            WorkOrderPort.WorkOrderOperation operation =
                    workOrderPort.operation(request.workOrderId(), operationId, operationSeq);
            if (operation == null) {
                String hint = operationSeq == null ? "该工序不属于所选工单" : "工单下没有第 " + operationSeq + " 道工序";
                throw new BizException(1002, hint + "，请重新扫码");
            }
            operationId = operation.id();
            operationSeq = operation.seq();
            operationProcessId = operation.processId();
        }

        // 检验项：工序项 + 产品通用项，必检项一个都不能少
        List<Map<String, Object>> templates =
                inspectionItemService.resolve(request.productId(), operationProcessId);
        List<InspectionItemService.ItemResultRequest> itemResults =
                request.items() == null ? List.of() : request.items();
        if (!templates.isEmpty()) {
            java.util.Set<Long> provided = new java.util.HashSet<>();
            for (InspectionItemService.ItemResultRequest item : itemResults) {
                if (item.itemId() != null) {
                    provided.add(item.itemId());
                }
            }
            List<String> missing = new ArrayList<>();
            for (Map<String, Object> template : templates) {
                if (!Boolean.TRUE.equals(template.get("required"))) {
                    continue;
                }
                long templateId = ((Number) template.get("id")).longValue();
                if (!provided.contains(templateId)) {
                    missing.add(String.valueOf(template.get("itemName")));
                }
            }
            if (!missing.isEmpty()) {
                String preview = String.join("、", missing.subList(0, Math.min(3, missing.size())));
                throw new BizException(1002, "还有 " + missing.size() + " 个必检项没填结果：" + preview);
            }
        }

        BigDecimal itemDefect = BigDecimal.ZERO;
        boolean itemFail = false;
        for (InspectionItemService.ItemResultRequest item : itemResults) {
            if (item.defectQty() != null) {
                itemDefect = itemDefect.add(item.defectQty());
            }
            if ("FAIL".equalsIgnoreCase(item.result())) {
                itemFail = true;
            }
        }
        BigDecimal defect = request.defectQty() != null ? request.defectQty() : itemDefect;
        if (defect.signum() < 0 || defect.compareTo(request.inspectQty()) > 0) {
            throw new BizException(1002, "不良数量必须在 0 到检验数量之间");
        }
        SamplingService.SamplePlan samplePlan =
                samplingService.plan(request.inspectQty(), request.samplePlan(), request.aql());
        String result;
        if ("SAMPLE".equals(samplePlan.plan())) {
            // 抽检：不良数不超过接收数就判合格
            result = defect.compareTo(BigDecimal.valueOf(samplePlan.acceptQty())) <= 0 ? "PASS" : "FAIL";
        } else {
            result = defect.signum() == 0 && !itemFail ? "PASS" : "FAIL";
        }

        if ("FINAL".equals(request.type()) && request.workOrderId() != null) {
            String workOrderStatus = workOrderPort.statusOf(request.workOrderId());
            if (workOrderStatus == null) {
                throw new BizException("生产工单不存在");
            }
            if (!"FINISHED".equals(workOrderStatus)) {
                throw new BizException(2022, "工单尚未完工，不能执行完工检验");
            }
            Integer passed = jdbcTemplate.queryForObject("""
                    select count(*) from inspection
                    where type = 'FINAL' and result = 'PASS' and work_order_id = ?
                    """, Integer.class, request.workOrderId());
            if (passed != null && passed > 0) {
                throw new BizException(2023, "该工单已完成完工检验并入库，请勿重复操作");
            }
        }
        if ("INCOMING".equals(request.type()) && request.inboundOrderId() != null) {
            InboundOrderPort.InboundOrderView inbound = inboundOrderPort.inbound(request.inboundOrderId());
            if (inbound == null) {
                throw new BizException(2024, "入库单不存在");
            }
            if (!"PENDING_QC".equals(inbound.status())) {
                throw new BizException(2024, "入库单当前不是待检状态，不能执行来料检验");
            }
        }
        String inspectionNo = codeGenerator.next("QC");
        Long id = jdbcTemplate.queryForObject("""
                insert into inspection (inspection_no, type, ref_type, ref_no, work_order_id, product_id,
                                        inspect_qty, defect_qty, result, inspector, inspected_at, remark,
                                        inbound_order_id, operation_id, operation_seq,
                                        sample_plan, sample_qty, aql, accept_qty, reject_qty)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, inspectionNo, request.type(), request.refType(), request.refNo(),
                request.workOrderId(), request.productId(), request.inspectQty(), defect, result,
                request.inspector(), request.remark(), request.inboundOrderId(),
                operationId, operationSeq,
                samplePlan.plan(), samplePlan.sampleQty(), samplePlan.aql(),
                samplePlan.acceptQty(), samplePlan.rejectQty());
        inspectionItemService.writeResults(id, itemResults);

        String workOrderStatus = null;
        String reworkWorkNo = null;
        Long reworkWorkOrderId = null;
        if ("FINAL".equals(request.type()) && "PASS".equals(result) && request.workOrderId() != null) {
            BigDecimal qualified = request.inspectQty().subtract(defect);
            long finishedWarehouse = inventoryService.warehouseOfType("FINISHED");
            // 成品批次号取工单号：一张工单就是一次投产，追溯时用它对到原料批次与发货客户
            String finishedBatch = workOrderPort.workNo(request.workOrderId());
            inventoryService.inboundWithBatch(request.productId(), finishedWarehouse, qualified,
                    "INSPECTION", inspectionNo, "完工检验入库", finishedBatch, "PRODUCTION", request.workOrderId());
            // 关单与合格数回写是生产的规则；返修工单完工时父工单也跟着关
            workOrderPort.closeAfterFinalInspection(request.workOrderId(), qualified);
            workOrderStatus = "CLOSED";
            WorkOrderPort.WorkOrderRef ref = workOrderPort.workOrder(request.workOrderId());
            // 工单完检，派工与领料两条待办到这里就没意义了，顺手收口
            taskService.close(TaskTypes.WO_ASSIGN, finishedBatch, "完工检", "工单已完工检");
            taskService.close(TaskTypes.WO_PICK, finishedBatch, "完工检", "工单已完工检");
            if (ref != null && ref.salesOrderId() != null
                    && !workOrderPort.hasUnfinishedForSalesOrder(ref.salesOrderId())) {
                // 订单能不能进待发货是销售的规则
                salesOrderPort.markReadyToShip(ref.salesOrderId());
                // 订单进了待发货，销售却不会自己知道：给销售开一条发货任务
                SalesOrderPort.SalesOrderRef order = salesOrderPort.order(ref.salesOrderId());
                if (order != null) {
                    taskService.open(TaskDraft.of(TaskTypes.SHIP_READY,
                            "订单 " + order.orderNo() + " 已完工，可以发货",
                            "完工检已通过，合格品已入成品库，请开发货单并通知客户",
                            TaskTypes.REF_SALES_ORDER, order.id(), order.orderNo(), "SALES"));
                }
            }
        }
        if ("FINAL".equals(request.type()) && "FAIL".equals(result) && request.workOrderId() != null
                && defect.signum() > 0) {
            workOrderPort.markRework(request.workOrderId());
            workOrderStatus = "REWORK";
            WorkOrderPort.WorkOrderRef parent = workOrderPort.workOrder(request.workOrderId());
            if (parent == null) {
                throw new BizException("生产工单不存在");
            }
            long productId = parent.productId();
            // 不良先入台账，处置方式由质量在「不良处置」里决定，避免不合格品凭空消失
            defectWriter.write(DefectRequest.of(request.workOrderId(), null, null, productId, defect,
                    "FINAL_QC", request.remark() == null ? "完工检不合格" : request.remark()));
            reworkWorkOrderId = reworkService.createRework(new ReworkRequest(productId, defect,
                    request.workOrderId(), parent.salesOrderId(), parent.salesOrderItemId(), parent.planEnd(),
                    "完工检不合格自动返修", request.reworkFromSeq()));
            reworkWorkNo = workOrderPort.workNo(reworkWorkOrderId);
        }
        if ("INCOMING".equals(request.type()) && "FAIL".equals(result) && request.productId() != null
                && defect.signum() > 0) {
            defectWriter.write(DefectRequest.of(null, null, null, request.productId(), defect, "INCOMING",
                    request.remark() == null ? "来料检验不合格" : request.remark()));
        }
        // 工序检验判不合格：不良挂到工序上，处置返修才能从这道工序起做
        if ("PROCESS".equals(request.type()) && "FAIL".equals(result) && defect.signum() > 0) {
            String reason = request.remark() == null || request.remark().isBlank()
                    ? (operationSeq == null ? "工序检验不合格" : "第 " + operationSeq + " 道工序检验不合格")
                    : request.remark();
            defectWriter.write(DefectRequest.of(request.workOrderId(), operationId, operationProcessId,
                    request.productId(), defect, "PROCESS_QC", reason));
        }
        if ("INCOMING".equals(request.type()) && "PASS".equals(result) && request.inboundOrderId() != null) {
            warehouseService.completeInbound(request.inboundOrderId(), inspectionNo);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("inspectionNo", inspectionNo);
        response.put("result", result);
        response.put("workOrderStatus", workOrderStatus);
        response.put("reworkWorkNo", reworkWorkNo);
        response.put("reworkWorkOrderId", reworkWorkOrderId);
        response.put("operationId", operationId);
        response.put("operationSeq", operationSeq);
        response.put("samplePlan", samplePlan.plan());
        response.put("sampleQty", samplePlan.sampleQty());
        response.put("acceptQty", samplePlan.acceptQty());
        response.put("rejectQty", samplePlan.rejectQty());
        response.put("itemCount", itemResults.size());
        operLogService.touch("INSPECTION", id, inspectionNo, "检验单 " + inspectionNo + " 判定为"
                + ("PASS".equals(result) ? "合格" : "不合格")
                + (defect != null && defect.signum() > 0 ? "，不良 " + defect.stripTrailingZeros().toPlainString() : ""));
        operLogService.action("判定");
        return response;
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.id, i.inspection_no as "inspectionNo", i.type, i.ref_type as "refType",
                       i.ref_no as "refNo", i.work_order_id as "workOrderId",
                       i.product_id as "productId",
                       i.inspect_qty as "inspectQty", i.defect_qty as "defectQty", i.result,
                       round((i.inspect_qty - i.defect_qty) * 100.0 / nullif(i.inspect_qty, 0), 2) as "passRate",
                       i.inspector, i.remark, i.operation_id as "operationId", i.operation_seq as "operationSeq",
                       i.sample_plan as "samplePlan", i.sample_qty as "sampleQty",
                       i.aql, i.accept_qty as "acceptQty", i.reject_qty as "rejectQty",
                       to_char(i.inspected_at, 'YYYY-MM-DD HH24:MI') as "inspectedAt"
                from inspection i
                where i.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2019, "检验单不存在");
        }
        MasterDataViews.fillProductNames(masterData, rows);
        fillOperationNames(rows);
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        // 工单号跟着工单走，产品与工序名称已经由各自的模块补齐
        Object workOrderId = detail.get("workOrderId");
        detail.put("workNo", workOrderId == null
                ? null : workOrderPort.workNo(((Number) workOrderId).longValue()));
        detail.put("items", inspectionItemService.resultsOf(id));
        return detail;
    }

    /** 不良项 Top：哪些检验项最常判不合格，用来反推工艺与供应商问题 */
    public List<Map<String, Object>> itemDefectTop(int days) {
        int span = Math.min(Math.max(days, 1), 365);
        return jdbcTemplate.queryForList("""
                select r.item_name as "itemName",
                       count(*) as "total",
                       count(*) filter (where r.result = 'FAIL') as "failCount",
                       coalesce(sum(r.defect_qty) filter (where r.result = 'FAIL'), 0) as "defectQty",
                       round(count(*) filter (where r.result = 'FAIL') * 100.0 / nullif(count(*), 0), 1) as "failRate"
                from inspection_item_result r
                join inspection i on i.id = r.inspection_id
                where i.created_at >= now() - (? || ' day')::interval
                group by r.item_name
                having count(*) filter (where r.result = 'FAIL') > 0
                order by "failCount" desc, "defectQty" desc
                limit 10
                """, span);
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select count(*) as "total",
                       coalesce(sum(case when result = 'PASS' then 1 else 0 end), 0) as "passCount",
                       coalesce(sum(defect_qty), 0) as "defectQty",
                       coalesce(sum(inspect_qty), 0) as "inspectQty"
                from inspection
                where created_at >= now() - interval '30 day'
                """);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        BigDecimal inspectQty = row.get("inspectQty") == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(row.get("inspectQty")));
        BigDecimal defectQty = row.get("defectQty") == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(row.get("defectQty")));
        // 检验合格率：30 天内所有检验单（来料 / 过程 / 完工）的合格数量占比
        BigDecimal rate = inspectQty.signum() == 0 ? BigDecimal.valueOf(100)
                : inspectQty.subtract(defectQty).multiply(BigDecimal.valueOf(100))
                .divide(inspectQty, 2, java.math.RoundingMode.HALF_UP);
        result.put("rate", rate);
        result.put("inspectQty", inspectQty);
        result.put("defectQty", defectQty);
        result.put("total", row.get("total"));

        // 一次合格率：报工口径，工序报上来多少、其中多少一次就合格（最能反映制造水平）
        Map<String, Object> report = jdbcTemplate.queryForList("""
                select coalesce(sum(qty), 0) as "qty", coalesce(sum(qualified_qty), 0) as "qualified",
                       coalesce(sum(defect_qty), 0) as "defect"
                from work_report where reported_at >= now() - interval '30 day'
                """).get(0);
        BigDecimal reportQty = new BigDecimal(String.valueOf(report.get("qty")));
        BigDecimal reportQualified = new BigDecimal(String.valueOf(report.get("qualified")));
        result.put("reportQty", reportQty);
        result.put("reportQualifiedQty", reportQualified);
        result.put("reportDefectQty", new BigDecimal(String.valueOf(report.get("defect"))));
        result.put("firstPassRate", reportQty.signum() == 0 ? BigDecimal.valueOf(100)
                : reportQualified.multiply(BigDecimal.valueOf(100))
                .divide(reportQty, 2, java.math.RoundingMode.HALF_UP));

        // 完工检验合格率：只看 FINAL，衡量交付前的守门效果
        Map<String, Object> fin = jdbcTemplate.queryForList("""
                select coalesce(sum(inspect_qty), 0) as "qty", coalesce(sum(defect_qty), 0) as "defect"
                from inspection
                where type = 'FINAL' and created_at >= now() - interval '30 day'
                """).get(0);
        BigDecimal finQty = new BigDecimal(String.valueOf(fin.get("qty")));
        BigDecimal finDefect = new BigDecimal(String.valueOf(fin.get("defect")));
        result.put("finalRate", finQty.signum() == 0 ? BigDecimal.valueOf(100)
                : finQty.subtract(finDefect).multiply(BigDecimal.valueOf(100))
                .divide(finQty, 2, java.math.RoundingMode.HALF_UP));

        // 不良处置口径：待处置 / 各处置方式笔数与数量
        Map<String, Object> defect = jdbcTemplate.queryForList("""
                select count(*) filter (where status = 'PENDING') as "pending",
                       coalesce(sum(qty) filter (where status = 'PENDING'), 0) as "pendingQty",
                       count(*) filter (where disposition = 'REWORK') as "reworkQty",
                       count(*) filter (where disposition = 'CONCESSION') as "concessionQty",
                       count(*) filter (where disposition = 'SCRAP') as "scrapQty",
                       count(*) filter (where disposition = 'RETURN_SUPPLIER') as "returnSupplierQty"
                from defect_record
                """).get(0);
        result.putAll(defect);
        return result;
    }
}
