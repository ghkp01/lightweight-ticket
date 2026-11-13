package com.mes.purchase;

import com.mes.approval.api.ApprovalPort;
import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.platform.audit.OperLogService;
import com.mes.platform.config.ConfigService;
import com.mes.platform.scope.OrgScopeService;
import com.mes.production.api.WorkOrderPort;
import com.mes.purchase.api.RequisitionCreated;
import com.mes.purchase.api.RequisitionDraft;
import com.mes.purchase.api.RequisitionLine;
import com.mes.purchase.api.RequisitionPort;
import com.mes.security.CurrentUser;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购申请：需求先立单，审批强度由「流程配置 → 业务开关」决定，批准后可以分次转采购单。
 *
 * <p>三档：{@code FLOW} 走审批流（节点进度在审批中心）；{@code THRESHOLD} 只按
 * {@code purchase.approval.threshold} 的金额阈值分级；{@code NONE} 提交即生效。</p>
 */
@Service
public class RequisitionService implements RequisitionPort {

    private static final List<String> SOURCES = List.of("PRODUCTION", "STOCK", "MANUAL", "URGENT");

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final ConfigService configService;
    private final OperLogService operLogService;
    private final PurchaseOrderService purchaseOrderService;
    private final NoticePort notice;
    private final OrgScopeService orgScope;
    private final MasterDataPort masterData;
    private final WorkOrderPort workOrderPort;
    private final ApprovalPort approval;
    private final TaskPort taskPort;

