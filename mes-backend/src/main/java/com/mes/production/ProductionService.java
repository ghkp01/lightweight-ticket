package com.mes.production;

import com.mes.andon.api.AndonPort;
import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.cost.api.CostPort;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.BomLineInfo;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.platform.customfield.CustomFieldService;
import com.mes.platform.scope.OrgScopeService;
import com.mes.production.api.WorkOrderPort;
import com.mes.quality.api.DefectPort;
import com.mes.quality.api.DefectRequest;
import com.mes.quality.api.ProcessInspectionPort;
import com.mes.security.CurrentUser;
import com.mes.system.api.UserSkillPort;
import com.mes.task.api.TaskDraft;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProductionService implements WorkOrderPort {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final DefectPort defectWriter;
    private final NoticePort notice;
    private final UserSkillPort userSkillService;
    private final WorkOrderIssueService issueService;
    private final WorkOrderUnitService unitService;
    /**
     * 安灯与工单是双向依赖：安灯要工单号与待领行（查询），工单下达要报安灯（命令）。
     * 用 {@link ObjectProvider} 延迟到调用时取，构造期不成环。
     */
    private final ObjectProvider<AndonPort> andonService;
    private final CostPort costService;
    private final OrgScopeService orgScopeService;
    private final MasterDataPort masterData;
    private final ProcessInspectionPort processInspections;
    private final OutsourceService outsourceService;
    private final TaskPort taskService;
    private final CustomFieldService customFieldService;

    public ProductionService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                             InventoryPort inventoryService, DefectPort defectWriter,
                             NoticePort notice,
                             UserSkillPort userSkillService,
                             WorkOrderIssueService issueService,
                             WorkOrderUnitService unitService,
                             ObjectProvider<AndonPort> andonService,
                             CostPort costService,
                             OrgScopeService orgScopeService,
                             MasterDataPort masterData,
                             ProcessInspectionPort processInspections,
                             OutsourceService outsourceService,
                             TaskPort taskService,
                             CustomFieldService customFieldService) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.defectWriter = defectWriter;
        this.notice = notice;
        this.userSkillService = userSkillService;
        this.issueService = issueService;
        this.unitService = unitService;
        this.andonService = andonService;
        this.costService = costService;
        this.orgScopeService = orgScopeService;
        this.masterData = masterData;
        this.processInspections = processInspections;
        this.outsourceService = outsourceService;
        this.taskService = taskService;
        this.customFieldService = customFieldService;
    }

    /**
     * 手工建单的入参。
     *
     * <p>{@code priority} / {@code traceMode} / {@code remark} 都是后补的：
     * 原来只有产品与两个日期，计划想标个加急、写句备注，或者给单件追溯的单换追溯方式，
     * 都得建完再进详情改。{@code traceMode} 传空表示跟随产品默认值。</p>
     *
     * <p>{@code assignments} 让建单时就把工序派下去：按工序序号指定报工人与报工权限，
     * 建完不用再进详情逐道派。没写到的工序保持「未派工 / 所有人可报」。</p>
     */
    public record WorkOrderRequest(Long productId, BigDecimal planQty, LocalDate planStart, LocalDate planEnd,
                                   String priority, String traceMode, String remark,
                                   List<OperationAssignment> assignments,
                                   Map<String, Object> customFields) {

        /** 兼容老调用：只给产品、数量与计划日期 */
        public WorkOrderRequest(Long productId, BigDecimal planQty, LocalDate planStart, LocalDate planEnd) {
            this(productId, planQty, planStart, planEnd, null, null, null, null, null);
        }

        /** 兼容：带优先级 / 追溯方式 / 备注但不派工 */
        public WorkOrderRequest(Long productId, BigDecimal planQty, LocalDate planStart, LocalDate planEnd,
                                String priority, String traceMode, String remark) {
            this(productId, planQty, planStart, planEnd, priority, traceMode, remark, null, null);
        }

        /** 兼容：带派工，不带自定义字段 */
        public WorkOrderRequest(Long productId, BigDecimal planQty, LocalDate planStart, LocalDate planEnd,
                                String priority, String traceMode, String remark,
                                List<OperationAssignment> assignments) {
            this(productId, planQty, planStart, planEnd, priority, traceMode, remark, assignments, null);
        }
    }

    /**
     * 建单时顺带派工：按工序序号指定报工人、报工权限与分配数量。
     *
     * <p>同一道工序可以给多行（多人分配）：每人一条，各自带 {@code quotaQty}。
     * 不填数量时默认整道工序的计划量（单人常见）。</p>
     *
     * @param reportPolicy ANY 所有人可报 / ASSIGNED 仅名单内的人可报；空按 ANY
     */
    public record OperationAssignment(int seq, Long userId, String reportPolicy, BigDecimal quotaQty) {

        public OperationAssignment(int seq, Long userId, String reportPolicy) {
            this(seq, userId, reportPolicy, null);
        }
    }

    /** 派工名单里的一行：谁、分多少 */
    public record AssignmentItem(Long userId, BigDecimal quotaQty) {
    }

    /** 多人派工入参：整体替换这道工序的分配名单；items 为空表示清空派工 */
    public record AssignmentsRequest(List<AssignmentItem> items, String reportPolicy) {
    }

    public record ReportRequest(Long operationId, BigDecimal qty, BigDecimal qualifiedQty, BigDecimal defectQty,
                                String worker, String remark,
                                String inspectionResult, String inspectionRemark) {
    }

    /** 派工入参：userId 与 worker 二选一（老客户端传名字），reportPolicy 可选（ANY / ASSIGNED） */
    public record AssignRequest(Long userId, String worker, String reportPolicy) {

        public AssignRequest(Long userId, String worker) {
            this(userId, worker, null);
        }
    }

    public record IssueItemRequest(Long productId, BigDecimal qty, Long warehouseId,
                                   String issuer, String team, String source) {
    }

    public record BomLineRequest(Long materialId, BigDecimal planQty, Integer operationSeq,
                                 String remark, String reason) {
    }

    public PageResult list(String keyword, String status, int page, int size) {
        return list(keyword, status, page, size, false);
    }

    /**
     * @param onlyShortage 只看缺料：待领量大于 0 的工单，班长打开就能看到哪些单还缺料
     */
    public PageResult list(String keyword, String status, int page, int size, boolean onlyShortage) {
        return list(keyword, status, page, size, onlyShortage, null, null);
    }

    /**
     * @param onlyShortage 只看缺料：待领量大于 0 的工单，班长打开就能看到哪些单还缺料
     */
    public PageResult list(String keyword, String status, int page, int size, boolean onlyShortage,
                           String customFieldKey, String customFieldValue) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where (w.work_no ilike ? or p.name ilike ?) ");
        args.add(like);
        args.add(like);
        if (status != null && !status.isBlank()) {
            where.append(" and w.status = ? ");
            args.add(status);
        }
        if (onlyShortage) {
            where.append("""
                     and exists (select 1 from work_order_bom b
                                 where b.work_order_id = w.id and b.plan_qty > b.issued_qty)
                    """);
        }
        if (customFieldKey != null && !customFieldKey.isBlank()
                && customFieldValue != null && !customFieldValue.isBlank()) {
            CustomFieldService.FormField definition =
                    customFieldService.formDefinition("work_order", customFieldKey);
            if (definition == null) {
                throw new BizException(1002, "自定义字段不存在、已停用或无权查看：" + customFieldKey);
            }
            String value = customFieldValue.trim();
            switch (definition.fieldType()) {
                case "NUMBER" -> {
                    try {
                        new BigDecimal(value);
                    } catch (NumberFormatException error) {
                        throw new BizException(1002, "自定义字段「" + definition.label() + "」筛选值必须是数字");
                    }
                    where.append(" and (w.ext_data ->> ?)::numeric = ?::numeric ");
                    args.add(definition.fieldKey());
                    args.add(value);
                }
                case "SWITCH" -> {
                    String normalized = "true".equalsIgnoreCase(value) || "1".equals(value)
                            || "是".equals(value) ? "true"
                            : ("false".equalsIgnoreCase(value) || "0".equals(value) || "否".equals(value)
                            ? "false" : null);
                    if (normalized == null) {
                        throw new BizException(1002, "自定义字段「" + definition.label() + "」筛选值只能是是/否");
                    }
                    where.append(" and (w.ext_data ->> ?)::boolean = ?::boolean ");
                    args.add(definition.fieldKey());
                    args.add(normalized);
                }
                default -> {
                    where.append(" and w.ext_data ->> ? = ? ");
                    args.add(definition.fieldKey());
                    args.add(value);
                }
            }
        }
        // 多工厂隔离：只开在本工厂名下，历史未归属的工单不隐藏
        Long factoryFilter = orgScopeService.listFilter();
        if (factoryFilter != null) {
            where.append(" and (w.factory_org_id = ? or w.factory_org_id is null) ");
            args.add(factoryFilter);
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from work_order w join rm_product p on p.id = w.product_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select w.id, w.work_no as "workNo", w.product_id as "productId",
                       p.code as "productCode", p.name as "productName",
                       p.spec, p.unit, w.plan_qty as "planQty", w.done_qty as "doneQty",
                       p.serial_managed as "serialManaged", w.trace_mode as "traceMode",
                       w.qualified_qty as "qualifiedQty", w.status, w.priority,
                       w.schedule_date as "scheduleDate", w.schedule_seq as "scheduleSeq",
                       w.work_type as "workType",
                       w.parent_work_order_id as "parentWorkOrderId", so.order_no as "orderNo",
                       w.plan_start as "planStart", w.plan_end as "planEnd",
                       coalesce((select sum(b.plan_qty) from work_order_bom b
                                 where b.work_order_id = w.id and b.source <> 'SUBSTITUTE'), 0)
                           as "materialNeedQty",
                       coalesce((select sum(b.plan_qty - b.issued_qty) from work_order_bom b
                                 where b.work_order_id = w.id and b.plan_qty > b.issued_qty
                                   and b.source <> 'SUBSTITUTE'), 0)
                           as "materialPendingQty",
                       (select count(*) from work_order_bom b
                         where b.work_order_id = w.id and b.plan_qty > b.issued_qty
                           and b.source <> 'SUBSTITUTE') as "shortageLines",
                       to_char(w.created_at, 'YYYY-MM-DD') as "createdAt",
                       w.ext_data::text as "extData"
                from work_order w
                join rm_product p on p.id = w.product_id
                left join rm_sales_order so on so.id = w.sales_order_id
                """ + where + " order by w.id desc limit ? offset ?", pageArgs.toArray());
        List<CustomFieldService.FormField> customFields =
                customFieldService.formDefinitions("work_order");
        for (Map<String, Object> record : records) {
            Object extData = record.remove("extData");
            record.put("customFields", customFieldService.visibleJson(customFields,
                    extData == null ? null : String.valueOf(extData)));
        }
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    /** 引用选择专用：工单下拉只返回编号、产品与状态，status 支持逗号分隔多个 */
    public List<Map<String, Object>> options(String keyword, String status, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>(List.of(like, like, like));
        StringBuilder where = new StringBuilder("""
                 where (w.work_no ilike ? or p.code ilike ? or p.name ilike ?)
                """);
        List<String> statuses = splitStatus(status);
        if (!statuses.isEmpty()) {
            where.append(" and w.status in (")
                    .append(String.join(",", java.util.Collections.nCopies(statuses.size(), "?")))
                    .append(") ");
            args.addAll(statuses);
        }
        Long factoryFilter = orgScopeService.listFilter();
        if (factoryFilter != null) {
            where.append(" and (w.factory_org_id = ? or w.factory_org_id is null) ");
            args.add(factoryFilter);
        }
        args.add(size);
        return jdbcTemplate.queryForList("""
                select w.id, w.work_no as "workNo", w.product_id as "productId",
                       p.code as "productCode", p.name as "productName",
                       w.plan_qty as "planQty", w.status
                from work_order w
                join rm_product p on p.id = w.product_id
                """ + where + " order by w.id desc limit ?", args.toArray());
    }

    private static List<String> splitStatus(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(status.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select w.id, w.work_no as "workNo", w.sales_order_id as "salesOrderId", so.order_no as "orderNo",
                       w.product_id as "productId", p.code as "productCode", p.name as "productName", p.spec, p.unit,
                       w.plan_qty as "planQty", w.done_qty as "doneQty", w.qualified_qty as "qualifiedQty",
                       p.serial_managed as "serialManaged", w.trace_mode as "traceMode",
                       w.status, w.priority, w.remark,
                       w.schedule_date as "scheduleDate", w.schedule_seq as "scheduleSeq",
                       w.work_type as "workType", w.parent_work_order_id as "parentWorkOrderId",
                       pw.work_no as "parentWorkNo", w.rework_from_seq as "reworkFromSeq",
                       so.delivery_date as "deliveryDate",
                       w.plan_start as "planStart", w.plan_end as "planEnd",
                       w.cancel_reason as "cancelReason",
                       to_char(w.cancelled_at, 'YYYY-MM-DD HH24:MI') as "cancelledAt",
                       w.cancelled_by as "cancelledBy",
                       to_char(w.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       w.ext_data::text as "extData"
                from work_order w
                join rm_product p on p.id = w.product_id
                left join rm_sales_order so on so.id = w.sales_order_id
                left join work_order pw on pw.id = w.parent_work_order_id
                where w.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException("生产工单不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        Object detailExt = detail.remove("extData");
        detail.put("customFields", customFieldService.visibleJson("work_order",
                detailExt == null ? null : String.valueOf(detailExt)));
        detail.put("predecessors", jdbcTemplate.queryForList("""
                select w.id, w.work_no as "workNo", w.status, w.plan_qty as "planQty",
                       p.code as "productCode", p.name as "productName"
                from work_order_dependency d
                join work_order w on w.id = d.predecessor_id
                join rm_product p on p.id = w.product_id
                where d.successor_id = ? order by w.id
                """, id));
        detail.put("successors", jdbcTemplate.queryForList("""
                select w.id, w.work_no as "workNo", w.status, w.plan_qty as "planQty",
                       p.code as "productCode", p.name as "productName"
                from work_order_dependency d
                join work_order w on w.id = d.successor_id
                join rm_product p on p.id = w.product_id
                where d.predecessor_id = ? order by w.id
                """, id));
        String workNo = String.valueOf(detail.get("workNo"));
        List<Map<String, Object>> operations = jdbcTemplate.queryForList("""
                select o.id, o.seq, o.name, o.plan_qty as "planQty", o.done_qty as "doneQty", o.status,
                       o.assigned_worker as "assignedWorker", o.assigned_user_id as "assignedUserId", o.unit_price as "unitPrice",
                       o.report_policy as "reportPolicy",
                       o.process_id as "processId", o.need_inspection as "needInspection",
                       coalesce(o.inspection_mode, 'NONE') as "inspectionMode",
                       o.outsource_status as "outsourceStatus",
                       o.outsource_supplier_id as "outsourceSupplierId",
                       o.outsource_price as "outsourcePrice",
                       o.outsource_expected_at as "outsourceExpectedAt",
                       o.outsource_received_qty as "outsourceReceivedQty",
                       o.outsource_defect_qty as "outsourceDefectQty",
                       to_char(o.outsource_sent_at, 'MM-DD HH24:MI') as "outsourceSentAt",
                       s.name as "outsourceSupplierName",
                       coalesce((select sum(b.plan_qty - b.issued_qty) from work_order_bom b
                                 where b.work_order_id = o.work_order_id and b.operation_seq = o.seq
                                   and b.plan_qty > b.issued_qty), 0) as "materialPendingQty",
                       case when coalesce((select sum(b.plan_qty - b.issued_qty) from work_order_bom b
                                 where b.work_order_id = o.work_order_id and b.operation_seq = o.seq
                                   and b.plan_qty > b.issued_qty), 0) = 0
                            then true else false end as "materialReady",
                       exists(select 1 from work_order_bom b
                              where b.work_order_id = o.work_order_id and b.operation_seq = o.seq
                                and b.source <> 'SUBSTITUTE') as "materialRequired"
                from work_order_operation o
                left join rm_supplier s on s.id = o.outsource_supplier_id
                where o.work_order_id = ? order by o.seq
                """, id);
        // 多人分配名单：一次查完再按工序分组，避免每道工序一次查询
        Map<Long, List<Map<String, Object>>> assignmentMap = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select a.operation_id as "operationId", a.user_id as "userId",
                       a.worker_name as "workerName", a.quota_qty as "quotaQty",
                       a.reported_qty as "reportedQty", (a.quota_qty - a.reported_qty) as "remainingQty",
                       u.username
                from work_order_operation_assignment a
                join sys_user u on u.id = a.user_id
                join work_order_operation o on o.id = a.operation_id
                where o.work_order_id = ?
                order by a.operation_id, a.id
                """, id)) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("quotaQty", decimal(row.get("quotaQty")));
            item.put("reportedQty", decimal(row.get("reportedQty")));
            item.put("remainingQty", decimal(row.get("remainingQty")));
            assignmentMap.computeIfAbsent(((Number) row.get("operationId")).longValue(), key -> new ArrayList<>())
                    .add(item);
        }
        for (Map<String, Object> operation : operations) {
            operation.put("assignments",
                    assignmentMap.getOrDefault(((Number) operation.get("id")).longValue(), List.of()));
        }
        detail.put("operations", operations);
        detail.put("outsourceOrders", outsourceService.listByWorkOrder(id));
        detail.put("reports", jdbcTemplate.queryForList("""
                select r.id, o.name as "operationName", r.qty, r.qualified_qty as "qualifiedQty",
                       r.defect_qty as "defectQty", r.worker, r.remark,
                       to_char(r.reported_at, 'MM-DD HH24:MI') as "reportedAt"
                from work_report r join work_order_operation o on o.id = r.operation_id
                where r.work_order_id = ? order by r.id desc
                """, id));
        detail.put("bom", workOrderBom(id, workNo));
        detail.put("bomChanges", bomChanges(id));
        Integer issued = jdbcTemplate.queryForObject("""
                select count(*) from rm_stock_txn where ref_type = 'WORK_ORDER' and ref_no = ? and txn_type = 'ISSUE'
                """, Integer.class, workNo);
        detail.put("issued", issued != null && issued > 0);
        detail.put("issues", issueService.listIssues(id));
        detail.put("returns", issueService.listReturns(id));
        return detail;
    }

    /** 工单 BOM：来源为工单快照，允许非标调整，已领数量按库存流水实时汇总。 */
    private List<Map<String, Object>> workOrderBom(long workOrderId, String workNo) {
        List<Map<String, Object>> bom = jdbcTemplate.queryForList("""
                select b.id as "bomId", b.material_id as "materialId", m.code as "materialCode",
                       m.name as "materialName", m.spec, m.unit,
                       b.plan_qty as "needQty", b.operation_seq as "operationSeq",
                       b.substitute_group as "substituteGroup", b.source, b.remark,
                       b.substitute_for as "substituteFor",
                       s.material_id as "substituteMaterialId",
                       sm.code as "substituteMaterialCode",
                       sm.name as "substituteMaterialName",
                       coalesce((select -sum(t.qty) from rm_stock_txn t
                                 where t.txn_type in ('ISSUE', 'OUTSOURCE')
                                   and t.ref_no = ? and t.product_id = b.material_id), 0)
                       + coalesce((select -sum(t.qty) from rm_stock_txn t
                                 where t.txn_type in ('ISSUE', 'OUTSOURCE')
                                   and t.ref_no = ? and t.product_id = s.material_id), 0)
                           as "issuedQty",
                       coalesce((select -sum(t.qty) from rm_stock_txn t
                                 where t.txn_type = 'OUTSOURCE'
                                   and t.ref_no = ? and t.product_id = b.material_id), 0)
                           as "outsourceQty",
                       coalesce((select sum(t.qty) from rm_stock_txn t
                                 where t.txn_type = 'RETURN_MAT' and t.ref_no = ? and t.product_id = b.material_id), 0)
                       + coalesce((select sum(t.qty) from rm_stock_txn t
                                 where t.txn_type = 'RETURN_MAT' and t.ref_no = ? and t.product_id = s.material_id), 0)
                           as "returnedQty"
                from work_order_bom b
                join rm_product m on m.id = b.material_id
                left join work_order_bom s
                       on s.work_order_id = b.work_order_id and s.substitute_for = b.material_id
                left join rm_product sm on sm.id = s.material_id
                where b.work_order_id = ? order by b.operation_seq nulls last, b.id
                """, workNo, workNo, workNo, workNo, workNo, workOrderId);
        // 替代料结存走库存契约：一行 BOM 最多带一个替代料，收集起来一次批量取
        Map<Long, BigDecimal> substituteStocks = inventoryService.totalStocks(bom.stream()
                .map(line -> line.get("substituteMaterialId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .toList());
        for (Map<String, Object> line : bom) {
            Object substituteId = line.get("substituteMaterialId");
            if (substituteId != null) {
                line.put("substituteStock", substituteStocks
                        .getOrDefault(((Number) substituteId).longValue(), BigDecimal.ZERO));
            }
            BigDecimal issuedQty = decimal(line.get("issuedQty"));
            // 外协发出的料已经离开本厂仓库，算「已领」，但不在退料范围内（料在外协方，随件收回）
            BigDecimal outsourceQty = decimal(line.get("outsourceQty"));
            BigDecimal returned = decimal(line.get("returnedQty"));
            BigDecimal returnable = issuedQty.subtract(outsourceQty).subtract(returned);
            if (returnable.signum() < 0) {
                returnable = BigDecimal.ZERO;
            }
            // 领多了的量：已领超过需求的部分，退料弹窗按这个值一键填充
            BigDecimal over = issuedQty.subtract(decimal(line.get("needQty")));
            line.put("issuedQty", issuedQty);
            line.put("outsourceQty", outsourceQty);
            line.put("returnedQty", returned);
            line.put("returnableQty", returnable);
            line.put("suggestReturnQty", over.signum() > 0 ? over.min(returnable) : BigDecimal.ZERO);
        }
        return bom;
    }

    private List<Map<String, Object>> bomChangeRows(long workOrderId) {
        return jdbcTemplate.queryForList("""
                select c.id, c.action, c.material_name as "materialName", c.old_qty as "oldQty",
                       c.new_qty as "newQty", c.reason, c.changed_by as "changedBy",
                       to_char(c.changed_at, 'MM-DD HH24:MI') as "changedAt"
                from work_order_bom_change c
                where c.work_order_id = ? order by c.id desc limit 20
                """, workOrderId);
    }

    public Map<String, Object> detailByNo(String workNo) {
        if (workNo == null || workNo.isBlank()) {
            throw new BizException(1002, "请提供工单号");
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from work_order where work_no = ?", Long.class, workNo.trim());
        if (ids.isEmpty()) {
            throw new BizException("生产工单不存在");
        }
        return detail(ids.get(0));
    }

    @Transactional
    public long create(WorkOrderRequest request) {
        if (request.productId() == null || request.planQty() == null || request.planQty().signum() <= 0) {
            throw new BizException(1002, "请选择产品并填写大于 0 的计划数量");
        }
        if (!masterData.productExists(request.productId())) {
            throw new BizException(1002, "产品不存在");
        }
        String workNo = codeGenerator.next("WO");
        Long factoryOrgId = orgScopeService.currentFactoryOrgId();
        if (factoryOrgId == null) {
            factoryOrgId = orgScopeService.defaultFactoryOrgId();
        }
        // 追溯方式在建单时从产品快照过来：之后改产品不影响这张单，下达前计划可以在工单上改
        String productTrace = masterData.product(request.productId())
                .map(info -> info.traceMode() == null ? "NONE" : info.traceMode())
                .orElse("NONE");
        String traceMode = resolveTraceMode(request.traceMode(), productTrace);
        String priority = resolvePriority(request.priority());
        if ("SERIAL".equals(traceMode) && request.planQty().stripTrailingZeros().scale() > 0) {
            throw new BizException(1002, "单件追溯要求计划数量是整数，当前为 "
                    + request.planQty().stripTrailingZeros().toPlainString());
        }
        String remark = request.remark() == null ? null : request.remark().trim();
        if (remark != null && remark.isEmpty()) {
            remark = null;
        }
        Long id = jdbcTemplate.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status, plan_start, plan_end,
                                        factory_org_id, trace_mode, priority, remark)
                values (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, workNo, request.productId(), request.planQty(),
                request.planStart() == null ? LocalDate.now() : request.planStart(),
                request.planEnd() == null ? LocalDate.now().plusDays(7) : request.planEnd(),
                factoryOrgId, traceMode, priority, remark);
        String extJson = request.customFields() == null ? "{}"
                : customFieldService.mergeJson("work_order", "{}", request.customFields(), true);
        jdbcTemplate.update("update work_order set ext_data = ?::jsonb where id = ?", extJson, id);
        createOperations(id, request.productId(), request.planQty());
        snapshotBom(id, request.productId(), request.planQty());
        applyAssignments(id, request.assignments());
        return id;
    }

    /**
     * 建单时就派工：按工序序号写报工人与报工权限。
     *
     * <p>走 {@link #assignOperation} 而不是自己 update：持证校验、用户启用状态、
     * 姓名反查这些规则只该有一份。设了「仅派工人员可报」却没选人时直接拒，
     * 免得建出一张谁都不能报的单。</p>
     */
    private void applyAssignments(long workId, List<OperationAssignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return;
        }
        // 同一道工序可能有多行（多人分配），先按工序序号归组
        Map<Integer, List<OperationAssignment>> grouped = new LinkedHashMap<>();
        for (OperationAssignment item : assignments) {
            grouped.computeIfAbsent(item.seq(), key -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<Integer, List<OperationAssignment>> entry : grouped.entrySet()) {
            int seq = entry.getKey();
            List<OperationAssignment> rows = entry.getValue();
            OperationAssignment head = rows.get(0);
            boolean hasPolicy = head.reportPolicy() != null && !head.reportPolicy().isBlank();
            List<AssignmentItem> items = new ArrayList<>();
            for (OperationAssignment row : rows) {
                if (row.userId() != null) {
                    items.add(new AssignmentItem(row.userId(), row.quotaQty()));
                }
            }
            if (items.isEmpty() && !hasPolicy) {
                continue;
            }
            Long operationId = operationIdOf(workId, seq);
            if (operationId == null) {
                throw new BizException(1002, "第 " + seq + " 道工序不存在，没法派工");
            }
            String policy = normalizeReportPolicy(head.reportPolicy());
            if (items.isEmpty() && "ASSIGNED".equals(policy)) {
                throw new BizException(1002, "第 " + seq + " 道工序设了「仅名单内可报」，要同时选报工人");
            }
            assignOperations(workId, operationId, items, policy);
        }
    }

    private Long operationIdOf(long workId, int seq) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from work_order_operation where work_order_id = ? and seq = ?", Long.class, workId, seq);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 追溯方式：请求优先，其次产品默认；非法值直接拒 */
    private String resolveTraceMode(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        String value = requested.trim().toUpperCase();
        if (!List.of("NONE", "SERIAL", "LOT").contains(value)) {
            throw new BizException(1002, "追溯方式只能是 NONE / SERIAL / LOT");
        }
        return value;
    }

    /** 工单优先级：NORMAL / URGENT / RUSH，空值按普通 */
    private String resolvePriority(String requested) {
        if (requested == null || requested.isBlank()) {
            return "NORMAL";
        }
        String value = requested.trim().toUpperCase();
        if (!List.of("NORMAL", "URGENT", "RUSH").contains(value)) {
            throw new BizException(1002, "优先级只能是 NORMAL、URGENT 或 RUSH");
        }
        return value;
    }

    /**
     * 改这张工单的追溯方式：只能在**下达前**改（下达那一刻会按它生成件码，之后就不该再变）。
     *
     * <p>为什么工单上还要能改：同一物料卖给不同客户，追溯要求常常不一样（客户指定单件追溯，
     * 或试制单要追、量产不要）。产品上的 trace_mode 只是默认值。</p>
     */
    @Transactional
    public Map<String, Object> setTraceMode(long id, String traceMode) {
        Map<String, Object> work = loadWork(id);
        String status = String.valueOf(work.get("status"));
        if (!"DRAFT".equals(status) && !"SCHEDULED".equals(status)) {
            throw new BizException(2041, "工单已下达，追溯方式不能再改（下达时已按原方式生成件码）");
        }
        String target = traceMode == null ? "" : traceMode.trim().toUpperCase();
        if (!List.of("NONE", "SERIAL", "LOT").contains(target)) {
            throw new BizException(1002, "追溯方式只能是 NONE / SERIAL / LOT");
        }
        if ("SERIAL".equals(target)) {
            BigDecimal plan = decimal(work.get("plan_qty"));
            if (plan.stripTrailingZeros().scale() > 0) {
                throw new BizException(1002, "单件追溯要求计划数量是整数，当前为 "
                        + plan.stripTrailingZeros().toPlainString());
            }
        }
        jdbcTemplate.update("""
                update work_order set trace_mode = ?, updated_at = now(), version = version + 1
                where id = ?
                """, target, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workOrderId", id);
        result.put("traceMode", target);
        return result;
    }

    @Transactional
    public void release(long id) {
        Map<String, Object> work = loadWork(id);
        if (!"SCHEDULED".equals(String.valueOf(work.get("status")))) {
            throw new BizException(2005, "请先完成排产再下达");
        }
        // 没有工艺路线的产品建单时工序是空的，这里拦住，免得下达后车间没有可报的工序
        Integer operations = jdbcTemplate.queryForObject(
                "select count(*) from work_order_operation where work_order_id = ?", Integer.class, id);
        if (operations == null || operations == 0) {
            throw new BizException(2014, "该工单还没有工序，请先在工单详情的工序页签添加工序");
        }
        // 半成品没完工，成品工单不能下达：先把前置工单做完，再放开后置工单
        List<String> dependencyBlockers = dependencyBlockers(id);
        if (!dependencyBlockers.isEmpty()) {
            throw new BizException(2005, "前置半成品工单还没完工，不能下达："
                    + String.join("、", dependencyBlockers));
        }
        jdbcTemplate.update("update work_order set status = 'RELEASED', updated_at = now() where id = ?", id);
        // 单件管理的产品在这里生成件码，车间才能打印标签并扫码流转
        unitService.generateForWorkOrder(id);
        String workNo = String.valueOf(work.get("work_no"));
        // 下达只代表「允许开工」，还有两件事得有人接手：计划派工、仓库发料
        taskService.open(TaskDraft.of(TaskTypes.WO_ASSIGN, "工单 " + workNo + " 待派工",
                "把工序分到人并设置报工权限，工人才能开工报工",
                TaskTypes.REF_WORK_ORDER, id, workNo, "PLANNER"));
        taskService.open(TaskDraft.of(TaskTypes.WO_PICK, "工单 " + workNo + " 待领料",
                "按 BOM 发料，料不到位车间开不了工",
                TaskTypes.REF_WORK_ORDER, id, workNo, "WAREHOUSE"));
        // 下达时料没领齐就自动报物料安灯，并把消息推给仓库与计划，不用车间再喊
        if (andonService.getObject().raiseMaterialShortage(id)) {
            notice.pushToRoles(List.of("WAREHOUSE", "PLANNER"), NoticeMessage.of("ALERT",
                    "工单 " + workNo + " 物料库存不足",
                    "下达后发现物料库存盖不住待领量，已自动报物料安灯，请安排采购或确认缺口。",
                    "WORK_ORDER", workNo));
        }
    }

    /** 整单领料：把工单所有待领物料一次领完，生成一张领料单 */
    @Transactional
    public void issue(long id) {
        issueService.issueAll(id);
        syncPickTask(id);
    }

    /**
     * 按工序领料：只领该工序的待领物料，主料不足时同样自动带替代料。
     */
    @Transactional
    public Map<String, Object> issueByOperation(long id, int operationSeq) {
        Map<String, Object> result = issueService.issueByOperation(id, operationSeq);
        syncPickTask(id);
        return result;
    }

    /**
     * 非标 BOM 与标准 BOM 的差异：新增 / 删除 / 改量一目了然。
     */
    public List<Map<String, Object>> bomDiff(long id) {
        Map<String, Object> work = loadWork(id);
        long productId = ((Number) work.get("product_id")).longValue();
        BigDecimal planQty = decimal(work.get("plan_qty"));
        String workNo = String.valueOf(work.get("work_no"));
        // 标准需求口径统一走「当前生效 BOM」：单耗 × 计划量 × (1 + 损耗率)
        BigDecimal loss = masterData.product(productId).map(ProductInfo::lossRate).orElse(BigDecimal.ZERO);
        BigDecimal factor = planQty.multiply(BigDecimal.ONE.add(loss == null ? BigDecimal.ZERO : loss));
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
        for (BomLineInfo bomLine : masterData.activeBomLines(productId)) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("materialId", bomLine.materialId());
            line.put("standardQty", bomLine.qtyPerUnit().multiply(factor).setScale(4, RoundingMode.HALF_UP));
            line.put("currentQty", BigDecimal.ZERO);
            line.put("source", "STANDARD");
            merged.put(bomLine.materialId(), line);
        }
        List<Map<String, Object>> current = jdbcTemplate.queryForList("""
                select wb.material_id as "materialId", wb.plan_qty as "currentQty", wb.source
                from work_order_bom wb
                where wb.work_order_id = ? and wb.source <> 'SUBSTITUTE'
                """, id);
        for (Map<String, Object> row : current) {
            long materialId = ((Number) row.get("materialId")).longValue();
            Map<String, Object> line = merged.computeIfAbsent(materialId, key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("materialId", key);
                created.put("standardQty", BigDecimal.ZERO);
                return created;
            });
            line.put("currentQty", decimal(row.get("currentQty")));
            line.put("source", row.get("source"));
        }
        // 物料编码/名称一次批量补齐，避免逐行查主数据
        Map<Long, ProductInfo> materials = masterData.products(merged.keySet());
        for (Map.Entry<Long, Map<String, Object>> entry : merged.entrySet()) {
            ProductInfo material = materials.get(entry.getKey());
            Map<String, Object> line = entry.getValue();
            line.put("materialCode", material == null ? null : material.code());
            line.put("materialName", material == null ? null : material.name());
            line.put("unit", material == null ? null : material.unit());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> line : merged.values()) {
            long materialId = ((Number) line.get("materialId")).longValue();
            BigDecimal std = decimal(line.get("standardQty"));
            BigDecimal cur = decimal(line.get("currentQty"));
            String changeType;
            if (std.signum() == 0) {
                changeType = "ADDED";
            } else if (cur.signum() == 0) {
                changeType = "REMOVED";
            } else if (std.compareTo(cur) == 0) {
                changeType = "SAME";
            } else {
                changeType = "CHANGED";
            }
            line.put("diffQty", cur.subtract(std));
            line.put("changeType", changeType);
            BigDecimal issuedQty = jdbcTemplate.queryForObject("""
                    select coalesce(-sum(t.qty), 0) from rm_stock_txn t
                    where t.txn_type in ('ISSUE', 'OUTSOURCE') and t.ref_no = ? and t.product_id = ?
                    """, BigDecimal.class, workNo, materialId);
            line.put("issuedQty", issuedQty == null ? BigDecimal.ZERO : issuedQty);
            result.add(line);
        }
        return result;
    }

    /**
     * 非标 BOM：在工单快照上新增或调整物料用量，不影响产品标准 BOM，其他工单不受影响。
     */
    @Transactional
    public Map<String, Object> saveBomLine(long id, BomLineRequest request) {
        Map<String, Object> work = loadWork(id);
        assertBomEditable(work);
        if (request == null || request.materialId() == null) {
            throw new BizException(1002, "请选择物料");
        }
        if (request.planQty() == null || request.planQty().signum() <= 0) {
            throw new BizException(1002, "用量必须大于 0");
        }
        long productId = ((Number) work.get("product_id")).longValue();
        if (request.materialId() == productId) {
            throw new BizException(1002, "工单 BOM 不能引用产品自身");
        }
        String materialName = masterData.product(request.materialId())
                .map(ProductInfo::name)
                .orElseThrow(() -> new BizException(1002, "物料不存在"));
        BigDecimal newQty = request.planQty();
        String reason = blankToNull(request.reason());
        if (reason == null) {
            throw new BizException(1002, "请填写非标调整原因");
        }

        List<Map<String, Object>> exists = jdbcTemplate.queryForList("""
                select id, plan_qty, issued_qty, source from work_order_bom
                where work_order_id = ? and material_id = ?
                """, id, request.materialId());
        if (!exists.isEmpty() && "SUBSTITUTE".equals(String.valueOf(exists.get(0).get("source")))) {
            throw new BizException(1002, "该行是替代料，需求随主料生成，不能单独调整");
        }
        if (exists.isEmpty()) {
            jdbcTemplate.update("""
                    insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty,
                                                operation_seq, source, remark)
                    values (?, ?, ?, 0, ?, 'CUSTOM', ?)
                    """, id, request.materialId(), newQty,
                    request.operationSeq(), blankToNull(request.remark()));
            logBomChange(id, "ADD", request.materialId(), materialName, null, newQty, reason);
        } else {
            Map<String, Object> row = exists.get(0);
            BigDecimal oldQty = decimal(row.get("plan_qty"));
            BigDecimal issuedQty = decimal(row.get("issued_qty"));
            if (newQty.compareTo(issuedQty) < 0) {
                throw new BizException(2015, materialName + " 已领 "
                        + issuedQty.stripTrailingZeros().toPlainString()
                        + "，用量不能小于已领数量，请先退料");
            }
            jdbcTemplate.update("""
                    update work_order_bom set plan_qty = ?, operation_seq = ?, source = 'CUSTOM',
                           remark = coalesce(?, remark)
                    where id = ?
                    """, newQty, request.operationSeq(), blankToNull(request.remark()),
                    ((Number) row.get("id")).longValue());
            logBomChange(id, "UPDATE", request.materialId(), materialName, oldQty, newQty, reason);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materialId", request.materialId());
        result.put("materialName", materialName);
        result.put("planQty", newQty);
        BigDecimal issuedQty = jdbcTemplate.queryForObject("""
                select coalesce(issued_qty, 0) from work_order_bom
                where work_order_id = ? and material_id = ?
                """, BigDecimal.class, id, request.materialId());
        BigDecimal issued = issuedQty == null ? BigDecimal.ZERO : issuedQty;
        BigDecimal needIssue = newQty.subtract(issued);
        if (needIssue.signum() < 0) {
            needIssue = BigDecimal.ZERO;
        }
        result.put("issuedQty", issued);
        result.put("needIssueQty", needIssue);
        return result;
    }

    @Transactional
    public void deleteBomLine(long id, long bomId, String reason) {
        Map<String, Object> work = loadWork(id);
        assertBomEditable(work);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select b.id, b.material_id, b.plan_qty, b.issued_qty, b.source, m.name
                from work_order_bom b join rm_product m on m.id = b.material_id
                where b.id = ? and b.work_order_id = ?
                """, bomId, id);
        if (rows.isEmpty()) {
            throw new BizException("工单 BOM 行不存在");
        }
        Map<String, Object> row = rows.get(0);
        if ("SUBSTITUTE".equals(String.valueOf(row.get("source")))) {
            throw new BizException(1002, "替代料行不能单独删除，请调整主料的替代料设置");
        }
        BigDecimal issuedQty = decimal(row.get("issued_qty"));
        if (issuedQty.signum() > 0) {
            throw new BizException(2016, String.valueOf(row.get("name"))
                    + " 已领 " + issuedQty.stripTrailingZeros().toPlainString()
                    + "，不能删除，请先退料");
        }
        jdbcTemplate.update("delete from work_order_bom where id = ?", bomId);
        logBomChange(id, "REMOVE", ((Number) row.get("material_id")).longValue(),
                String.valueOf(row.get("name")), decimal(row.get("plan_qty")), null,
                blankToNull(reason) == null ? "非标删除" : reason.trim());
    }

    /** 恢复标准 BOM：清空工单自定义行，按产品 BOM 与损耗率重新生成快照。 */
    @Transactional
    public Map<String, Object> resetBom(long id) {
        Map<String, Object> work = loadWork(id);
        assertBomEditable(work);
        BigDecimal issued = jdbcTemplate.queryForObject("""
                select coalesce(sum(issued_qty), 0) from work_order_bom where work_order_id = ?
                """, BigDecimal.class, id);
        if (issued != null && issued.signum() > 0) {
            throw new BizException(2017, "工单已领料，不能恢复标准 BOM，请先退料");
        }
        jdbcTemplate.update("delete from work_order_bom where work_order_id = ?", id);
        snapshotBom(id, ((Number) work.get("product_id")).longValue(), decimal(work.get("plan_qty")));
        logBomChange(id, "RESET", null, null, null, null, "恢复标准 BOM");
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from work_order_bom where work_order_id = ?", Integer.class, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", count == null ? 0 : count);
        return result;
    }

    public List<Map<String, Object>> bomChanges(long id) {
        loadWork(id);
        return bomChangeRows(id);
    }

    private void assertBomEditable(Map<String, Object> work) {
        String status = String.valueOf(work.get("status"));
        if ("FINISHED".equals(status) || "CLOSED".equals(status)) {
            throw new BizException(2018, "工单已完工，不能再调整 BOM");
        }
    }

    private void logBomChange(long workOrderId, String action, Long materialId, String materialName,
                              BigDecimal oldQty, BigDecimal newQty, String reason) {
        jdbcTemplate.update("""
                insert into work_order_bom_change (work_order_id, action, material_id, material_name,
                                                   old_qty, new_qty, reason, changed_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, workOrderId, action, materialId, materialName, oldQty, newQty, reason,
                CurrentUser.name());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Transactional
    public Map<String, Object> issueItem(long id, IssueItemRequest request) {
        if (request.productId() == null || request.qty() == null || request.qty().signum() <= 0) {
            throw new BizException(1002, "请提供物料和领料数量");
        }
        return issueService.issueOne(id, request.productId(), request.qty(),
                request.warehouseId(), request.issuer(), request.team(), request.source());
    }

    /** 批量领料：一次提交多行，同一事务内生成一张领料单 */
    @Transactional
    public Map<String, Object> issueBatch(long id, WorkOrderIssueService.IssueBatchRequest request) {
        Map<String, Object> result = issueService.issueBatch(id, request);
        syncPickTask(id);
        return result;
    }

    /** 工单的领料单列表 */
    public List<Map<String, Object>> issues(long workOrderId) {
        return issueService.listIssues(workOrderId);
    }

    /** 补生成件码：历史工单或下达时未生成的情况 */
    @Transactional
    public int generateUnits(long workOrderId) {
        return unitService.generateForWorkOrder(workOrderId);
    }

    @Transactional
    public Map<String, Object> report(long id, ReportRequest request) {
        Map<String, Object> work = loadWork(id);
        String status = String.valueOf(work.get("status"));
        if (!"RELEASED".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw new BizException(2009, "当前工单状态不允许报工");
        }
        // 单件追溯的工单只能扫件码完工，避免与件流转重复计数。
        // 判定读工单上的快照（不是产品开关）：产品后来改了，在制工单口径不变
        if ("SERIAL".equals(String.valueOf(work.get("trace_mode")))) {
            throw new BizException(2023, "该工单按单件管理，请到车间扫码扫件码完工");
        }
        if (request.operationId() == null || request.qty() == null || request.qty().signum() <= 0) {
            throw new BizException(1002, "请选择工序并填写大于 0 的报工数量");
        }
        List<Map<String, Object>> ops = jdbcTemplate.queryForList("""
                select id, seq, name, plan_qty, done_qty, process_id, inspection_mode, outsource_status,
                       assigned_user_id, assigned_worker, report_policy
                from work_order_operation
                where id = ? and work_order_id = ?
                for update
                """, request.operationId(), id);
        if (ops.isEmpty()) {
            throw new BizException(1002, "工序不属于当前工单");
        }
        Map<String, Object> op = ops.get(0);
        int seq = ((Number) op.get("seq")).intValue();
        String workerName = request.worker() == null || request.worker().isBlank()
                ? CurrentUser.name() : request.worker().trim();
        // 按人分配（报工数配比）：报工人必须是名单上的人，且不能超过他自己的分配量
        List<Map<String, Object>> assignments = jdbcTemplate.queryForList("""
                select id, user_id, worker_name, quota_qty, reported_qty
                from work_order_operation_assignment
                where operation_id = ? order by id
                """, request.operationId());
        Map<String, Object> matchedAssignment = null;
        List<String> assignedNames = new ArrayList<>();
        for (Map<String, Object> row : assignments) {
            assignedNames.add(String.valueOf(row.get("worker_name")));
        }
        String names = String.join("、", assignedNames);
        // 只有「多人分配」才按名单 + 配额卡：单人派工仍允许代报（老行为），
        // 否则班组长帮一个人代填工时都会被挡回去
        if (assignments.size() > 1) {
            for (Map<String, Object> row : assignments) {
                if (workerName.equals(String.valueOf(row.get("worker_name")))) {
                    matchedAssignment = row;
                    break;
                }
            }
            if (matchedAssignment == null) {
                throw new BizException(2025, "第 " + seq + " 道工序按人分配了数量，报工人只能是：" + names);
            }
            BigDecimal left = decimal(matchedAssignment.get("quota_qty"))
                    .subtract(decimal(matchedAssignment.get("reported_qty")));
            if (request.qty().compareTo(left) > 0) {
                throw new BizException(2025, workerName + " 的分配量只剩 "
                        + left.stripTrailingZeros().toPlainString() + "，本次报了 "
                        + request.qty().stripTrailingZeros().toPlainString());
            }
        }
        // 报工权限：设了「仅名单内可报」就只有名单上的人能报，管理员可代办
        if ("ASSIGNED".equals(String.valueOf(op.get("report_policy")))) {
            boolean admin = "ADMIN".equals(CurrentUser.role());
            Long mine = currentUserId();
            if (!admin) {
                if (assignments.isEmpty()) {
                    Object assignedUserId = op.get("assigned_user_id");
                    if (assignedUserId == null) {
                        throw new BizException(2024, "第 " + seq + " 道工序限定报工人，但还没派工，请先在工单里派工");
                    }
                    if (mine == null || mine.longValue() != ((Number) assignedUserId).longValue()) {
                        throw new BizException(2024, "第 " + seq + " 道工序只允许 "
                                + op.get("assigned_worker") + " 报工");
                    }
                } else {
                    boolean allowed = false;
                    for (Map<String, Object> row : assignments) {
                        if (mine != null && ((Number) row.get("user_id")).longValue() == mine.longValue()) {
                            allowed = true;
                            break;
                        }
                    }
                    if (!allowed) {
                        throw new BizException(2024, "第 " + seq + " 道工序只允许名单内的人报工：" + names);
                    }
                }
            }
        }
        // 外协工序的数量由外协单收回登记，这里再报一遍会让同一批活算两次产量
        String outsourceStatus = op.get("outsource_status") == null
                ? null : String.valueOf(op.get("outsource_status"));
        if ("PENDING_SEND".equals(outsourceStatus) || "SENT".equals(outsourceStatus)) {
            throw new BizException(2039, "第 " + seq + " 道工序已转外协，请到外协单登记收回");
        }
        // 工序防差错：前道工序必须完成计划数量，才允许当前工序报工
        // 返修单可能从中间工序起做（rework_from_seq），此时前道工序不存在，直接放行
        if (seq > 1) {
            List<Map<String, Object>> previous = jdbcTemplate.queryForList("""
                    select plan_qty, done_qty from work_order_operation
                    where work_order_id = ? and seq = ?
                    """, id, seq - 1);
            if (!previous.isEmpty()
                    && decimal(previous.get(0).get("done_qty"))
                    .compareTo(decimal(previous.get(0).get("plan_qty"))) < 0) {
                throw new BizException("前道工序尚未完工，不能报工");
            }
        }
        BigDecimal planQty = new BigDecimal(String.valueOf(op.get("plan_qty")));
        BigDecimal doneQty = new BigDecimal(String.valueOf(op.get("done_qty")));
        BigDecimal remaining = planQty.subtract(doneQty);
        if (request.qty().compareTo(remaining) > 0) {
            throw new BizException(2010, "报工数量超过剩余数量，最多可报 "
                    + remaining.stripTrailingZeros().toPlainString());
        }
        BigDecimal defect = request.defectQty() == null ? BigDecimal.ZERO : request.defectQty();
        if (defect.signum() < 0) {
            throw new BizException(2010, "不良数量不能小于 0");
        }
        if (defect.compareTo(request.qty()) > 0) {
            throw new BizException(2010, "不良数量不能大于报工数量");
        }
        BigDecimal qualified = request.qualifiedQty() == null
                ? request.qty().subtract(defect) : request.qualifiedQty();
        if (qualified.signum() < 0) {
            throw new BizException(2010, "合格数量不能小于 0");
        }
        if (qualified.compareTo(request.qty()) > 0) {
            throw new BizException(2010, "合格数量不能大于报工数量");
        }
        if (qualified.add(defect).compareTo(request.qty()) > 0) {
            throw new BizException(2010, "合格数量与不良数量之和不能大于报工数量");
        }

        // 工序内检验点：报工必须给出检验结论，结论存入过程检验记录
        String inspectionMode = op.get("inspection_mode") == null
                ? "NONE" : String.valueOf(op.get("inspection_mode"));
        boolean attached = "ATTACHED".equals(inspectionMode);
        String inspectionResult = request.inspectionResult() == null
                ? null : request.inspectionResult().trim().toUpperCase();
        if (attached) {
            if (inspectionResult == null || inspectionResult.isEmpty()) {
                throw new BizException(2020,
                        "第 " + seq + " 道工序是工序内检验点，请填写检验结论（合格 / 不合格）");
            }
            if (!"PASS".equals(inspectionResult) && !"FAIL".equals(inspectionResult)) {
                throw new BizException(1002, "检验结论只能是合格或不合格");
            }
            if ("PASS".equals(inspectionResult) && defect.signum() > 0) {
                throw new BizException(2010, "检验结论为合格时不良数量必须为 0");
            }
            if ("FAIL".equals(inspectionResult) && defect.signum() == 0) {
                throw new BizException(2010, "检验结论为不合格时必须填写不良数量");
            }
        }
        BigDecimal newDone = doneQty.add(request.qty());
        String opStatus = newDone.compareTo(planQty) >= 0 ? "DONE" : "IN_PROGRESS";
        jdbcTemplate.update("""
                update work_order_operation set done_qty = done_qty + ?, status = ? where id = ?
                """, request.qty(), opStatus, request.operationId());
        jdbcTemplate.update("""
                insert into work_report (work_order_id, operation_id, qty, qualified_qty, defect_qty, worker, remark)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, request.operationId(), request.qty(), qualified, defect,
                workerName, request.remark());
        // 按人分配时累加这个人已报数量，后面再报就按剩余配额卡
        if (matchedAssignment != null) {
            jdbcTemplate.update("""
                    update work_order_operation_assignment set reported_qty = reported_qty + ?
                    where id = ?
                    """, request.qty(), ((Number) matchedAssignment.get("id")).longValue());
        }

        long productId = ((Number) work.get("product_id")).longValue();
        if (attached && inspectionResult != null) {
            // 检验单归质量：这里只发一条「落一张随工检验」的命令
            processInspections.record(new ProcessInspectionPort.ProcessInspection(
                    String.valueOf(work.get("work_no")), id, productId, request.qty(), defect,
                    inspectionResult,
                    request.worker() == null ? CurrentUser.name() : request.worker(),
                    request.inspectionRemark()));
        }
        if (defect.signum() > 0) {
            Object processId = op.get("process_id");
            defectWriter.write(DefectRequest.of(id, request.operationId(),
                    processId == null ? null : ((Number) processId).longValue(),
                    productId, defect, "REPORT",
                    request.remark() == null ? "报工不良" : request.remark()));
        }

        // 一码到底：本道不良自动扣减后道计划，避免后道永远凑不满计划数量
        if (defect.signum() > 0) {
            jdbcTemplate.update("""
                    update work_order_operation
                    set plan_qty = greatest(done_qty, plan_qty - ?)
                    where work_order_id = ? and seq > ?
                    """, defect, id, seq);
        }

        Integer notDone = jdbcTemplate.queryForObject("""
                select count(*) from work_order_operation where work_order_id = ? and done_qty < plan_qty
                """, Integer.class, id);
        Integer lastSeq = jdbcTemplate.queryForObject(
                "select max(seq) from work_order_operation where work_order_id = ?", Integer.class, id);
        BigDecimal lastDone = BigDecimal.ZERO;
        BigDecimal qualifiedTotal = BigDecimal.ZERO;
        if (lastSeq != null) {
            lastDone = jdbcTemplate.queryForObject("""
                    select done_qty from work_order_operation where work_order_id = ? and seq = ?
                    """, BigDecimal.class, id, lastSeq);
            BigDecimal q = jdbcTemplate.queryForObject("""
                    select coalesce(sum(r.qualified_qty), 0) from work_report r
                    join work_order_operation o on o.id = r.operation_id
                    where r.work_order_id = ? and o.seq = ?
                    """, BigDecimal.class, id, lastSeq);
            qualifiedTotal = q == null ? BigDecimal.ZERO : q;
        }
        boolean finished = notDone != null && notDone == 0;
        jdbcTemplate.update("""
                update work_order set done_qty = ?, qualified_qty = ?, status = ?,
                       updated_at = now(), version = version + 1
                where id = ?
                """, lastDone == null ? BigDecimal.ZERO : lastDone, qualifiedTotal,
                finished ? "FINISHED" : "IN_PROGRESS", id);
        // 完工即结算成本：料按领料流水、工按报工量、费按配置比例摊
        if (finished) {
            try {
                costService.recalc(id);
            } catch (Exception e) {
                // 成本结算失败不影响报工本身，可在成本报表里重算
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("finished", finished);
        result.put("operationName", op.get("name"));
        return result;
    }

    @Transactional
    public Map<String, Object> assignOperation(long id, long operationId, Long userId, String worker) {
        return assignOperation(id, operationId, userId, worker, null);
    }

    /**
     * 工序派工（单人）：兼容旧接口与建单时的单人派工。
     *
     * @param reportPolicy 报工权限：ANY 所有人可报 / ASSIGNED 只有名单内的人可报；
     *                     传空表示不改（新建与手工加工序时默认 ANY）
     */
    @Transactional
    public Map<String, Object> assignOperation(long id, long operationId, Long userId, String worker,
                                               String reportPolicy) {
        Long targetUserId = userId;
        if (targetUserId == null && worker != null && !worker.isBlank()) {
            // 兼容旧客户端：只传名字时按姓名或账号反查，查不到就要求改成选人
            List<Map<String, Object>> matched = jdbcTemplate.queryForList("""
                    select id from sys_user
                    where enabled = true and (real_name = ? or username = ?)
                    """, worker.trim(), worker.trim());
            if (matched.size() != 1) {
                throw new BizException(1002, "请从系统用户里选择派工人员");
            }
            targetUserId = ((Number) matched.get(0).get("id")).longValue();
        }
        if (targetUserId == null) {
            throw new BizException(1002, "请选择派工人员");
        }
        return assignOperations(id, operationId, List.of(new AssignmentItem(targetUserId, null)), reportPolicy);
    }

    /**
     * 工序派工（可多人）：整体替换这道工序的分配名单。
     *
     * <p>每人一行、各带分配数量（报工数配比）；不填数量时按整道工序计划量算。
     * 名单为空表示清空派工。同时把第一个人写回 {@code assigned_user_id / assigned_worker}，
     * 看板、扫码、报工默认带出仍然只认这两个字段。</p>
     */
    @Transactional
    public Map<String, Object> assignOperations(long id, long operationId, List<AssignmentItem> items,
                                                String reportPolicy) {
        loadWork(id);
        List<Map<String, Object>> opRows = jdbcTemplate.queryForList("""
                select o.process_id, o.plan_qty
                from work_order_operation o
                where o.id = ? and o.work_order_id = ?
                for update
                """, operationId, id);
        if (opRows.isEmpty()) {
            throw new BizException(1002, "工序不属于当前工单");
        }
        Object processIdValue = opRows.get(0).get("process_id");
        Long processId = processIdValue == null ? null : ((Number) processIdValue).longValue();
        BigDecimal planQty = decimal(opRows.get(0).get("plan_qty"));
        String policy = normalizeReportPolicy(reportPolicy);

        List<AssignmentItem> safeItems = items == null ? List.of() : items;
        jdbcTemplate.update("delete from work_order_operation_assignment where operation_id = ?", operationId);

        String firstWorker = null;
        Long firstUserId = null;
        BigDecimal quotaTotal = BigDecimal.ZERO;
        Set<Long> seen = new LinkedHashSet<>();
        for (AssignmentItem item : safeItems) {
            if (item == null || item.userId() == null) {
                continue;
            }
            if (!seen.add(item.userId())) {
                throw new BizException(1002, "同一个工序不能把同一个人分配两次");
            }
            List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                    select id, real_name as "realName", username, enabled from sys_user where id = ?
                    """, item.userId());
            if (users.isEmpty()) {
                throw new BizException(1002, "派工人员不存在，请重新选择");
            }
            Map<String, Object> user = users.get(0);
            if (!Boolean.TRUE.equals(user.get("enabled"))) {
                throw new BizException(1002, "该用户已停用，不能派工");
            }
            // 工序启用了持证管控时，只能派给会这道工序的人
            if (userSkillService.processRequiresSkill(processId)
                    && !userSkillService.hasSkill(item.userId(), processId)) {
                throw new BizException(2022, "该工序需要持证人员，"
                        + user.get("realName") + " 还没有这道工序的技能");
            }
            BigDecimal quota = item.quotaQty();
            if (quota == null || quota.signum() <= 0) {
                // 不填就按整道工序计划量：单人派工最常见，前端也不用逼着人算
                quota = planQty;
            }
            String displayName = String.valueOf(user.get("realName"));
            jdbcTemplate.update("""
                    insert into work_order_operation_assignment (operation_id, user_id, worker_name, quota_qty)
                    values (?, ?, ?, ?)
                    """, operationId, item.userId(), displayName, quota);
            quotaTotal = quotaTotal.add(quota);
            if (firstUserId == null) {
                firstUserId = item.userId();
                firstWorker = displayName;
            }
        }
        if (!safeItems.isEmpty() && firstUserId == null) {
            throw new BizException(1002, "请选择派工人员");
        }
        jdbcTemplate.update("""
                update work_order_operation
                   set assigned_user_id = ?, assigned_worker = ?,
                       report_policy = coalesce(?, report_policy)
                 where id = ?
                """, firstUserId, firstWorker, policy, operationId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationId", operationId);
        result.put("assignedUserId", firstUserId);
        result.put("assignedWorker", firstWorker);
        result.put("assignmentCount", safeItems.size());
        result.put("quotaTotal", quotaTotal);
        result.put("planQty", planQty);
        result.put("reportPolicy", policy == null
                ? jdbcTemplate.queryForObject(
                        "select report_policy from work_order_operation where id = ?", String.class, operationId)
                : policy);
        result.put("assignments", assignmentsOf(operationId));
        syncAssignTask(id);
        return result;
    }

    /**
     * 派工任务收口：这张工单的工序都挂上人了，任务才算做完。
     * 派工是逐道做的，所以每次派完都回头看一眼整体进度；外协工序不用自己派人。
     */
    private void syncAssignTask(long workId) {
        Integer remain = jdbcTemplate.queryForObject("""
                select count(*) from work_order_operation
                where work_order_id = ? and assigned_user_id is null
                  and (outsource_status is null or outsource_status not in ('PENDING_SEND', 'SENT'))
                """, Integer.class, workId);
        if (remain != null && remain == 0) {
            String workNo = jdbcTemplate.queryForObject(
                    "select work_no from work_order where id = ?", String.class, workId);
            taskService.close(TaskTypes.WO_ASSIGN, workNo, CurrentUser.name(), "工序已全部派工");
        }
    }

    /**
     * 领料任务收口：BOM 待领行都发完了才算做完。
     * 替代料行是备选，不参与判断（source = 'SUBSTITUTE'）。
     */
    private void syncPickTask(long workId) {
        Integer remain = jdbcTemplate.queryForObject("""
                select count(*) from work_order_bom
                where work_order_id = ? and issued_qty < plan_qty and source <> 'SUBSTITUTE'
                """, Integer.class, workId);
        if (remain != null && remain == 0) {
            String workNo = jdbcTemplate.queryForObject(
                    "select work_no from work_order where id = ?", String.class, workId);
            taskService.close(TaskTypes.WO_PICK, workNo, CurrentUser.name(), "物料已领齐");
        }
    }

    /** 某道工序的分配名单 */
    private List<Map<String, Object>> assignmentsOf(long operationId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select a.user_id as "userId", a.worker_name as "workerName",
                       a.quota_qty as "quotaQty", a.reported_qty as "reportedQty",
                       (a.quota_qty - a.reported_qty) as "remainingQty", u.username
                from work_order_operation_assignment a
                join sys_user u on u.id = a.user_id
                where a.operation_id = ?
                order by a.id
                """, operationId)) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("quotaQty", decimal(row.get("quotaQty")));
            item.put("reportedQty", decimal(row.get("reportedQty")));
            item.put("remainingQty", decimal(row.get("remainingQty")));
            rows.add(item);
        }
        return rows;
    }

    /** 报工权限归一化：空表示不改，只接受 ANY / ASSIGNED */
    private String normalizeReportPolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return null;
        }
        String value = policy.trim().toUpperCase();
        if (!List.of("ANY", "ASSIGNED").contains(value)) {
            throw new BizException(1002, "报工权限只能是 ANY（所有人）或 ASSIGNED（仅派工人员）");
        }
        return value;
    }

    /** 当前登录用户的 id，按账号反查；查不到返回 null（例如系统任务） */
    private Long currentUserId() {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from sys_user where username = ?", Long.class, CurrentUser.name());
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Transactional
    public Map<String, Object> returnMaterial(long id, WorkOrderIssueService.ReturnRequest request) {
        return issueService.returnBatch(id, request);
    }

    /** 可退料明细与建议退料量（领多了的部分） */
    public List<Map<String, Object>> returnableLines(long id) {
        return issueService.returnableLines(id);
    }

    public List<Map<String, Object>> returns(long id) {
        return issueService.listReturns(id);
    }

    @Transactional
    public void close(long id) {
        Map<String, Object> work = loadWork(id);
        if (!"FINISHED".equals(String.valueOf(work.get("status")))) {
            throw new BizException(2011, "只有已完工工单可以关闭");
        }
        jdbcTemplate.update("update work_order set status = 'CLOSED', updated_at = now() where id = ?", id);
    }

    /** 可作废的工单状态：还没真正开工的单子才允许撤 */
    private static final Set<String> CANCELLABLE_STATUS = Set.of("DRAFT", "SCHEDULED", "RELEASED");

    /** 作废检查项：带业务码，车间作废与销售撤明细行共用同一套判断 */
    private record CancelBlocker(int code, String message) {
    }

    /**
     * 工单作废的阻塞原因：状态、报工、领料、在途外协、件码流转，逐项检查。
     *
     * <p>车间作废与销售撤明细行共用这一套判断，提示口径不会两处不一致；
     * 预检时只读不写，执行时再跑一遍防并发。</p>
     */
    @Override
    public List<String> cancelBlockers(long workOrderId) {
        return cancelBlockerDetails(workOrderId).stream().map(CancelBlocker::message).toList();
    }

    private List<CancelBlocker> cancelBlockerDetails(long workOrderId) {
        Map<String, Object> work = loadWork(workOrderId);
        List<CancelBlocker> blockers = new ArrayList<>();
        String status = String.valueOf(work.get("status"));
        if (!CANCELLABLE_STATUS.contains(status)) {
            blockers.add(new CancelBlocker(2014, "工单状态为 " + status
                    + "，只有草稿、已排产、已下达且未开工的工单可以调整或作废；已开工的请走完工或返工流程"));
        }
        if (countOf("select count(*) from work_report where work_order_id = ?", workOrderId) > 0) {
            blockers.add(new CancelBlocker(2015, "工单已有报工记录，不能调整或作废"));
        }
        BigDecimal issued = decimal(jdbcTemplate.queryForObject("""
                select coalesce(sum(issued_qty), 0) from work_order_bom
                where work_order_id = ? and source <> 'SUBSTITUTE'
                """, BigDecimal.class, workOrderId));
        if (issued.signum() > 0) {
            blockers.add(new CancelBlocker(2016, "工单已领料 "
                    + issued.stripTrailingZeros().toPlainString() + "，请先做生产退料再调整或作废"));
        }
        if (countOf("""
                select count(*) from outsource_order where work_order_id = ? and status in ('SENT', 'PARTIAL')
                """, workOrderId) > 0) {
            blockers.add(new CancelBlocker(2017, "工单有在途外协，请先收回或撤销外协再调整或作废"));
        }
        if (countOf("""
                select count(*) from work_order_unit where work_order_id = ? and status in ('IN_PROGRESS', 'FINISHED')
                """, workOrderId) > 0) {
            blockers.add(new CancelBlocker(2018, "件码已开始流转，不能调整或作废"));
        }
        return blockers;
    }

    /**
     * 作废工单：客户取消、下单错误时把还没开工的工单撤掉。
     *
     * <p>前置条件是「这单还没动过料和人」：没有报工、没有领料、没有在途外协、件码没开始流转。
     * 已经投过料的必须先做生产退料，已经把件发到外协厂的必须先收回或撤销外协 ——
     * 否则库存、成本、追溯链三处都对不上。</p>
     */
    @Override
    @Transactional
    public Map<String, Object> cancelWorkOrder(long id, String reason) {
        Map<String, Object> work = loadWork(id);
        List<CancelBlocker> blockers = cancelBlockerDetails(id);
        if (!blockers.isEmpty()) {
            throw new BizException(blockers.get(0).code(),
                    String.join("；", blockers.stream().map(CancelBlocker::message).toList()));
        }
        String trimmed = reason == null ? null : reason.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        jdbcTemplate.update("""
                update work_order
                   set status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(), cancelled_by = ?,
                       schedule_date = null, schedule_seq = null, updated_at = now()
                 where id = ?
                """, trimmed, CurrentUser.name(), id);
        // 工序一起作废：排产、看板、待办都不再把它当成要干的活
        jdbcTemplate.update("update work_order_operation set status = 'CANCELLED' where work_order_id = ?", id);
        // 还没投产的件码标报废，单件追踪里一眼能看出是撤单留下的
        jdbcTemplate.update("""
                update work_order_unit set status = 'SCRAPPED' where work_order_id = ? and status = 'PENDING'
                """, id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("workNo", work.get("work_no"));
        result.put("status", "CANCELLED");
        result.put("cancelReason", trimmed);
        return result;
    }

    /**
     * 缩短工单计划量：客户砍掉一部分数量时用。
     *
     * <p>工序计划量与标准工时、BOM 快照用量一起按新老数量比例缩，车间不会照着老数量多干。
     * 前置条件与作废一致（没报工、没领料、无在途外协、件码没流转），
     * 因为「已经投过料」的单改数量同样会让库存与成本对不上。</p>
     */
    @Override
    @Transactional
    public Map<String, Object> resizeWorkOrder(long id, BigDecimal newQty) {
        Map<String, Object> work = loadWork(id);
        List<CancelBlocker> blockers = cancelBlockerDetails(id);
        if (!blockers.isEmpty()) {
            throw new BizException(blockers.get(0).code(),
                    String.join("；", blockers.stream().map(CancelBlocker::message).toList()));
        }
        BigDecimal oldQty = decimal(work.get("plan_qty"));
        if (newQty == null || newQty.signum() <= 0) {
            throw new BizException(1002, "新的计划数量必须大于 0");
        }
        if (newQty.compareTo(oldQty) >= 0) {
            throw new BizException(1002, "只支持缩短计划量：新数量 "
                    + newQty.stripTrailingZeros().toPlainString() + " 不小于原数量 "
                    + oldQty.stripTrailingZeros().toPlainString());
        }
        jdbcTemplate.update("""
                update work_order set plan_qty = ?, updated_at = now(), version = version + 1
                where id = ?
                """, newQty, id);
        // 工序：计划量按比例缩，标准工时跟着缩，否则排产还会按老工时占产能
        int operations = jdbcTemplate.update("""
                update work_order_operation
                   set plan_qty = round(plan_qty * ? / ?, 4),
                       standard_minutes = round(coalesce(standard_minutes, 0) * ? / ?, 2)
                 where work_order_id = ?
                """, newQty, oldQty, newQty, oldQty, id);
        // BOM 快照：替代行用量本来就是 0，不参与缩放
        int bomLines = jdbcTemplate.update("""
                update work_order_bom
                   set plan_qty = round(plan_qty * ? / ?, 4)
                 where work_order_id = ? and source <> 'SUBSTITUTE'
                """, newQty, oldQty, id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("workNo", work.get("work_no"));
        result.put("oldQty", oldQty);
        result.put("newQty", newQty);
        result.put("operations", operations);
        result.put("bomLines", bomLines);
        return result;
    }

    private long countOf(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /**
     * 按产品工艺路线生成工单工序；没有工艺路线时工序留空，由计划在工单里手工添加。
     *
     * <p>不退回硬编码三道工序，也不在「产品还没维护路线」时把建单整条路堵死 ——
     * 空工序由 {@code release()} 拦住，车间不会拿到一张没有工序可报的工单。</p>
     */
    @Override
    public void createOperations(long workId, long productId, BigDecimal planQty) {
        createOperations(workId, productId, planQty, null);
    }

    @Override
    public CreatedWorkOrder createFromSalesOrder(long salesOrderId, long salesOrderItemId, long productId,
                                                 BigDecimal planQty, String priority, LocalDate planEnd) {
        String workNo = codeGenerator.next("WO");
        // 按成品提前期倒排计划开始日，交期当天完成
        int leadDays = masterData.product(productId).map(ProductInfo::leadDays).orElse(0);
        LocalDate planStart = planEnd == null ? LocalDate.now() : planEnd.minusDays(leadDays);
        if (planStart.isBefore(LocalDate.now())) {
            planStart = LocalDate.now();
        }
        Long workId = jdbcTemplate.queryForObject("""
                insert into work_order (work_no, sales_order_id, sales_order_item_id, product_id,
                                        plan_qty, priority, status, plan_start, plan_end)
                values (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                returning id
                """, Long.class, workNo, salesOrderId, salesOrderItemId, productId, planQty, priority,
                planStart, planEnd);
        // 工序按产品工艺路线生成，BOM 按标准 BOM 生成工单快照（含损耗率）
        createOperations(workId, productId, planQty);
        snapshotBom(workId, productId, planQty);
        return new CreatedWorkOrder(workId, workNo);
    }

    @Override
    public CreatedWorkOrder createPlanned(long productId, BigDecimal planQty, LocalDate planStart,
                                          LocalDate planEnd, String priority, String remark) {
        long workId = create(new WorkOrderRequest(productId, planQty, planStart, planEnd,
                priority, null, remark, null, null));
        return new CreatedWorkOrder(workId, workNo(workId));
    }

    @Override
    public void linkDependencies(Collection<WorkOrderDependency> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return;
        }
        List<Object[]> args = dependencies.stream()
                .filter(dependency -> dependency.predecessorId() != dependency.successorId())
                .distinct()
                .map(dependency -> new Object[]{dependency.predecessorId(), dependency.successorId()})
                .toList();
        if (args.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                insert into work_order_dependency (predecessor_id, successor_id)
                values (?, ?)
                on conflict (predecessor_id, successor_id) do nothing
                """, args);
    }

    /** 该工单还没完工的前置工单，返回「工单号（状态）」；空表示可以下达 */
    private List<String> dependencyBlockers(long workOrderId) {
        return jdbcTemplate.query("""
                select w.work_no as "workNo", w.status
                from work_order_dependency d
                join work_order w on w.id = d.predecessor_id
                where d.successor_id = ? and w.status not in ('FINISHED', 'CLOSED')
                order by w.id
                """, (rs, rowNum) -> rs.getString("workNo") + "（" + rs.getString("status") + "）",
                workOrderId);
    }

    /**
     * @param fromSeq 返修起点工序序号，空表示整件返修从第一道开始；
     *                非空时只生成该工序及之后的工序，前面的工序不再重复加工
     */
    public void createOperations(long workId, long productId, BigDecimal planQty, Integer fromSeq) {
        List<Map<String, Object>> routing = jdbcTemplate.queryForList("""
                select r.seq, r.minutes_per_piece as "minutes", r.unit_price as "price",
                       r.need_inspection as "needInspection", p.id as "processId", p.name,
                       p.inspection_mode as "inspectionMode"
                from product_routing r
                join rm_process p on p.id = r.process_id
                where r.product_id = ? and p.enabled = true
                  and (cast(? as integer) is null or r.seq >= cast(? as integer))
                order by r.seq
                """, productId, fromSeq, fromSeq);
        if (routing.isEmpty()) {
            if (fromSeq != null) {
                Integer total = jdbcTemplate.queryForObject(
                        "select count(*) from product_routing where product_id = ?", Integer.class, productId);
                if (total != null && total > 0) {
                    throw new BizException(1002, "返修起点工序不在该产品的工艺路线内");
                }
                throw new BizException(2014, "该产品未维护工艺路线，不能按工序返修");
            }
            // 没有工艺路线也允许建单：工单先落成空工序，由计划在工单里逐道手工添加。
            // 上线初期主数据常常没录全，一刀切拒绝会把「先开工、后补路线」这条正常路径堵死。
            return;
        }
        for (Map<String, Object> line : routing) {
            int seq = ((Number) line.get("seq")).intValue();
            BigDecimal standardMinutes = planQty.multiply(decimal(line.get("minutes")));
            jdbcTemplate.update("""
                    insert into work_order_operation (work_order_id, seq, name, plan_qty,
                                                      standard_minutes, unit_price, process_id,
                                                      need_inspection, inspection_mode)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, workId, seq, String.valueOf(line.get("name")), planQty,
                    standardMinutes, decimal(line.get("price")),
                    ((Number) line.get("processId")).longValue(),
                    Boolean.TRUE.equals(line.get("needInspection")),
                    String.valueOf(line.get("inspectionMode")));
        }
    }

    /** 手工给工单加一道工序：产品没维护工艺路线时用，从工序档案里挑，保证排产与成本都有归属。 */
    public record AddOperationRequest(Long processId, String name, BigDecimal planQty,
                                      BigDecimal minutesPerPiece, BigDecimal price,
                                      Boolean needInspection) {
    }

    @Transactional
    public Map<String, Object> addOperation(long workId, AddOperationRequest request) {
        Map<String, Object> work = loadWork(workId);
        String status = String.valueOf(work.get("status"));
        if (List.of("FINISHED", "CLOSED", "CANCELLED").contains(status)) {
            throw new BizException(2015, "工单已完工或已收尾，不能再加工序");
        }
        if (request.processId() == null && (request.name() == null || request.name().isBlank())) {
            throw new BizException(1002, "请选择工序或填写工序名称");
        }
        String name = request.name() == null ? null : request.name().trim();
        String inspectionMode = null;
        if (request.processId() != null) {
            List<Map<String, Object>> processes = jdbcTemplate.queryForList("""
                    select name, inspection_mode as "inspectionMode"
                    from rm_process where id = ? and enabled = true
                    """, request.processId());
            if (processes.isEmpty()) {
                throw new BizException(1002, "工序不存在或已停用");
            }
            name = String.valueOf(processes.get(0).get("name"));
            inspectionMode = (String) processes.get(0).get("inspectionMode");
        }
        BigDecimal planQty = request.planQty() == null ? decimal(work.get("plan_qty")) : request.planQty();
        if (planQty.signum() <= 0) {
            throw new BizException(1002, "工序计划数量必须大于 0");
        }
        // 标准工时与计件工价：请求里有就用，没有就回落到产品工艺路线上同工序的配置
        BigDecimal minutes = request.minutesPerPiece();
        BigDecimal price = request.price();
        if ((minutes == null || price == null) && request.processId() != null) {
            List<Map<String, Object>> routing = jdbcTemplate.queryForList("""
                    select minutes_per_piece as "minutes", unit_price as "price"
                    from product_routing where product_id = ? and process_id = ?
                    """, ((Number) work.get("product_id")).longValue(), request.processId());
            if (!routing.isEmpty()) {
                if (minutes == null) {
                    minutes = decimal(routing.get(0).get("minutes"));
                }
                if (price == null) {
                    price = decimal(routing.get(0).get("price"));
                }
            }
        }
        Integer maxSeq = jdbcTemplate.queryForObject(
                "select coalesce(max(seq), 0) from work_order_operation where work_order_id = ?",
                Integer.class, workId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        BigDecimal standardMinutes = planQty.multiply(minutes == null ? BigDecimal.ZERO : minutes);
        jdbcTemplate.update("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, standard_minutes,
                                                  unit_price, process_id, need_inspection, inspection_mode)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, workId, seq, name, planQty, standardMinutes,
                price == null ? BigDecimal.ZERO : price, request.processId(),
                Boolean.TRUE.equals(request.needInspection()), inspectionMode);
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("seq", seq);
        created.put("name", name);
        created.put("planQty", planQty);
        return created;
    }

    /** 删除手工加的工序：只在工单下达前允许，避免动到已经领料 / 报工的工序。 */
    @Transactional
    public void deleteOperation(long workId, int seq) {
        Map<String, Object> work = loadWork(workId);
        String status = String.valueOf(work.get("status"));
        if (!List.of("DRAFT", "SCHEDULED").contains(status)) {
            throw new BizException(1002, "只有未下达的工单可以删除工序");
        }
        Integer exists = jdbcTemplate.queryForObject("""
                select count(*) from work_order_operation where work_order_id = ? and seq = ?
                """, Integer.class, workId, seq);
        if (exists == null || exists == 0) {
            throw new BizException(1002, "工序不存在");
        }
        Integer reported = jdbcTemplate.queryForObject("""
                select count(*) from work_report where work_order_id = ? and operation_id in
                    (select id from work_order_operation where work_order_id = ? and seq = ?)
                """, Integer.class, workId, workId, seq);
        if (reported != null && reported > 0) {
            throw new BizException(2015, "该工序已报工，不能删除");
        }
        jdbcTemplate.update("delete from work_order_operation where work_order_id = ? and seq = ?", workId, seq);
    }

    /** 工单 BOM 快照：标准 BOM × 计划数量 ×（1 + 损耗率），并带上替代料行（需求为 0）。 */
    @Override
    public void snapshotBom(long workId, long productId, BigDecimal planQty) {
        snapshotBom(workId, productId, planQty, null);
    }

    @Override
    public boolean exists(long workOrderId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from work_order where id = ?", Integer.class, workOrderId);
        return count != null && count > 0;
    }

    @Override
    public String workNo(long workOrderId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select work_no from work_order where id = ?", String.class, workOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Map<Long, String> workNos(Collection<Long> workOrderIds) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (workOrderIds == null) {
            return map;
        }
        List<Long> ids = workOrderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, work_no from work_order where id in (" + marks + ")", ids.toArray())) {
            map.put(((Number) row.get("id")).longValue(), String.valueOf(row.get("work_no")));
        }
        return map;
    }

    @Override
    public Set<Long> searchIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "select id from work_order where work_no ilike ? order by id desc limit 500",
                Long.class, "%" + keyword.trim() + "%"));
    }

    @Override
    public Long factoryOrgId(long workOrderId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "select factory_org_id from work_order where id = ?", Long.class, workOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public WorkOrderRef workOrder(long workOrderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, work_no, status, work_type, product_id, parent_work_order_id,
                       sales_order_id, sales_order_item_id, plan_end
                from work_order where id = ?
                """, workOrderId);
        return rows.isEmpty() ? null : refOf(rows.get(0));
    }

    @Override
    public String statusOf(long workOrderId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select status from work_order where id = ?", String.class, workOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Long productIdOf(long workOrderId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "select product_id from work_order where id = ?", Long.class, workOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public WorkOrderOperation operation(long workOrderId, Long operationId, Integer seq) {
        List<Map<String, Object>> rows = operationId != null
                ? jdbcTemplate.queryForList("""
                        select id, work_order_id, seq, name, process_id from work_order_operation
                        where id = ? and work_order_id = ?
                        """, operationId, workOrderId)
                : jdbcTemplate.queryForList("""
                        select id, work_order_id, seq, name, process_id from work_order_operation
                        where work_order_id = ? and seq = ?
                        """, workOrderId, seq);
        return rows.isEmpty() ? null : operationOf(rows.get(0));
    }

    @Override
    public Map<Long, WorkOrderOperation> operations(Collection<Long> operationIds) {
        Map<Long, WorkOrderOperation> map = new LinkedHashMap<>();
        if (operationIds == null) {
            return map;
        }
        List<Long> ids = operationIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, work_order_id, seq, name, process_id from work_order_operation where id in ("
                        + marks + ")", ids.toArray())) {
            WorkOrderOperation operation = operationOf(row);
            map.put(operation.id(), operation);
        }
        return map;
    }

    /**
     * 完工检合格：回写合格数、关单。
     * 如果这张工单本身是返修单，父工单也随之关闭（返修的目的就是让它能交付），
     * 合格数按父工单的计划数算满 —— 这是原有口径，不在这里重新解释。
     */
    @Override
    public void closeAfterFinalInspection(long workOrderId, BigDecimal qualifiedQty) {
        jdbcTemplate.update("""
                update work_order set qualified_qty = ?, status = 'CLOSED', updated_at = now(),
                       version = version + 1
                where id = ?
                """, qualifiedQty, workOrderId);
        WorkOrderRef current = workOrder(workOrderId);
        if (current != null && "REWORK".equals(current.workType()) && current.parentWorkOrderId() != null) {
            jdbcTemplate.update("""
                    update work_order set status = 'CLOSED', qualified_qty = plan_qty,
                           updated_at = now(), version = version + 1
                    where id = ?
                    """, current.parentWorkOrderId());
        }
    }

    @Override
    public void markRework(long workOrderId) {
        jdbcTemplate.update("""
                update work_order set status = 'REWORK', updated_at = now(), version = version + 1
                where id = ?
                """, workOrderId);
    }

    @Override
    public void addQualifiedQty(long workOrderId, BigDecimal qty) {
        jdbcTemplate.update("""
                update work_order set qualified_qty = qualified_qty + ?, updated_at = now()
                where id = ?
                """, qty, workOrderId);
    }

    @Override
    public boolean hasUnfinishedForSalesOrder(long salesOrderId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from work_order
                where sales_order_id = ? and status not in ('FINISHED', 'CLOSED', 'CANCELLED')
                """, Integer.class, salesOrderId);
        return count != null && count > 0;
    }

    /** 作废的工单不算「已经转生产」，否则撤单后想重新转生产会被拦在幂等校验上 */
    @Override
    public boolean existsForSalesOrder(long salesOrderId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from work_order where sales_order_id = ? and status <> 'CANCELLED'
                """, Integer.class, salesOrderId);
        return count != null && count > 0;
    }

    @Override
    public List<String> openWorkNos(long salesOrderId) {
        return jdbcTemplate.queryForList("""
                select work_no from work_order
                where sales_order_id = ? and status not in ('CLOSED', 'CANCELLED')
                order by id
                """, String.class, salesOrderId);
    }

    @Override
    public List<WorkOrderPort.WorkOrderRef> workOrdersOfItem(long salesOrderItemId, long salesOrderId,
                                                             long productId) {
        // 老工单只写了订单没写明细 id，按「订单 + 产品」兜底，免得撤行时漏掉它
        return jdbcTemplate.query("""
                select id, work_no, status, work_type, product_id, parent_work_order_id,
                       sales_order_id, sales_order_item_id, plan_end
                from work_order
                where sales_order_item_id = ?
                   or (sales_order_item_id is null and sales_order_id = ? and product_id = ?)
                order by id
                """, (rs, rowNum) -> new WorkOrderPort.WorkOrderRef(
                rs.getLong("id"), rs.getString("work_no"), rs.getString("status"),
                rs.getString("work_type"), rs.getLong("product_id"),
                rs.getObject("parent_work_order_id") == null ? null : rs.getLong("parent_work_order_id"),
                rs.getObject("sales_order_id") == null ? null : rs.getLong("sales_order_id"),
                rs.getObject("sales_order_item_id") == null ? null : rs.getLong("sales_order_item_id"),
                rs.getDate("plan_end") == null ? null : rs.getDate("plan_end").toLocalDate()),
                salesOrderItemId, salesOrderId, productId);
    }

    @Override
    public List<WorkOrderPort.PendingReturn> pendingReturns(Collection<Long> workOrderIds) {
        List<Long> ids = workOrderIds == null ? List.of()
                : workOrderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        List<WorkOrderPort.PendingReturn> lines = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select b.work_order_id as "workOrderId", w.work_no as "workNo",
                       b.material_id as "materialId", p.code as "materialCode", p.name as "materialName",
                       p.unit, coalesce(b.issued_qty, 0) as "issuedQty",
                       coalesce((select sum(t.qty) from rm_stock_txn t
                                 where t.txn_type = 'RETURN_MAT' and t.ref_no = w.work_no
                                   and t.product_id = b.material_id), 0) as "returnedQty"
                from work_order_bom b
                join work_order w on w.id = b.work_order_id
                join rm_product p on p.id = b.material_id
                where b.work_order_id in (""" + marks + """
                ) and b.source <> 'SUBSTITUTE'
                order by b.work_order_id, b.operation_seq nulls last, b.id
                """, ids.toArray())) {
            BigDecimal issued = decimal(row.get("issuedQty"));
            BigDecimal returned = decimal(row.get("returnedQty"));
            BigDecimal returnable = issued.subtract(returned);
            if (returnable.signum() <= 0) {
                continue;
            }
            lines.add(new WorkOrderPort.PendingReturn(
                    ((Number) row.get("workOrderId")).longValue(), String.valueOf(row.get("workNo")),
                    ((Number) row.get("materialId")).longValue(), String.valueOf(row.get("materialCode")),
                    String.valueOf(row.get("materialName")), (String) row.get("unit"),
                    issued, returned, returnable));
        }
        return lines;
    }

    // ---------------------------------------------------------------- 计划：需求与排产

    @Override
    public List<MaterialDemand> openMaterialDemand(Collection<String> statuses, Integer horizonDays) {
        List<String> safe = statuses == null || statuses.isEmpty()
                ? List.of("RELEASED", "IN_PROGRESS") : List.copyOf(statuses);
        List<Object> args = new ArrayList<>(safe);
        String horizon = "";
        if (horizonDays != null && horizonDays > 0) {
            horizon = " and coalesce(w.schedule_date, w.plan_start, current_date) <= current_date + ? ";
            args.add(horizonDays);
        }
        List<MaterialDemand> demands = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select b.material_id as "productId",
                       sum(greatest(b.plan_qty - b.issued_qty, 0)) as "grossNeed",
                       min(coalesce(w.schedule_date, w.plan_start, current_date)) as "firstNeedDate",
                       count(distinct w.id) as "workOrderCount",
                       string_agg(distinct w.work_no, '、') as "workOrders"
                from work_order_bom b
                join work_order w on w.id = b.work_order_id
                where w.status in (""" + placeholders(safe.size()) + ") and b.source <> 'SUBSTITUTE'" + horizon + """
                group by b.material_id
                """, args.toArray())) {
            demands.add(new MaterialDemand(((Number) row.get("productId")).longValue(),
                    decimalOf(row.get("grossNeed")),
                    row.get("firstNeedDate") == null
                            ? null : ((java.sql.Date) row.get("firstNeedDate")).toLocalDate(),
                    ((Number) row.get("workOrderCount")).intValue(),
                    row.get("workOrders") == null ? "" : String.valueOf(row.get("workOrders"))));
        }
        return demands;
    }

    @Override
    public List<MaterialDemand> committedMaterialDemand() {
        List<MaterialDemand> demands = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select b.material_id as "productId",
                       sum(greatest(b.plan_qty - b.issued_qty, 0)) as "grossNeed",
                       min(coalesce(w.schedule_date, w.plan_start, current_date)) as "firstNeedDate",
                       count(distinct w.id) as "workOrderCount",
                       string_agg(distinct w.work_no, '、') as "workOrders"
                from work_order_bom b
                join work_order w on w.id = b.work_order_id
                where w.status in ('DRAFT', 'SCHEDULED', 'RELEASED', 'IN_PROGRESS')
                  and b.source <> 'SUBSTITUTE'
                  and b.plan_qty > b.issued_qty
                group by b.material_id
                """)) {
            demands.add(new MaterialDemand(((Number) row.get("productId")).longValue(),
                    decimalOf(row.get("grossNeed")),
                    row.get("firstNeedDate") == null
                            ? null : ((java.sql.Date) row.get("firstNeedDate")).toLocalDate(),
                    ((Number) row.get("workOrderCount")).intValue(),
                    row.get("workOrders") == null ? "" : String.valueOf(row.get("workOrders"))));
        }
        return demands;
    }

    @Override
    public Set<Long> salesOrderIdsOfWorkNos(Collection<String> workNos) {
        if (workNos == null || workNos.isEmpty()) {
            return Set.of();
        }
        List<String> safe = workNos.stream().filter(no -> no != null && !no.isBlank()).distinct().toList();
        if (safe.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                select distinct sales_order_id from work_order
                where sales_order_id is not null and work_no in (""" + placeholders(safe.size()) + ")",
                Long.class, safe.toArray()));
    }

    @Override
    public List<ScheduleOrder> ordersForScheduling(Collection<String> statuses) {
        List<String> safe = statuses == null || statuses.isEmpty()
                ? List.of("DRAFT") : List.copyOf(statuses);
        List<ScheduleOrder> orders = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, work_no, product_id, plan_qty, done_qty, priority, status, sales_order_id,
                       schedule_date, schedule_seq, scheduled_start, scheduled_end, created_at
                from work_order
                where status in (""" + placeholders(safe.size()) + ") order by id", safe.toArray())) {
            orders.add(scheduleOrderOf(row));
        }
        return orders;
    }

    @Override
    public Map<Long, ScheduleOrder> scheduleOrders(Collection<Long> workOrderIds) {
        Map<Long, ScheduleOrder> map = new LinkedHashMap<>();
        List<Long> ids = distinct(workOrderIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, work_no, product_id, plan_qty, done_qty, priority, status, sales_order_id,
                       schedule_date, schedule_seq, scheduled_start, scheduled_end, created_at
                from work_order where id in (""" + placeholders(ids.size()) + ") order by id",
                ids.toArray())) {
            ScheduleOrder order = scheduleOrderOf(row);
            map.put(order.id(), order);
        }
        return map;
    }

    @Override
    public List<ScheduleOperation> operationsOfWorkOrders(Collection<Long> workOrderIds) {
        List<Long> ids = distinct(workOrderIds);
        List<ScheduleOperation> operations = new ArrayList<>();
        if (ids.isEmpty()) {
            return operations;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, work_order_id, seq, name, process_id, standard_minutes,
                       plan_qty, done_qty, schedule_date
                from work_order_operation
                where work_order_id in (""" + placeholders(ids.size()) + """
                ) order by work_order_id, seq
                """, ids.toArray())) {
            operations.add(scheduleOperationOf(row));
        }
        return operations;
    }

    @Override
    public void updatePriority(long workOrderId, String priority) {
        jdbcTemplate.update(
                "update work_order set priority = ?, updated_at = now() where id = ?", priority, workOrderId);
    }

    @Override
    public Map<Long, BigDecimal> standardMinutes(Collection<Long> workOrderIds) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        List<Long> ids = distinct(workOrderIds);
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select work_order_id as "workOrderId", coalesce(sum(standard_minutes), 0) as "minutes"
                from work_order_operation
                where work_order_id in (""" + placeholders(ids.size()) + ") group by work_order_id",
                ids.toArray())) {
            map.put(((Number) row.get("workOrderId")).longValue(), decimalOf(row.get("minutes")));
        }
        return map;
    }

    @Override
    public List<ScheduleOperation> operationsForScheduling(Collection<String> statuses,
                                                            LocalDate from, LocalDate to) {
        List<String> safe = statuses == null || statuses.isEmpty()
                ? List.of("SCHEDULED", "RELEASED", "IN_PROGRESS") : List.copyOf(statuses);
        List<Object> args = new ArrayList<>(safe);
        args.add(java.sql.Date.valueOf(from));
        args.add(java.sql.Date.valueOf(to));
        List<ScheduleOperation> operations = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select o.id, o.work_order_id, o.seq, o.name, o.process_id, o.standard_minutes,
                       o.plan_qty, o.done_qty, o.schedule_date
                from work_order_operation o
                join work_order w on w.id = o.work_order_id
                where w.status in (""" + placeholders(safe.size()) + """
                  ) and o.schedule_date between ? and ?
                order by o.schedule_date, o.work_order_id, o.seq
                """, args.toArray())) {
            operations.add(scheduleOperationOf(row));
        }
        return operations;
    }

    @Override
    public List<WorkOrderPort.OrderLevelPlan> orderLevelPlans(Collection<String> statuses,
                                                             LocalDate from, LocalDate to) {
        List<String> safe = statuses == null || statuses.isEmpty()
                ? List.of("SCHEDULED", "RELEASED", "IN_PROGRESS") : List.copyOf(statuses);
        List<Object> args = new ArrayList<>(safe);
        args.add(java.sql.Date.valueOf(from));
        args.add(java.sql.Date.valueOf(to));
        List<WorkOrderPort.OrderLevelPlan> plans = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select w.id, w.work_no, w.product_id, w.plan_qty, w.done_qty, w.status, w.priority,
                       w.schedule_date, w.schedule_seq
                from work_order w
                where w.status in (""" + placeholders(safe.size()) + """
                  ) and w.schedule_date between ? and ?
                  and not exists (select 1 from work_order_operation o
                                  where o.work_order_id = w.id and o.schedule_date is not null)
                order by w.schedule_date, w.schedule_seq nulls last, w.id
                """, args.toArray())) {
            plans.add(new WorkOrderPort.OrderLevelPlan(
                    ((Number) row.get("id")).longValue(), String.valueOf(row.get("work_no")),
                    ((Number) row.get("product_id")).longValue(),
                    decimal(row.get("plan_qty")), decimal(row.get("done_qty")),
                    String.valueOf(row.get("status")), (String) row.get("priority"),
                    row.get("schedule_date") == null ? null
                            : ((java.sql.Date) row.get("schedule_date")).toLocalDate(),
                    row.get("schedule_seq") == null ? null : ((Number) row.get("schedule_seq")).intValue()));
        }
        return plans;
    }

    @Override
    public Map<LocalDate, BigDecimal> scheduledMinutesByDate(LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select w.schedule_date as d, coalesce(sum(o.standard_minutes), 0) as "minutes"
                from work_order w left join work_order_operation o on o.work_order_id = w.id
                where w.schedule_date between ? and ?
                  and w.status in ('SCHEDULED', 'RELEASED', 'IN_PROGRESS')
                group by w.schedule_date
                """, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to))) {
            map.put(((java.sql.Date) row.get("d")).toLocalDate(), decimalOf(row.get("minutes")));
        }
        return map;
    }

    @Override
    public Integer maxScheduleSeq(LocalDate date, Long excludeWorkOrderId) {
        return scheduleSeq(date, excludeWorkOrderId, "max");
    }

    @Override
    public Integer minScheduleSeq(LocalDate date, Long excludeWorkOrderId) {
        return scheduleSeq(date, excludeWorkOrderId, "min");
    }

    @Override
    public ScheduleOrder scheduleNeighbor(LocalDate date, int seq, long workOrderId, boolean up) {
        String sql = up
                ? """
                select id, work_no, product_id, plan_qty, done_qty, priority, status, sales_order_id,
                       schedule_date, schedule_seq, scheduled_start, scheduled_end, created_at
                from work_order
                where schedule_date = ? and schedule_seq is not null and schedule_seq < ? and id <> ?
                order by schedule_seq desc limit 1
                """
                : """
                select id, work_no, product_id, plan_qty, done_qty, priority, status, sales_order_id,
                       schedule_date, schedule_seq, scheduled_start, scheduled_end, created_at
                from work_order
                where schedule_date = ? and schedule_seq is not null and schedule_seq > ? and id <> ?
                order by schedule_seq asc limit 1
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
                java.sql.Date.valueOf(date), seq, workOrderId);
        return rows.isEmpty() ? null : scheduleOrderOf(rows.get(0));
    }

    @Override
    public void placeSchedule(long workOrderId, LocalDate date, int seq, LocalDateTime start,
                              LocalDateTime end, String status) {
        LocalDate day = date == null ? (start == null ? LocalDate.now() : start.toLocalDate()) : date;
        jdbcTemplate.update("""
                update work_order set status = ?, schedule_date = ?, schedule_seq = ?,
                       scheduled_start = ?, scheduled_end = ?, updated_at = now(), version = version + 1
                where id = ?
                """, status, java.sql.Date.valueOf(day), seq,
                start == null ? null : java.sql.Timestamp.valueOf(start),
                end == null ? null : java.sql.Timestamp.valueOf(end), workOrderId);
    }

    @Override
    public void clearSchedule(long workOrderId) {
        jdbcTemplate.update("""
                update work_order set status = 'DRAFT', schedule_date = null, schedule_seq = null,
                       scheduled_start = null, scheduled_end = null,
                       updated_at = now(), version = version + 1
                where id = ?
                """, workOrderId);
    }

    @Override
    public void swapScheduleSeq(long workOrderId, int workOrderSeq, long neighborId, int neighborSeq) {
        // 先放到临时顺序，避免交换过程中出现相同顺序
        jdbcTemplate.update("update work_order set schedule_seq = ? where id = ?", -999999, workOrderId);
        jdbcTemplate.update("update work_order set schedule_seq = ?, updated_at = now() where id = ?",
                workOrderSeq, neighborId);
        jdbcTemplate.update("""
                update work_order set schedule_seq = ?, updated_at = now(), version = version + 1
                where id = ?
                """, neighborSeq, workOrderId);
    }

    @Override
    public void scheduleOperation(long operationId, LocalDate date, LocalDateTime start, LocalDateTime end) {
        jdbcTemplate.update("""
                update work_order_operation set schedule_date = ?, scheduled_start = ?, scheduled_end = ?
                where id = ?
                """, java.sql.Date.valueOf(date),
                start == null ? null : java.sql.Timestamp.valueOf(start),
                end == null ? null : java.sql.Timestamp.valueOf(end), operationId);
    }

    @Override
    public void clearOperationSchedule(long workOrderId) {
        jdbcTemplate.update("""
                update work_order_operation set schedule_date = null, scheduled_start = null,
                       scheduled_end = null where work_order_id = ?
                """, workOrderId);
    }

    @Override
    public List<KittingCandidate> kittingCandidates(Collection<Long> materialIds) {
        List<Long> ids = distinct(materialIds);
        List<KittingCandidate> candidates = new ArrayList<>();
        if (ids.isEmpty()) {
            return candidates;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select distinct w.id, w.work_no as "workNo",
                       case w.status when 'IN_PROGRESS' then 0 when 'RELEASED' then 1 else 2 end as "rank",
                       coalesce(w.schedule_date, w.plan_start, w.created_at::date) as "dueDate"
                from work_order w
                join work_order_bom b on b.work_order_id = w.id
                where w.status in ('SCHEDULED', 'RELEASED', 'IN_PROGRESS')
                  and b.material_id in (""" + placeholders(ids.size()) + """
                  ) and b.plan_qty > b.issued_qty
                  and b.source <> 'SUBSTITUTE'
                """, ids.toArray())) {
            candidates.add(new KittingCandidate(((Number) row.get("id")).longValue(),
                    String.valueOf(row.get("workNo")),
                    ((Number) row.get("rank")).intValue(),
                    row.get("dueDate") == null
                            ? null : ((java.sql.Date) row.get("dueDate")).toLocalDate()));
        }
        return candidates;
    }

    @Override
    public List<MaterialNeed> openMaterialNeeds(Collection<Long> workOrderIds) {
        List<Long> ids = distinct(workOrderIds);
        List<MaterialNeed> needs = new ArrayList<>();
        if (ids.isEmpty()) {
            return needs;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select b.work_order_id as "workOrderId", b.material_id as "materialId",
                       (b.plan_qty - b.issued_qty) as "needQty"
                from work_order_bom b
                where b.work_order_id in (""" + placeholders(ids.size()) + """
                  ) and b.plan_qty > b.issued_qty
                  and b.source <> 'SUBSTITUTE'
                """, ids.toArray())) {
            needs.add(new MaterialNeed(((Number) row.get("workOrderId")).longValue(),
                    ((Number) row.get("materialId")).longValue(), decimalOf(row.get("needQty"))));
        }
        return needs;
    }

    @Override
    public List<ActiveMaterialNeed> activeMaterialNeeds() {
        List<ActiveMaterialNeed> needs = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select w.id as "workOrderId", w.work_no as "workNo", w.product_id as "productId",
                       b.material_id as "materialId", (b.plan_qty - b.issued_qty) as "needQty"
                from work_order w
                join work_order_bom b on b.work_order_id = w.id
                where w.status in ('RELEASED', 'IN_PROGRESS')
                  and b.plan_qty > b.issued_qty
                  and b.source <> 'SUBSTITUTE'
                order by w.id
                """)) {
            needs.add(new ActiveMaterialNeed(((Number) row.get("workOrderId")).longValue(),
                    row.get("workNo") == null ? null : String.valueOf(row.get("workNo")),
                    ((Number) row.get("productId")).longValue(),
                    ((Number) row.get("materialId")).longValue(), decimalOf(row.get("needQty"))));
        }
        return needs;
    }

    private Integer scheduleSeq(LocalDate date, Long excludeWorkOrderId, String aggregate) {
        String sql = "select " + aggregate + "(schedule_seq) from work_order"
                + " where schedule_date = ? and schedule_seq is not null"
                + (excludeWorkOrderId == null ? "" : " and id <> ?");
        return excludeWorkOrderId == null
                ? jdbcTemplate.queryForObject(sql, Integer.class, java.sql.Date.valueOf(date))
                : jdbcTemplate.queryForObject(sql, Integer.class,
                        java.sql.Date.valueOf(date), excludeWorkOrderId);
    }

    private static ScheduleOrder scheduleOrderOf(Map<String, Object> row) {
        return new ScheduleOrder(((Number) row.get("id")).longValue(),
                row.get("work_no") == null ? null : String.valueOf(row.get("work_no")),
                row.get("product_id") == null ? 0L : ((Number) row.get("product_id")).longValue(),
                decimalOf(row.get("plan_qty")), decimalOf(row.get("done_qty")),
                row.get("priority") == null ? null : String.valueOf(row.get("priority")),
                row.get("status") == null ? null : String.valueOf(row.get("status")),
                row.get("sales_order_id") == null ? null : ((Number) row.get("sales_order_id")).longValue(),
                row.get("schedule_date") == null
                        ? null : ((java.sql.Date) row.get("schedule_date")).toLocalDate(),
                row.get("schedule_seq") == null ? null : ((Number) row.get("schedule_seq")).intValue(),
                toLocalDateTime(row.get("scheduled_start")), toLocalDateTime(row.get("scheduled_end")),
                toLocalDateTime(row.get("created_at")));
    }

    private static ScheduleOperation scheduleOperationOf(Map<String, Object> row) {
        return new ScheduleOperation(((Number) row.get("id")).longValue(),
                ((Number) row.get("work_order_id")).longValue(),
                row.get("seq") == null ? 0 : ((Number) row.get("seq")).intValue(),
                row.get("name") == null ? null : String.valueOf(row.get("name")),
                row.get("process_id") == null ? null : ((Number) row.get("process_id")).longValue(),
                decimalOf(row.get("standard_minutes")), decimalOf(row.get("plan_qty")),
                decimalOf(row.get("done_qty")),
                row.get("schedule_date") == null
                        ? null : ((java.sql.Date) row.get("schedule_date")).toLocalDate());
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        return null;
    }

    private static BigDecimal decimalOf(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static List<Long> distinct(Collection<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static WorkOrderRef refOf(Map<String, Object> row) {
        return new WorkOrderRef(((Number) row.get("id")).longValue(),
                row.get("work_no") == null ? null : String.valueOf(row.get("work_no")),
                row.get("status") == null ? null : String.valueOf(row.get("status")),
                row.get("work_type") == null ? null : String.valueOf(row.get("work_type")),
                row.get("product_id") == null ? 0L : ((Number) row.get("product_id")).longValue(),
                row.get("parent_work_order_id") == null
                        ? null : ((Number) row.get("parent_work_order_id")).longValue(),
                row.get("sales_order_id") == null ? null : ((Number) row.get("sales_order_id")).longValue(),
                row.get("sales_order_item_id") == null
                        ? null : ((Number) row.get("sales_order_item_id")).longValue(),
                row.get("plan_end") == null ? null : ((java.sql.Date) row.get("plan_end")).toLocalDate());
    }

    private static WorkOrderOperation operationOf(Map<String, Object> row) {
        return new WorkOrderOperation(((Number) row.get("id")).longValue(),
                ((Number) row.get("work_order_id")).longValue(),
                row.get("seq") == null ? 0 : ((Number) row.get("seq")).intValue(),
                row.get("name") == null ? null : String.valueOf(row.get("name")),
                row.get("process_id") == null ? null : ((Number) row.get("process_id")).longValue());
    }

    /**
     * @param fromSeq 返修起点工序序号；非空时只带该工序及之后的物料，
     *                前面工序的料已经固化在半成品里，不再重复领
     */
    public void snapshotBom(long workId, long productId, BigDecimal planQty, Integer fromSeq) {
        // 取当前生效 BOM，单耗已在主数据侧按基础数量折算成「每个父件用量」
        BigDecimal loss = masterData.product(productId).map(ProductInfo::lossRate).orElse(BigDecimal.ZERO);
        BigDecimal factor = planQty.multiply(BigDecimal.ONE.add(loss == null ? BigDecimal.ZERO : loss));
        for (BomLineInfo line : masterData.activeBomLines(productId)) {
            if (fromSeq != null && line.operationSeq() != null && line.operationSeq() < fromSeq) {
                continue;
            }
            BigDecimal need = line.qtyPerUnit().multiply(factor).setScale(4, RoundingMode.HALF_UP);
            jdbcTemplate.update("""
                    insert into work_order_bom (work_order_id, material_id, plan_qty, operation_seq,
                                                substitute_group, source)
                    values (?, ?, ?, ?, ?, 'STANDARD')
                    """, workId, line.materialId(), need, line.operationSeq(), line.substituteGroup());
            if (line.substituteMaterialId() != null) {
                jdbcTemplate.update("""
                        insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty,
                                                    operation_seq, substitute_for, source)
                        values (?, ?, 0, 0, ?, ?, 'SUBSTITUTE')
                        on conflict (work_order_id, material_id) do nothing
                        """, workId, line.substituteMaterialId(), line.operationSeq(), line.materialId());
            }
        }
    }

    private Map<String, Object> loadWork(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, work_no, status, plan_qty, product_id, trace_mode from work_order where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException("生产工单不存在");
        }
        return rows.get(0);
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