    public RequisitionService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                              ConfigService configService, OperLogService operLogService,
                              PurchaseOrderService purchaseOrderService,
                              NoticePort notice,
                              OrgScopeService orgScope,
                              MasterDataPort masterData,
                              WorkOrderPort workOrderPort,
                              ApprovalPort approval,
                              TaskPort taskPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.configService = configService;
        this.operLogService = operLogService;
        this.purchaseOrderService = purchaseOrderService;
        this.notice = notice;
        this.orgScope = orgScope;
        this.masterData = masterData;
        this.workOrderPort = workOrderPort;
        this.approval = approval;
        this.taskPort = taskPort;
    }

    public record RejectRequest(String reason) {
    }

    public record ConvertLine(Long requisitionItemId, BigDecimal qty, BigDecimal price) {
    }

    public record ConvertRequest(Long supplierId, Long warehouseId, LocalDate expectedDate,
                                 Boolean needInspection, String remark, List<ConvertLine> items) {
    }

    public PageResult list(String keyword, String status, String sourceType, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder where = new StringBuilder("""
                 where (r.req_no ilike ? or coalesce(r.purpose, '') ilike ?
                        or coalesce(u.real_name, r.requester) ilike ?)
                """);
        List<Object> args = new ArrayList<>(List.of(like, like, like));
        if (status != null && !status.isBlank()) {
            where.append(" and r.status = ?");
            args.add(status.trim());
        }
        if (sourceType != null && !sourceType.isBlank()) {
            where.append(" and r.source_type = ?");
            args.add(sourceType.trim());
        }
        orgScope.appendScope(where, args, "r.factory_org_id");
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from purchase_requisition r
                left join sys_user u on u.username = r.requester
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select r.id, r.req_no as "reqNo", r.source_type as "sourceType", r.requester,
                       coalesce(u.real_name, r.requester) as "requesterName", r.department,
                       r.work_order_id as "workOrderId", r.category, r.urgent,
                       r.budget_no as "budgetNo",
                       to_char(r.need_date, 'YYYY-MM-DD') as "needDate", r.purpose, r.status,
                       r.total_amount as "totalAmount",
                       (select count(*) from purchase_requisition_item i where i.req_id = r.id) as "itemCount",
                       (select coalesce(sum(i.qty), 0) from purchase_requisition_item i
                         where i.req_id = r.id) as "totalQty",
                       (select coalesce(sum(i.converted_qty), 0) from purchase_requisition_item i
                         where i.req_id = r.id) as "convertedQty",
                       r.approved_by as "approvedBy", r.reject_reason as "rejectReason",
                       r.remark, to_char(r.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from purchase_requisition r
                left join sys_user u on u.username = r.requester
                """ + where + " order by r.id desc limit ? offset ?", pageArgs.toArray());
        fillWorkNos(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    /** 引用选择专用：采购申请下拉只返回单号、用途与状态，status 支持逗号分隔多个 */
    public List<Map<String, Object>> options(String keyword, String status, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>(List.of(like, like));
        StringBuilder where = new StringBuilder(
                " where (r.req_no ilike ? or coalesce(r.purpose, '') ilike ?) ");
        List<String> statuses = splitStatus(status);
        if (!statuses.isEmpty()) {
            where.append(" and r.status in (")
                    .append(String.join(",", java.util.Collections.nCopies(statuses.size(), "?")))
                    .append(") ");
            args.addAll(statuses);
        }
        orgScope.appendScope(where, args, "r.factory_org_id");
        args.add(size);
        return jdbcTemplate.queryForList("""
                select r.id, r.req_no as "reqNo", r.purpose, r.status,
                       r.source_type as "sourceType"
                from purchase_requisition r
                """ + where + " order by r.id desc limit ?", args.toArray());
    }

    private static List<String> splitStatus(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(status.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    public Map<String, Object> summary() {
        return jdbcTemplate.queryForMap("""
                select count(*) filter (where status = 'SUBMITTED') as "pending",
                       count(*) filter (where status = 'APPROVED') as "approved",
                       count(*) filter (where status in ('APPROVED', 'PARTIAL')) as "toConvert",
                       count(*) filter (where status = 'DRAFT') as "draft",
                       coalesce(sum(total_amount) filter (
                           where status <> 'CANCELLED'
                             and created_at >= date_trunc('month', current_date)), 0) as "monthAmount"
                from purchase_requisition
                """);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select r.id, r.req_no as "reqNo", r.source_type as "sourceType", r.requester,
                       coalesce(u.real_name, r.requester) as "requesterName", r.department,
                       r.work_order_id as "workOrderId",
                       to_char(r.need_date, 'YYYY-MM-DD') as "needDate", r.purpose, r.status,
                       r.total_amount as "totalAmount", r.remark,
                       r.category, r.urgent, r.budget_no as "budgetNo",
                       r.approved_by as "approvedBy",
                       to_char(r.approved_at, 'YYYY-MM-DD HH24:MI') as "approvedAt",
                       r.reject_reason as "rejectReason",
                       to_char(r.submit_at, 'YYYY-MM-DD HH24:MI') as "submitAt",
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from purchase_requisition r
                left join sys_user u on u.username = r.requester
                where r.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2009, "采购申请不存在");
        }
        fillWorkNos(rows);
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId",
                       i.qty, i.converted_qty as "convertedQty", i.target_price as "targetPrice",
                       i.suggest_supplier_id as "suggestSupplierId", s.name as "suggestSupplierName",
                       i.need_quote as "needQuote", i.remark, i.category,
                       i.approval_status as "approvalStatus",
                       (i.qty - i.converted_qty) as "remainingQty"
                from purchase_requisition_item i
                left join supplier s on s.id = i.suggest_supplier_id
                where i.req_id = ?
                order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        detail.put("items", items);
        detail.put("orders", jdbcTemplate.queryForList("""
                select o.id, o.order_no as "orderNo", o.status, o.total_amount as "totalAmount",
                       to_char(o.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from purchase_order o
                where o.requisition_id = ?
                order by o.id desc
                """, id));
        return detail;
    }

    @Transactional
    @Override
    public RequisitionCreated create(RequisitionDraft request) {
        validate(request);
        String reqNo = codeGenerator.next("PR");
        BigDecimal total = totalOf(request.items());
        // 来源工单能定性工厂就随工单，否则按当前账号所属工厂
        Long factory = request.workOrderId() == null ? null : workOrderPort.factoryOrgId(request.workOrderId());
        if (factory == null) {
            factory = orgScope.writeFactoryOrgId();
        }
        Long id = jdbcTemplate.queryForObject("""
                insert into purchase_requisition (req_no, source_type, requester, department, work_order_id,
                                                  need_date, purpose, status, total_amount, remark,
                                                  factory_org_id, category, urgent, budget_no)
                values (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, reqNo, sourceOf(request.sourceType()), CurrentUser.name(),
                blankToNull(request.department()), request.workOrderId(), request.needDate(),
                blankToNull(request.purpose()), total, blankToNull(request.remark()), factory,
                categoryOf(request), urgentOf(request), blankToNull(request.budgetNo()));
        insertItems(id, request.items());
        operLogService.touch("REQUISITION", id, reqNo, "新建采购申请 " + reqNo
                + (request.purpose() == null ? "" : "：" + request.purpose()));
        return new RequisitionCreated(id, reqNo);
    }

    @Transactional
    public void update(long id, RequisitionDraft request) {
        Map<String, Object> row = loadForUpdate(id);
        assertStatus(row, "DRAFT", "只有草稿申请可以修改");
        validate(request);
        jdbcTemplate.update("""
                update purchase_requisition set source_type = ?, department = ?, work_order_id = ?,
                       need_date = ?, purpose = ?, total_amount = ?, remark = ?,
                       category = ?, urgent = ?, budget_no = ?
                where id = ?
                """, sourceOf(request.sourceType()), blankToNull(request.department()),
                request.workOrderId(), request.needDate(), blankToNull(request.purpose()),
                totalOf(request.items()), blankToNull(request.remark()),
                categoryOf(request), urgentOf(request), blankToNull(request.budgetNo()), id);
        jdbcTemplate.update("delete from purchase_requisition_item where req_id = ?", id);
        insertItems(id, request.items());
    }

    @Transactional
    public void submit(long id) {
        Map<String, Object> row = loadForUpdate(id);
        assertStatus(row, "DRAFT", "只有草稿申请可以提交");
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.qty, i.target_price as "targetPrice",
                       coalesce(i.category, r.category) as "category"
                from purchase_requisition_item i
                join purchase_requisition r on r.id = i.req_id
                where i.req_id = ?
                order by i.id
                """, id);
        if (items.isEmpty()) {
            throw new BizException(1002, "申请单没有明细，不能提交");
        }
        String reqNo = String.valueOf(row.get("req_no"));
        String mode = approval.modeOf("REQUISITION");
        if ("NONE".equals(mode)) {
            // 审批被关掉：提交即生效，明细行也一并视为通过
            markAllLines(id, "SKIPPED");
            jdbcTemplate.update("""
                    update purchase_requisition set status = 'APPROVED', submit_at = now(),
                           approved_by = '免审批', approved_at = now(), approval_instance_id = null,
                           reject_reason = null
                     where id = ?
                    """, id);
            notice.push(String.valueOf(row.get("requester")), NoticeMessage.of("INFO",
                    "采购申请 " + reqNo + " 已生效",
                    "该业务当前关闭审批，提交即生效，可以直接转采购单。", "REQUISITION", reqNo));
            operLogService.touch("REQUISITION", id, reqNo, "提交采购申请 " + reqNo + "（免审批）");
            operLogService.action("提交");
            return;
        }
        if ("FLOW".equals(mode) && submitByLines(id, reqNo, row, items)) {
            return;
        }
        // 金额阈值模式：也是「开了审批但一行都没命中链」时的兜底，保持原来的行为
        jdbcTemplate.update("""
                update purchase_requisition set status = 'SUBMITTED', submit_at = now(),
                       approval_instance_id = null
                 where id = ?
                """, id);
        operLogService.touch("REQUISITION", id, reqNo, "提交采购申请 " + reqNo);
        operLogService.action("提交");
        notifyApprovers(row);
    }

    /**
     * 行级分流：逐行按自己的类别与金额选链，再按命中的链分组提交。
     *
     * <p>同一组行共用一个审批实例 —— 一个审批人批一次覆盖这几行，不必一行一个待办；
     * 没命中任何链的行直接标成 SKIPPED（视为无需审批），不会卡在待审批里。</p>
     *
     * @return 是否真的产生了审批实例；false 表示没有任何行需要审批，交给上层兜底
     */
    private boolean submitByLines(long id, String reqNo, Map<String, Object> head,
                                  List<Map<String, Object>> items) {
        Map<Long, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        List<Long> skipped = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Long flowId = approval.matchFlow("REQUISITION", id, reqNo, lineVariables(head, item));
            long lineId = ((Number) item.get("id")).longValue();
            if (flowId == null) {
                skipped.add(lineId);
            } else {
                groups.computeIfAbsent(flowId, key -> new ArrayList<>()).add(item);
            }
        }
        if (groups.isEmpty()) {
            return false;
        }
        if (!skipped.isEmpty()) {
            setLineStatus(id, skipped, "SKIPPED");
        }
        boolean started = false;
        for (Map.Entry<Long, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> lines = entry.getValue();
            List<Long> lineIds = new ArrayList<>();
            for (Map<String, Object> line : lines) {
                lineIds.add(((Number) line.get("id")).longValue());
            }
            Map<String, Object> context = groupVariables(head, lines, lineIds);
            String title = "采购申请 " + reqNo + "（" + context.get("category") + "，"
                    + lines.size() + " 行）";
            Long instanceId = approval.submitWithFlow("REQUISITION", id, reqNo, title,
                    entry.getKey(), context);
            if (instanceId == null) {
                // 链被停用或还没部署：这几行按无需审批处理，不卡单
                setLineStatus(id, lineIds, "SKIPPED");
                continue;
            }
            started = true;
            jdbcTemplate.update("""
                    update purchase_requisition_item
                       set approval_status = 'PENDING', approval_instance_id = ?
                     where req_id = ? and id in (%s)
                    """.formatted(placeholders(lineIds.size())),
                    mergeArgs(instanceId, id, lineIds));
        }
        if (!started) {
            return false;
        }
        jdbcTemplate.update("""
                update purchase_requisition set status = 'SUBMITTED', submit_at = now(),
                       approval_instance_id = null
                 where id = ?
                """, id);
        operLogService.touch("REQUISITION", id, reqNo,
                "提交采购申请 " + reqNo + "（行级分流，" + groups.size() + " 条审批链）");
        operLogService.action("提交");
        return true;
    }

    /** 行级变量：金额取「本行数量 × 单价」，类别取行上的，其余跟随单据头 */
    private Map<String, Object> lineVariables(Map<String, Object> head, Map<String, Object> item) {
        BigDecimal qty = decimal(item.get("qty"));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("amount", qty.multiply(decimal(item.get("targetPrice"))));
        variables.put("qty", qty);
        variables.put("itemCount", 1);
        variables.put("category", categoryValue(item.get("category")));
        variables.put("sourceType", String.valueOf(head.get("source_type")));
        Object urgent = head.get("urgent");
        variables.put("urgent", urgent instanceof Boolean flag
                ? flag : "URGENT".equals(String.valueOf(head.get("source_type"))));
        variables.put("department", head.get("department") == null
                ? "" : String.valueOf(head.get("department")));
        variables.put("budgetNo", head.get("budget_no") == null
                ? "" : String.valueOf(head.get("budget_no")));
        return variables;
    }

    /** 同一条链上的几行合成一个实例：金额与数量按组合计，类别混合时标 MIXED */
    private Map<String, Object> groupVariables(Map<String, Object> head, List<Map<String, Object>> lines,
                                               List<Long> lineIds) {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        java.util.Set<String> categories = new java.util.LinkedHashSet<>();
        for (Map<String, Object> line : lines) {
            BigDecimal lineQty = decimal(line.get("qty"));
            qty = qty.add(lineQty);
            amount = amount.add(lineQty.multiply(decimal(line.get("targetPrice"))));
            categories.add(categoryValue(line.get("category")));
        }
        Map<String, Object> variables = lineVariables(head, lines.get(0));
        variables.put("amount", amount);
        variables.put("qty", qty);
        variables.put("itemCount", lines.size());
        variables.put("category", categories.size() == 1 ? categories.iterator().next() : "MIXED");
        variables.put("lineIds", joinIds(lineIds));
        return variables;
    }

    private void setLineStatus(long reqId, List<Long> lineIds, String status) {
        if (lineIds == null || lineIds.isEmpty()) {
            return;
        }
        jdbcTemplate.update("update purchase_requisition_item set approval_status = ? "
                + "where req_id = ? and id in (" + placeholders(lineIds.size()) + ")",
                mergeArgs(status, reqId, lineIds));
    }

    private void markAllLines(long reqId, String status) {
        jdbcTemplate.update(
                "update purchase_requisition_item set approval_status = ? where req_id = ?", status, reqId);
    }

    private static String categoryValue(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? "MATERIAL" : String.valueOf(value);
    }

    private static String joinIds(List<Long> ids) {
        StringBuilder builder = new StringBuilder();
        for (Long id : ids) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] mergeArgs(Object first, long reqId, List<Long> ids) {
        Object[] args = new Object[ids.size() + 2];
        args[0] = first;
        args[1] = reqId;
        for (int index = 0; index < ids.size(); index++) {
            args[index + 2] = ids.get(index);
        }
        return args;
    }

    /** 提交后推给审批人：超阈值只有管理员能批，否则计划与管理都提示 */
    private void notifyApprovers(Map<String, Object> row) {
        BigDecimal amount = decimal(row.get("total_amount"));
        int threshold = configService.intValue("purchase.approval.threshold", 5000);
        boolean needAdmin = amount.compareTo(BigDecimal.valueOf(threshold)) > 0;
        String reqNo = String.valueOf(row.get("req_no"));
        String content = "申请人 " + row.get("requester") + "，金额 "
                + amount.stripTrailingZeros().toPlainString() + " 元"
                + (needAdmin ? "，超过审批阈值 " + threshold + " 元需管理员审批" : "");
        notice.pushToRoles(needAdmin ? List.of("ADMIN") : List.of("PLANNER", "ADMIN"),
                NoticeMessage.of("TASK",
                        "采购申请 " + reqNo + " 待审批", content, "REQUISITION", reqNo));
    }

    @Transactional
    public void approve(long id) {
        Map<String, Object> row = loadForUpdate(id);
        assertStatus(row, "SUBMITTED", "只有待审批的申请可以审批");
        assertNotInFlow(row);
        BigDecimal amount = decimal(row.get("total_amount"));
        int threshold = configService.intValue("purchase.approval.threshold", 5000);
        if (amount.compareTo(BigDecimal.valueOf(threshold)) > 0
                && !"ADMIN".equals(CurrentUser.role())) {
            throw new BizException(403, "申请金额 " + amount.stripTrailingZeros().toPlainString()
                    + " 元超过审批阈值 " + threshold + " 元，需要管理员审批");
        }
        jdbcTemplate.update("""
                update purchase_requisition set status = 'APPROVED', approved_by = ?, approved_at = now(),
                       reject_reason = null
                where id = ?
                """, CurrentUser.name(), id);
        // 阈值审批也把行状态带上，否则转采购单时会被「这一行还没批」挡住
        markAllLines(id, "APPROVED");
        operLogService.touch("REQUISITION", id, String.valueOf(row.get("req_no")),
                "审批通过采购申请 " + row.get("req_no"));
        operLogService.action("审批");
        String reqNo = String.valueOf(row.get("req_no"));
        notice.push(String.valueOf(row.get("requester")), NoticeMessage.of("INFO",
                "采购申请 " + reqNo + " 已批准",
                "审批人 " + CurrentUser.name() + "，可以转采购单了", "REQUISITION", reqNo));
    }

    @Transactional
    public void reject(long id, RejectRequest request) {
        Map<String, Object> row = loadForUpdate(id);
        assertStatus(row, "SUBMITTED", "只有待审批的申请可以驳回");
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException(1002, "驳回必须填写原因");
        }
        jdbcTemplate.update("""
                update purchase_requisition set status = 'REJECTED', approved_by = ?, approved_at = now(),
                       reject_reason = ?
                where id = ?
                """, CurrentUser.name(), reason, id);
        markAllLines(id, "REJECTED");
        operLogService.touch("REQUISITION", id, String.valueOf(row.get("req_no")),
                "驳回采购申请 " + row.get("req_no") + "：" + reason);
        operLogService.action("驳回");
        String reqNo = String.valueOf(row.get("req_no"));
        taskPort.close(TaskTypes.REQ_CONVERT, reqNo, CurrentUser.name(), "申请被驳回，不用再转采购单");
        notice.push(String.valueOf(row.get("requester")), NoticeMessage.of("ALERT",
                "采购申请 " + reqNo + " 被驳回",
                "驳回原因：" + reason + "，请修改后重新提交", "REQUISITION", reqNo));
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> row = loadForUpdate(id);
        String status = String.valueOf(row.get("status"));
        if (!List.of("DRAFT", "SUBMITTED", "APPROVED").contains(status)) {
            throw new BizException(2014, "当前状态不允许取消");
        }
        BigDecimal converted = jdbcTemplate.queryForObject("""
                select coalesce(sum(converted_qty), 0) from purchase_requisition_item where req_id = ?
                """, BigDecimal.class, id);
        if (converted != null && converted.signum() > 0) {
            throw new BizException(2014, "已经转过采购单，不能取消申请");
        }
        jdbcTemplate.update("update purchase_requisition set status = 'CANCELLED' where id = ?", id);
        taskPort.close(TaskTypes.REQ_CONVERT, String.valueOf(row.get("req_no")), CurrentUser.name(),
                "申请已取消，转采购单任务作废");
        operLogService.touch("REQUISITION", id, String.valueOf(row.get("req_no")),
                "取消采购申请 " + row.get("req_no"));
        operLogService.action("取消");
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> row = loadForUpdate(id);
        assertStatus(row, "DRAFT", "只有草稿申请可以删除");
        jdbcTemplate.update("delete from purchase_requisition_item where req_id = ?", id);
        jdbcTemplate.update("delete from purchase_requisition where id = ?", id);
        taskPort.close(TaskTypes.REQ_CONVERT, String.valueOf(row.get("req_no")), CurrentUser.name(),
                "申请已删除，转采购单任务作废");
    }

    /**
     * 转采购单：按申请行剩余数量生成一张草稿采购单，可多次转，全部转完申请状态变已转单。
     *
     * <p>按行放行：只转「已批准（或未命中审批链）」的行，所以审批中的单据也能先把
     * 批完的那几行转掉，不必等整单批完；没指定行时默认把可转的行全带上。</p>
     */
    @Transactional
    public Map<String, Object> toOrder(long id, ConvertRequest request) {
        Map<String, Object> row = loadForUpdate(id);
        String status = String.valueOf(row.get("status"));
        if (!List.of("APPROVED", "PARTIAL", "SUBMITTED").contains(status)) {
            throw new BizException(2014, "只有已批准或部分转单的申请可以转采购单");
        }
        if (request == null || request.supplierId() == null || request.warehouseId() == null) {
            throw new BizException(1002, "请选择供应商与收货仓库");
        }
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id, i.qty, i.converted_qty, i.target_price, i.approval_status
                from purchase_requisition_item i
                where i.req_id = ?
                order by i.id
                """, id);
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            byId.put(((Number) item.get("id")).longValue(), item);
        }
        List<ConvertLine> requested = new ArrayList<>();
        for (ConvertLine line : request.items() == null ? List.<ConvertLine>of() : request.items()) {
            if (line != null && line.requisitionItemId() != null) {
                requested.add(line);
            }
        }
        if (requested.isEmpty()) {
            // 没指定行：把已批准且还有余量的行全部带上
            for (Map<String, Object> item : items) {
                BigDecimal remaining = decimal(item.get("qty")).subtract(decimal(item.get("converted_qty")));
                if (convertible(item, status) && remaining.signum() > 0) {
                    requested.add(new ConvertLine(((Number) item.get("id")).longValue(), remaining, null));
                }
            }
        }
        List<PurchaseOrderService.ItemRequest> poItems = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ConvertLine line : requested) {
            Map<String, Object> item = byId.get(line.requisitionItemId());
            if (item == null) {
                throw new BizException(1002, "申请明细不属于这张申请单");
            }
            if (!convertible(item, status)) {
                throw new BizException(2014, "有明细还没审批通过，这几行不能转采购单");
            }
            BigDecimal remaining = decimal(item.get("qty")).subtract(decimal(item.get("converted_qty")));
            BigDecimal qty = line.qty() == null ? remaining : line.qty();
            if (qty.signum() <= 0) {
                continue;
            }
            if (qty.compareTo(remaining) > 0) {
                throw new BizException(1002, "转单数量超过申请剩余数量");
            }
            BigDecimal price = line.price() == null ? decimal(item.get("target_price")) : line.price();
            poItems.add(new PurchaseOrderService.ItemRequest(
                    ((Number) item.get("product_id")).longValue(), qty, price, null,
                    line.requisitionItemId()));
            lines.add(Map.of("itemId", line.requisitionItemId(), "qty", qty));
        }
        if (poItems.isEmpty()) {
            throw new BizException(1002, "没有可转采购单的明细：已批准的行都已转完");
        }
        String reqNo = String.valueOf(row.get("req_no"));
        String remark = request.remark() == null || request.remark().isBlank()
                ? "由采购申请 " + reqNo + " 转入" : request.remark().trim();
        long orderId = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                request.supplierId(), request.warehouseId(), LocalDate.now(), request.expectedDate(),
                request.needInspection(), remark, poItems, id));
        for (Map<String, Object> line : lines) {
            jdbcTemplate.update("""
                    update purchase_requisition_item set converted_qty = converted_qty + ? where id = ?
                    """, line.get("qty"), line.get("itemId"));
        }
        refreshStatus(id, status);
        closeConvertTaskIfDone(id, reqNo);
        String orderNo = jdbcTemplate.queryForObject(
                "select order_no from purchase_order where id = ?", String.class, orderId);
        operLogService.touch("REQUISITION", id, reqNo,
                "采购申请 " + reqNo + " 转采购单 " + orderNo);
        operLogService.action("转采购");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("orderNo", orderNo);
        result.put("status", jdbcTemplate.queryForObject(
                "select status from purchase_requisition where id = ?", String.class, id));
        return result;
    }

    /**
     * 转采购单任务收口：可转的行都转完了就关，哪怕剩下的是被驳回的行。
     *
     * <p>申请还在审批中（或草稿）时不能关，否则审批人一批准，任务已经没了。</p>
     */
    private void closeConvertTaskIfDone(long id, String reqNo) {
        String status = jdbcTemplate.queryForObject(
                "select status from purchase_requisition where id = ?", String.class, id);
        if ("SUBMITTED".equals(status) || "DRAFT".equals(status)) {
            return;
        }
        Integer remaining = jdbcTemplate.queryForObject("""
                select count(*) from purchase_requisition_item i
                join purchase_requisition r on r.id = i.req_id
                where i.req_id = ? and i.qty > i.converted_qty
                  and (i.approval_status in ('APPROVED', 'SKIPPED')
                       or (i.approval_status = 'PENDING' and r.status in ('APPROVED', 'PARTIAL')))
                """, Integer.class, id);
        if (remaining == null || remaining > 0) {
            return;
        }
        taskPort.close(TaskTypes.REQ_CONVERT, reqNo, CurrentUser.name(),
                "已按批准的明细转完采购单");
    }

    /** 这一行能不能转采购单：审批通过（或无需审批）且还有余量 */
    private static boolean convertible(Map<String, Object> item, String headerStatus) {
        String approval = item.get("approval_status") == null
                ? "" : String.valueOf(item.get("approval_status"));
        if ("APPROVED".equals(approval) || "SKIPPED".equals(approval)) {
            return true;
        }
        // 存量单：阈值审批那条路径以前不回写行状态，按单据已批准处理，不能把老单卡死
        return "PENDING".equals(approval) && List.of("APPROVED", "PARTIAL").contains(headerStatus);
    }

    /** 重算单据状态：还在审批就保持待审批；批完或老路径再按转单进度算 */
    private void refreshStatus(long id, String headerStatus) {
        if ("SUBMITTED".equals(headerStatus)) {
            Integer pending = jdbcTemplate.queryForObject("""
                    select count(*) from purchase_requisition_item
                    where req_id = ? and approval_status = 'PENDING'
                    """, Integer.class, id);
            if (pending != null && pending > 0) {
                // 还有行在审批，状态不动，等行级回写自己推进
                return;
            }
            jdbcTemplate.update("""
                    update purchase_requisition r
                    set status = case when s.converted >= s.approved then 'CONVERTED'
                                      when s.converted > 0 then 'PARTIAL'
                                      else 'APPROVED' end
                    from (select coalesce(sum(qty) filter (where approval_status in ('APPROVED', 'SKIPPED')), 0) as approved,
                                 coalesce(sum(converted_qty), 0) as converted
                          from purchase_requisition_item where req_id = ?) s
                    where r.id = ? and r.status = 'SUBMITTED'
                    """, id, id);
            return;
        }
        // 阈值审批那条老路径：没有行级状态，按总数量算转单进度
        jdbcTemplate.update("""
                update purchase_requisition r
                set status = case when s.converted >= s.total then 'CONVERTED'
                                  when s.converted > 0 then 'PARTIAL'
                                  else 'APPROVED' end
                from (select coalesce(sum(qty), 0) as total, coalesce(sum(converted_qty), 0) as converted
                      from purchase_requisition_item where req_id = ?) s
                where r.id = ? and r.status in ('APPROVED', 'PARTIAL', 'CONVERTED')
                """, id, id);
    }

    private void insertItems(long reqId, List<RequisitionLine> items) {
        for (RequisitionLine item : items) {
            jdbcTemplate.update("""
                    insert into purchase_requisition_item (req_id, product_id, qty, converted_qty,
                                                           target_price, suggest_supplier_id, need_quote,
                                                           remark, category)
                    values (?, ?, ?, 0, ?, ?, ?, ?, ?)
                    """, reqId, item.productId(), item.qty(),
                    item.targetPrice() == null ? BigDecimal.ZERO : item.targetPrice(),
                    item.suggestSupplierId(), Boolean.TRUE.equals(item.needQuote()),
                    blankToNull(item.remark()), lineCategory(item.category()));
        }
    }

    /** 行级类别留空表示跟随单据头；填了就必须是白名单里的值 */
    private static String lineCategory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CATEGORIES.contains(upper)) {
            throw new BizException(1002, "明细行的采购类别只能是 " + String.join(" / ", CATEGORIES));
        }
        return upper;
    }

    private BigDecimal totalOf(List<RequisitionLine> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (RequisitionLine item : items) {
            BigDecimal price = item.targetPrice() == null ? BigDecimal.ZERO : item.targetPrice();
            total = total.add(item.qty().multiply(price).setScale(2, RoundingMode.HALF_UP));
        }
        return total;
    }

    private void validate(RequisitionDraft request) {
        if (!SOURCES.contains(sourceOf(request.sourceType()))) {
            throw new BizException(1002, "申请来源只能是 PRODUCTION / STOCK / MANUAL / URGENT");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少添加一条申请明细");
        }
        if (request.needDate() != null && request.needDate().isBefore(LocalDate.now().minusDays(1))) {
            throw new BizException(1002, "需求日期不能早于今天");
        }
        if (request.workOrderId() != null && !workOrderPort.exists(request.workOrderId())) {
            throw new BizException(1002, "关联工单不存在");
        }
        for (RequisitionLine item : request.items()) {
            if (item.productId() == null) {
                throw new BizException(1002, "申请明细必须选择物料");
            }
            if (item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "申请数量必须大于 0");
            }
            if (item.targetPrice() != null && item.targetPrice().signum() < 0) {
                throw new BizException(1002, "目标单价不能为负数");
            }
            if (!masterData.productExists(item.productId())) {
                throw new BizException(1002, "申请明细中的物料不存在");
            }
            if (item.suggestSupplierId() != null) {
                Integer supplier = jdbcTemplate.queryForObject(
                        "select count(*) from supplier where id = ?", Integer.class, item.suggestSupplierId());
                if (supplier == null || supplier == 0) {
                    throw new BizException(1002, "建议供应商不存在");
                }
            }
        }
    }

    /** 补来源工单号：工单属于生产模块，按 id 批量问一次，避免列表里退化成 N+1 */
    private void fillWorkNos(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> row.get("workOrderId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> workNos = workOrderPort.workNos(ids);
        for (Map<String, Object> row : rows) {
            Object id = row.get("workOrderId");
            if (id != null) {
                row.put("workNo", workNos.get(((Number) id).longValue()));
            }
        }
    }

    private Map<String, Object> loadForUpdate(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, req_no, status, total_amount, requester, department, source_type,
                       approval_instance_id, category, urgent, budget_no
                from purchase_requisition where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2009, "采购申请不存在");
        }
        return rows.get(0);
    }

    private void assertStatus(Map<String, Object> row, String expected, String message) {
        if (!expected.equals(String.valueOf(row.get("status")))) {
            throw new BizException(2014, message);
        }
    }

    /** 进了审批流的单据只能在审批中心批，避免有人绕过节点直接改状态 */
    private void assertNotInFlow(Map<String, Object> row) {
        long reqId = ((Number) row.get("id")).longValue();
        // 行级分流时单据头不记单一实例，所以还要问一下有没有在途实例
        if (row.get("approval_instance_id") != null || approval.hasPending("REQUISITION", reqId)) {
            throw new BizException(2015, "这张申请在审批流里，请到审批中心处理");
        }
    }

    private static String sourceOf(String sourceType) {
        return sourceType == null || sourceType.isBlank()
                ? "MANUAL" : sourceType.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static final List<String> CATEGORIES =
            List.of("MATERIAL", "OUTSOURCE", "SPARE", "EQUIPMENT", "SERVICE");

    /** 采购类别决定走哪条审批链，所以非法值直接拒掉，不静默兜底 */
    private static String categoryOf(RequisitionDraft request) {
        String value = request.category() == null
                ? "" : request.category().trim().toUpperCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            return "MATERIAL";
        }
        if (!CATEGORIES.contains(value)) {
            throw new BizException(1002, "采购类别只能是 " + String.join(" / ", CATEGORIES));
        }
        return value;
    }

    /** 没显式勾紧急时，跟随来源：紧急采购视为紧急 */
    private static boolean urgentOf(RequisitionDraft request) {
        if (request.urgent() != null) {
            return request.urgent();
        }
        return "URGENT".equals(sourceOf(request.sourceType()));
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
