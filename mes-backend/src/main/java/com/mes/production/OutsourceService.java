package com.mes.production;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.cost.api.CostPort;
import com.mes.inventory.api.BatchMove;
import com.mes.inventory.api.InventoryPort;
import com.mes.platform.scope.OrgScopeService;
import com.mes.quality.api.DefectPort;
import com.mes.quality.api.DefectRequest;
import com.mes.quality.api.ProcessInspectionPort;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工序外协：把工单里某道工序发到外协厂做，并对「发出 → 收回」全过程留痕。
 *
 * <p><b>为什么做成「工序 + 一张单」而不是独立的外协模块</b>：外协改的只是这道工序在哪儿做，
 * 前后的工序、BOM、件码都不变。所以外协属性挂在 {@code work_order_operation} 上，
 * 一张 {@code outsource_order} 记录「发出去做」这个动作，收回也登记回同一张单。
 * 中途转外协，就是给在制工单的某道工序点一下「转外协」。</p>
 *
 * <p><b>料怎么走</b>（这是「领料不用在家里领」的落点）：</p>
 * <ul>
 *   <li>转外协后，这道工序的 BOM 行不再出现在本厂领料清单里（{@link WorkOrderIssueService} 会跳过）；</li>
 *   <li>发出时如果这道工序的料还没领，就从原料仓按批次扣减发给外协厂，并回写
 *       {@code work_order_bom.issued_qty} —— 需求已满足，整单领料不会再来要一遍；</li>
 *   <li>如果料早就领到线边了，发出只登记「随件转出」，不重复扣库存（{@code from_stock = false}）。</li>
 * </ul>
 *
 * <p><b>量怎么算</b>：外协收回**不写 work_report**。报工表是「厂内工人工作量」的凭据，
 * 外协干的活写进去会污染计件工资、工人绩效与人工成本三处。产量与合格数记在外协单上，
 * 工序完成数照常累加，工单进度与完工判定沿用既有逻辑。</p>
 */
@Service
public class OutsourceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final List<String> OPEN_STATUS = List.of("SENT", "PARTIAL");

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventory;
    private final DefectPort defectWriter;
    private final ProcessInspectionPort processInspections;
    private final CostPort costService;
    private final OrgScopeService orgScope;

    public OutsourceService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                            InventoryPort inventory, DefectPort defectWriter,
                            ProcessInspectionPort processInspections,
                            CostPort costService, OrgScopeService orgScope) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventory = inventory;
        this.defectWriter = defectWriter;
        this.processInspections = processInspections;
        this.costService = costService;
        this.orgScope = orgScope;
    }

    // ---------------------------------------------------------------- 入参

    /** 转外协：谁做、什么价、什么时候回来 */
    public record PlanRequest(Long supplierId, BigDecimal price, String expectedAt, String remark) {
    }

    /** 发出时的一行用料 */
    public record SendItem(Long materialId, BigDecimal qty) {
    }

    /** 发出：本次做多少件、发哪些料 */
    public record SendRequest(BigDecimal qty, Long supplierId, BigDecimal price, String expectedAt,
                              Long warehouseId, String sender, String remark, List<SendItem> items) {
    }

    /** 收回：回来多少、坏了几件、检验结论 */
    public record ReceiveRequest(BigDecimal qty, BigDecimal defectQty, String receiver, String remark,
                                 String inspectionResult, String inspectionRemark) {
    }

    // ---------------------------------------------------------------- 转外协

    /** 把某道工序转成外协（还没发料）。中途外协就是从这一步开始。 */
    @Transactional
    public Map<String, Object> plan(long workOrderId, int seq, PlanRequest request) {
        Map<String, Object> work = loadWork(workOrderId);
        requireExecutable(work);
        Map<String, Object> op = loadOperation(workOrderId, seq, true);
        String outsourceStatus = op.get("outsource_status") == null
                ? null : String.valueOf(op.get("outsource_status"));
        if ("SENT".equals(outsourceStatus)) {
            throw new BizException(2030, "该工序已发出外协，请先收回再调整外协信息");
        }
        if (decimal(op.get("done_qty")).compareTo(decimal(op.get("plan_qty"))) >= 0) {
            throw new BizException(2031, "该工序已完工，不需要外协");
        }
        Long supplierId = request == null ? null : request.supplierId();
        if (supplierId == null && op.get("outsource_supplier_id") != null) {
            supplierId = ((Number) op.get("outsource_supplier_id")).longValue();
        }
        if (supplierId != null) {
            assertSupplier(supplierId);
        }
        BigDecimal price = request != null && request.price() != null
                ? nonNegative(request.price(), "外协单价")
                : decimal(op.get("outsource_price"));
        Date expected = parseDate(request == null ? null : request.expectedAt());
        if (expected == null && op.get("outsource_expected_at") != null) {
            expected = Date.valueOf(String.valueOf(op.get("outsource_expected_at")));
        }
        jdbcTemplate.update("""
                update work_order_operation
                set outsource_status = 'PENDING_SEND', outsource_supplier_id = ?,
                    outsource_price = ?, outsource_expected_at = ?
                where id = ?
                """, supplierId, price, expected, op.get("id"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workOrderId", workOrderId);
        result.put("operationSeq", seq);
        result.put("outsourceStatus", "PENDING_SEND");
        result.put("supplierId", supplierId);
        result.put("price", price);
        result.put("expectedAt", expected == null ? null : expected.toString());
        result.put("remark", request == null ? null : trimToNull(request.remark()));
        return result;
    }

    /** 撤销转外协：只在还没发出过的时候允许，料和库存都没动过 */
    @Transactional
    public void cancelPlan(long workOrderId, int seq) {
        loadWork(workOrderId);
        Map<String, Object> op = loadOperation(workOrderId, seq, true);
        String outsourceStatus = op.get("outsource_status") == null
                ? null : String.valueOf(op.get("outsource_status"));
        if (!"PENDING_SEND".equals(outsourceStatus)) {
            throw new BizException(2032, "只有「已转外协待发出」的工序可以撤销");
        }
        Integer orders = jdbcTemplate.queryForObject("""
                select count(*) from outsource_order where operation_id = ?
                """, Integer.class, op.get("id"));
        if (orders != null && orders > 0) {
            throw new BizException(2032, "该工序已有外协单，不能撤销，请按收回流程处理");
        }
        jdbcTemplate.update("""
                update work_order_operation
                set outsource_status = null, outsource_supplier_id = null, outsource_price = 0,
                    outsource_expected_at = null
                where id = ?
                """, op.get("id"));
    }

    // ---------------------------------------------------------------- 发出

    /** 待发料清单：这道工序还需要多少料、本厂仓里有多少 */
    public Map<String, Object> materials(long workOrderId, int seq) {
        Map<String, Object> op = loadOperation(workOrderId, seq, false);
        long warehouseId = rawWarehouse();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select b.material_id as "materialId", m.code as "materialCode", m.name as "materialName",
                       m.spec, m.unit, b.plan_qty as "needQty", b.issued_qty as "issuedQty",
                       greatest(0, b.plan_qty - b.issued_qty) as "remainingQty"
                from work_order_bom b
                join rm_product m on m.id = b.material_id
                where b.work_order_id = ? and b.operation_seq = ? and b.source <> 'SUBSTITUTE'
                order by b.id
                """, workOrderId, seq);
        BigDecimal total = ZERO;
        for (Map<String, Object> row : rows) {
            long materialId = ((Number) row.get("materialId")).longValue();
            BigDecimal stock = inventory.stock(materialId, warehouseId);
            BigDecimal remaining = decimal(row.get("remainingQty"));
            row.put("stockQty", stock);
            // 料还没领才需要从仓库发；已领足的随件转出，不再扣库存
            row.put("fromStock", remaining.signum() > 0);
            row.put("sendQty", remaining.signum() > 0 ? remaining : decimal(row.get("needQty")));
            row.put("stockEnough", remaining.signum() <= 0 || stock.compareTo(remaining) >= 0);
            total = total.add(remaining.signum() > 0 ? remaining : ZERO);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationSeq", seq);
        result.put("operationName", op.get("name"));
        result.put("planQty", op.get("plan_qty"));
        result.put("doneQty", op.get("done_qty"));
        result.put("outsourceStatus", op.get("outsource_status"));
        result.put("supplierId", op.get("outsource_supplier_id"));
        result.put("price", op.get("outsource_price"));
        result.put("expectedAt", op.get("outsource_expected_at"));
        result.put("warehouseId", warehouseId);
        result.put("stockTotalQty", total);
        result.put("lines", rows);
        return result;
    }

    /** 发出：生成外协单、把该工序的料发给外协厂（没领过的才扣库存） */
    @Transactional
    public Map<String, Object> send(long workOrderId, int seq, SendRequest request) {
        if (request == null || request.qty() == null || request.qty().signum() <= 0) {
            throw new BizException(1002, "请填写本次外协数量");
        }
        Map<String, Object> work = loadWork(workOrderId);
        requireExecutable(work);
        Map<String, Object> op = loadOperation(workOrderId, seq, true);
        String outsourceStatus = op.get("outsource_status") == null
                ? null : String.valueOf(op.get("outsource_status"));
        if ("SENT".equals(outsourceStatus)) {
            throw new BizException(2030, "该工序已有一张未收回的外协单，请先收回");
        }
        if (decimal(op.get("done_qty")).compareTo(decimal(op.get("plan_qty"))) >= 0) {
            throw new BizException(2031, "该工序已完工，不需要外协");
        }
        requirePreviousDone(workOrderId, seq);

        BigDecimal already = openQty(op.get("id"));
        BigDecimal planQty = decimal(op.get("plan_qty"));
        BigDecimal qty = request.qty();
        if (already.add(qty).compareTo(planQty) > 0) {
            throw new BizException(2033, "外协数量超过工序计划量，最多还能发 "
                    + planQty.subtract(already).stripTrailingZeros().toPlainString());
        }

        Long supplierId = request.supplierId();
        if (supplierId == null && op.get("outsource_supplier_id") != null) {
            supplierId = ((Number) op.get("outsource_supplier_id")).longValue();
        }
        if (supplierId == null) {
            throw new BizException(1002, "请选择外协供应商");
        }
        assertSupplier(supplierId);
        BigDecimal price = request.price() != null
                ? nonNegative(request.price(), "外协单价")
                : decimal(op.get("outsource_price"));
        Date expected = parseDate(request.expectedAt());
        if (expected == null && op.get("outsource_expected_at") != null) {
            expected = Date.valueOf(String.valueOf(op.get("outsource_expected_at")));
        }

        long warehouseId = request.warehouseId() == null ? rawWarehouse() : request.warehouseId();
        List<SendItem> items = request.items() == null || request.items().isEmpty()
                ? defaultItems(workOrderId, seq)
                : request.items();
        if (items.isEmpty()) {
            throw new BizException(2034, "该工序没有可发出的料，也没填发料明细");
        }

        String operator = CurrentUser.name();
        String sender = trimToNull(request.sender());
        if (sender == null) {
            sender = operator;
        }
        String orderNo = codeGenerator.next("OS");
        Long factoryOrgId = work.get("factory_org_id") == null
                ? orgScope.writeFactoryOrgId()
                : ((Number) work.get("factory_org_id")).longValue();
        String workNo = String.valueOf(work.get("work_no"));
        long orderId = jdbcTemplate.queryForObject("""
                insert into outsource_order (order_no, work_order_id, operation_id, operation_seq,
                                             supplier_id, status, qty, price, expected_at, sender,
                                             remark, factory_org_id)
                values (?, ?, ?, ?, ?, 'SENT', ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, orderNo, workOrderId, op.get("id"), seq, supplierId, qty, price,
                expected, cut(sender, 40), trimToNull(request.remark()), factoryOrgId);

        List<Map<String, Object>> sentLines = new ArrayList<>();
        for (SendItem item : items) {
            if (item == null || item.materialId() == null || item.qty() == null
                    || item.qty().signum() <= 0) {
                throw new BizException(1002, "发料明细的物料与数量不能为空，数量必须大于 0");
            }
            BigDecimal remaining = remainingOf(workOrderId, seq, item.materialId());
            BigDecimal sendQty = item.qty();
            if (remaining.signum() <= 0) {
                // 料早就领到线边了，随件转出，不重复扣库存
                jdbcTemplate.update("""
                        insert into outsource_order_item (order_id, material_id, qty, warehouse_id,
                                                          batch_summary, from_stock)
                        values (?, ?, ?, ?, ?, false)
                        """, orderId, item.materialId(), sendQty, warehouseId, "已领料，随件转出");
                sentLines.add(line(item.materialId(), sendQty, false, "已领料，随件转出"));
                continue;
            }
            if (sendQty.compareTo(remaining) > 0) {
                throw new BizException(2035, materialName(item.materialId())
                        + " 超发：该工序还剩 " + remaining.stripTrailingZeros().toPlainString()
                        + " 未领");
            }
            List<BatchMove> moves = inventory.consumeFifo(item.materialId(), warehouseId, sendQty,
                    "OUTSOURCE", "OUTSOURCE", workNo, "外协发出 " + orderNo);
            String summary = summarizeBatches(moves);
            jdbcTemplate.update("""
                    insert into outsource_order_item (order_id, material_id, qty, warehouse_id,
                                                      batch_summary, from_stock)
                    values (?, ?, ?, ?, ?, true)
                    """, orderId, item.materialId(), sendQty, warehouseId, summary);
            markIssued(workOrderId, item.materialId(), sendQty);
            sentLines.add(line(item.materialId(), sendQty, true, summary));
        }

        jdbcTemplate.update("""
                update work_order_operation
                set outsource_status = 'SENT', outsource_supplier_id = ?, outsource_price = ?,
                    outsource_expected_at = ?, outsource_sent_at = now()
                where id = ?
                """, supplierId, price, expected, op.get("id"));
        if ("RELEASED".equals(String.valueOf(work.get("status")))) {
            jdbcTemplate.update(
                    "update work_order set status = 'IN_PROGRESS', updated_at = now() where id = ?",
                    workOrderId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("orderNo", orderNo);
        result.put("status", "SENT");
        result.put("qty", qty);
        result.put("price", price);
        result.put("expectedAt", expected == null ? null : expected.toString());
        result.put("supplierId", supplierId);
        result.put("warehouseId", warehouseId);
        result.put("lines", sentLines);
        return result;
    }

    // ---------------------------------------------------------------- 收回

    /** 收回：登记回来的数量与不良，推进工序与件码，可选落随工检验 */
    @Transactional
    public Map<String, Object> receive(long orderId, ReceiveRequest request) {
        Map<String, Object> order = loadOrder(orderId, true);
        String status = String.valueOf(order.get("status"));
        if (!OPEN_STATUS.contains(status)) {
            throw new BizException(2036, "这张外协单已收回，不能再登记");
        }
        if (request == null || request.qty() == null || request.qty().signum() <= 0) {
            throw new BizException(1002, "请填写本次收回数量");
        }
        BigDecimal receiveQty = request.qty();
        BigDecimal defect = request.defectQty() == null ? ZERO : request.defectQty();
        if (defect.signum() < 0) {
            throw new BizException(1002, "不良数量不能小于 0");
        }
        if (defect.compareTo(receiveQty) > 0) {
            throw new BizException(1002, "不良数量不能大于收回数量");
        }
        BigDecimal orderQty = decimal(order.get("qty"));
        BigDecimal receivedBefore = decimal(order.get("received_qty"));
        if (receivedBefore.add(receiveQty).compareTo(orderQty) > 0) {
            throw new BizException(2037, "收回数量超过外协数量，最多还能收 "
                    + orderQty.subtract(receivedBefore).stripTrailingZeros().toPlainString());
        }

        long workOrderId = ((Number) order.get("work_order_id")).longValue();
        long operationId = ((Number) order.get("operation_id")).longValue();
        int seq = ((Number) order.get("operation_seq")).intValue();
        Map<String, Object> work = loadWork(workOrderId);
        Map<String, Object> op = loadOperation(workOrderId, seq, true);

        // 工序配了检验点时，收回必须给结论，和报工同一套规则
        String inspectionMode = op.get("inspection_mode") == null
                ? "NONE" : String.valueOf(op.get("inspection_mode"));
        boolean inspectRequired = Boolean.TRUE.equals(op.get("need_inspection"))
                || "ATTACHED".equals(inspectionMode);
        String inspectionResult = request.inspectionResult() == null
                ? null : request.inspectionResult().trim().toUpperCase();
        if (inspectRequired) {
            if (inspectionResult == null || inspectionResult.isEmpty()) {
                throw new BizException(2020,
                        "第 " + seq + " 道工序是检验点，收回时请填写检验结论（合格 / 不合格）");
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

        String operator = CurrentUser.name();
        String receiver = trimToNull(request.receiver());
        if (receiver == null) {
            receiver = operator;
        }
        BigDecimal newReceived = receivedBefore.add(receiveQty);
        boolean allBack = newReceived.compareTo(orderQty) >= 0;
        BigDecimal amount = decimal(order.get("price"))
                .multiply(newReceived.subtract(decimal(order.get("defect_qty")).add(defect)))
                .setScale(2, RoundingMode.HALF_UP);
        jdbcTemplate.update("""
                update outsource_order
                set received_qty = ?, defect_qty = defect_qty + ?, amount = ?, status = ?,
                    receiver = ?, received_at = case when ? then now() else received_at end,
                    updated_at = now(), version = version + 1
                where id = ?
                """, newReceived, defect, amount, allBack ? "RECEIVED" : "PARTIAL",
                cut(receiver, 40), allBack, orderId);

        // 工序完成数按收回数累加（不良单独记），工序状态与累计外协数量一起回写
        BigDecimal newDone = decimal(op.get("done_qty")).add(receiveQty);
        BigDecimal planQty = decimal(op.get("plan_qty"));
        jdbcTemplate.update("""
                update work_order_operation
                set done_qty = ?, status = ?,
                    outsource_received_qty = outsource_received_qty + ?,
                    outsource_defect_qty = outsource_defect_qty + ?,
                    outsource_status = ?
                where id = ?
                """, newDone, newDone.compareTo(planQty) >= 0 ? "DONE" : "IN_PROGRESS",
                receiveQty, defect, allBack ? "RECEIVED" : "SENT", operationId);

        // 一码到底：外协收回的不良同样扣减后道计划量
        if (defect.signum() > 0) {
            jdbcTemplate.update("""
                    update work_order_operation
                    set plan_qty = greatest(done_qty, plan_qty - ?)
                    where work_order_id = ? and seq > ?
                    """, defect, workOrderId, seq);
        }

        long productId = ((Number) work.get("product_id")).longValue();
        if (inspectRequired && inspectionResult != null) {
            processInspections.record(new ProcessInspectionPort.ProcessInspection(
                    String.valueOf(work.get("work_no")), workOrderId, productId, receiveQty, defect,
                    inspectionResult, receiver, request.inspectionRemark()));
        }
        if (defect.signum() > 0) {
            Object processId = op.get("process_id");
            defectWriter.write(DefectRequest.of(workOrderId, operationId,
                    processId == null ? null : ((Number) processId).longValue(), productId, defect,
                    "REPORT", "外协收回不良：" + order.get("order_no")));
        }
        advanceUnits(workOrderId, seq, operationId, receiveQty, receiver, request.remark());
        boolean finished = syncWorkOrderProgress(workOrderId);
        if (finished) {
            try {
                costService.recalc(workOrderId);
            } catch (Exception e) {
                // 成本结算失败不影响收回，可在成本报表里重算
            }
        }
        Map<String, Object> result = detail(orderId);
        result.put("finished", finished);
        return result;
    }

    // ---------------------------------------------------------------- 查询

    public PageResult list(String keyword, String status, Long supplierId, Boolean overdueOnly,
                           int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (o.order_no ilike ? or w.work_no ilike ? or op.name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and o.status = ? ");
            args.add(status.trim().toUpperCase());
        }
        if (supplierId != null) {
            where.append(" and o.supplier_id = ? ");
            args.add(supplierId);
        }
        if (Boolean.TRUE.equals(overdueOnly)) {
            where.append(" and o.status in ('SENT', 'PARTIAL') and o.expected_at < current_date ");
        }
        orgScope.appendScope(where, args, "o.factory_org_id");
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from outsource_order o "
                        + "join rm_work_order w on w.id = o.work_order_id "
                        + "join rm_work_order_operation op on op.id = o.operation_id " + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id, o.order_no as "orderNo", o.status, o.qty,
                       o.received_qty as "receivedQty", o.defect_qty as "defectQty",
                       o.price, o.amount, o.sender, o.receiver, o.remark,
                       o.work_order_id as "workOrderId", w.work_no as "workNo",
                       p.name as "productName", op.seq as "operationSeq",
                       op.name as "operationName", s.name as "supplierName",
                       o.expected_at as "expectedAt",
                       to_char(o.sent_at, 'YYYY-MM-DD HH24:MI') as "sentAt",
                       to_char(o.received_at, 'YYYY-MM-DD HH24:MI') as "receivedAt",
                       case when o.status in ('SENT', 'PARTIAL') and o.expected_at is not null
                                 and o.expected_at < current_date then true else false end as "overdue",
                       case when o.status in ('SENT', 'PARTIAL') and o.expected_at is not null
                                 and o.expected_at < current_date
                            then current_date - o.expected_at else 0 end as "overdueDays"
                from outsource_order o
                join rm_work_order w on w.id = o.work_order_id
                join rm_work_order_operation op on op.id = o.operation_id
                left join rm_product p on p.id = w.product_id
                left join rm_supplier s on s.id = o.supplier_id
                """ + where + " order by o.id desc limit ? offset ?", pageArgs.toArray());
        rows.forEach(row -> row.put("statusLabel", statusLabel(String.valueOf(row.get("status")))));
        return new PageResult(rows, total == null ? 0 : total, safePage, safeSize);
    }

    public Map<String, Object> detail(long orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id, o.order_no as "orderNo", o.status, o.qty,
                       o.received_qty as "receivedQty", o.defect_qty as "defectQty",
                       o.price, o.amount, o.sender, o.receiver, o.remark,
                       o.work_order_id as "workOrderId", w.work_no as "workNo",
                       p.code as "productCode", p.name as "productName", p.unit,
                       op.id as "operationId", op.seq as "operationSeq", op.name as "operationName",
                       op.plan_qty as "operationPlanQty", op.done_qty as "operationDoneQty",
                       op.need_inspection as "needInspection",
                       coalesce(op.inspection_mode, 'NONE') as "inspectionMode",
                       s.code as "supplierCode", s.name as "supplierName",
                       o.expected_at as "expectedAt",
                       to_char(o.sent_at, 'YYYY-MM-DD HH24:MI') as "sentAt",
                       to_char(o.received_at, 'YYYY-MM-DD HH24:MI') as "receivedAt",
                       case when o.status in ('SENT', 'PARTIAL') and o.expected_at is not null
                                 and o.expected_at < current_date
                            then current_date - o.expected_at else 0 end as "overdueDays",
                       coalesce((select sum(i.qty) from outsource_order_item i
                                 where i.order_id = o.id), 0) as "materialQty"
                from outsource_order o
                join rm_work_order w on w.id = o.work_order_id
                join rm_work_order_operation op on op.id = o.operation_id
                left join rm_product p on p.id = w.product_id
                left join rm_supplier s on s.id = o.supplier_id
                where o.id = ?
                """, orderId);
        if (rows.isEmpty()) {
            throw new BizException(2038, "外协单不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        detail.put("statusLabel", statusLabel(String.valueOf(detail.get("status"))));
        // 工序是检验点时，收回必须给结论（与报工同一套规则）
        detail.put("inspectionRequired",
                Boolean.TRUE.equals(detail.get("needInspection"))
                        || "ATTACHED".equals(String.valueOf(detail.get("inspectionMode"))));
        detail.put("items", jdbcTemplate.queryForList("""
                select i.material_id as "materialId", m.code as "materialCode",
                       m.name as "materialName", m.spec, m.unit, i.qty,
                       i.batch_summary as "batchSummary", i.from_stock as "fromStock",
                       w.name as "warehouseName"
                from outsource_order_item i
                join rm_product m on m.id = i.material_id
                left join rm_warehouse w on w.id = i.warehouse_id
                where i.order_id = ?
                order by i.id
                """, orderId));
        return detail;
    }

    /** 工单维度的外协记录，工单详情页直接展示 */
    public List<Map<String, Object>> listByWorkOrder(long workOrderId) {
        return jdbcTemplate.queryForList("""
                select o.id, o.order_no as "orderNo", o.status, o.qty,
                       o.received_qty as "receivedQty", o.defect_qty as "defectQty",
                       o.price, o.amount, op.seq as "operationSeq", op.name as "operationName",
                       s.name as "supplierName", o.expected_at as "expectedAt",
                       to_char(o.sent_at, 'MM-DD HH24:MI') as "sentAt",
                       to_char(o.received_at, 'MM-DD HH24:MI') as "receivedAt",
                       case when o.status in ('SENT', 'PARTIAL') and o.expected_at is not null
                                 and o.expected_at < current_date then true else false end as "overdue"
                from outsource_order o
                join rm_work_order_operation op on op.id = o.operation_id
                left join rm_supplier s on s.id = o.supplier_id
                where o.work_order_id = ?
                order by o.id desc
                """, workOrderId);
    }

    /** 外协概览：在制单数、超期单数、在制件数、本月与累计外协费 */
    public Map<String, Object> summary() {
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        orgScope.appendScope(where, args, "factory_org_id");
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select count(*) filter (where status in ('SENT', 'PARTIAL')) as "open",
                       count(*) filter (where status in ('SENT', 'PARTIAL')
                                          and expected_at is not null and expected_at < current_date)
                           as "overdue",
                       coalesce(sum(qty - received_qty) filter (where status in ('SENT', 'PARTIAL')), 0)
                           as "openQty",
                       coalesce(sum(amount) filter (where to_char(sent_at, 'YYYY-MM')
                                                      = to_char(current_date, 'YYYY-MM')), 0) as "monthAmount",
                       coalesce(sum(amount), 0) as "totalAmount"
                from outsource_order
                """ + where, args.toArray());
        return new LinkedHashMap<>(row);
    }

    // ---------------------------------------------------------------- 内部

    private static String statusLabel(String status) {
        return switch (status) {
            case "SENT" -> "已发出";
            case "PARTIAL" -> "部分收回";
            case "RECEIVED" -> "已收回";
            default -> status;
        };
    }

    private Map<String, Object> loadWork(long workOrderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, work_no, product_id, plan_qty, status, factory_org_id
                from work_order where id = ?
                """, workOrderId);
        if (rows.isEmpty()) {
            throw new BizException(2010, "生产工单不存在");
        }
        return rows.get(0);
    }

    private void requireExecutable(Map<String, Object> work) {
        String status = String.valueOf(work.get("status"));
        if (!"RELEASED".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw new BizException(2029, "只有已下达或生产中的工单可以转外协");
        }
    }

    /** 外协和报工一样受前道工序约束：前道没做完，件还没到这道工序 */
    private void requirePreviousDone(long workOrderId, int seq) {
        if (seq <= 1) {
            return;
        }
        List<Map<String, Object>> previous = jdbcTemplate.queryForList("""
                select plan_qty, done_qty from work_order_operation
                where work_order_id = ? and seq = ?
                """, workOrderId, seq - 1);
        if (!previous.isEmpty()
                && decimal(previous.get(0).get("done_qty"))
                .compareTo(decimal(previous.get(0).get("plan_qty"))) < 0) {
            throw new BizException("前道工序尚未完工，不能发出外协");
        }
    }

    private Map<String, Object> loadOperation(long workOrderId, int seq, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, seq, name, plan_qty, done_qty, status, process_id,
                       need_inspection, inspection_mode, outsource_status,
                       outsource_supplier_id, outsource_price, outsource_expected_at,
                       outsource_received_qty, outsource_defect_qty
                from work_order_operation
                where work_order_id = ? and seq = ?
                """ + (forUpdate ? " for update" : ""), workOrderId, seq);
        if (rows.isEmpty()) {
            throw new BizException(1002, "第 " + seq + " 道工序不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> loadOrder(long orderId, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, order_no, work_order_id, operation_id, operation_seq, supplier_id,
                       status, qty, price, received_qty, defect_qty, amount, expected_at
                from outsource_order where id = ?
                """ + (forUpdate ? " for update" : ""), orderId);
        if (rows.isEmpty()) {
            throw new BizException(2038, "外协单不存在");
        }
        return rows.get(0);
    }

    private void assertSupplier(long supplierId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, name, enabled from rm_supplier where id = ?", supplierId);
        if (rows.isEmpty()) {
            throw new BizException(1002, "外协供应商不存在");
        }
        if (!Boolean.TRUE.equals(rows.get(0).get("enabled"))) {
            throw new BizException(1002, "该供应商已停用，不能发外协");
        }
    }

    private long rawWarehouse() {
        return inventory.warehouseOfType("RAW");
    }

    /** 该工序已发出但还没收回的数量：用来卡「累计外协量不超过工序计划量」 */
    private BigDecimal openQty(Object operationId) {
        BigDecimal qty = jdbcTemplate.queryForObject("""
                select coalesce(sum(qty - received_qty), 0) from outsource_order
                where operation_id = ? and status in ('SENT', 'PARTIAL')
                """, BigDecimal.class, operationId);
        return qty == null ? ZERO : qty;
    }

    private BigDecimal remainingOf(long workOrderId, int seq, long materialId) {
        List<BigDecimal> rows = jdbcTemplate.queryForList("""
                select greatest(0, plan_qty - issued_qty) from work_order_bom
                where work_order_id = ? and material_id = ? and operation_seq = ? and source <> 'SUBSTITUTE'
                """, BigDecimal.class, workOrderId, materialId, seq);
        return rows.isEmpty() ? ZERO : (rows.get(0) == null ? ZERO : rows.get(0));
    }

    /** 默认发料明细：这道工序所有还没领足的 BOM 行；已领足的记成随件转出 */
    private List<SendItem> defaultItems(long workOrderId, int seq) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select b.material_id as "materialId", b.plan_qty as "planQty",
                       b.issued_qty as "issuedQty"
                from work_order_bom b
                where b.work_order_id = ? and b.operation_seq = ? and b.source <> 'SUBSTITUTE'
                order by b.id
                """, workOrderId, seq);
        List<SendItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal remaining = decimal(row.get("planQty")).subtract(decimal(row.get("issuedQty")));
            BigDecimal qty = remaining.signum() > 0 ? remaining : decimal(row.get("planQty"));
            if (qty.signum() > 0) {
                items.add(new SendItem(((Number) row.get("materialId")).longValue(), qty));
            }
        }
        return items;
    }

    private Map<String, Object> line(long materialId, BigDecimal qty, boolean fromStock, String summary) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("materialId", materialId);
        line.put("materialName", materialName(materialId));
        line.put("qty", qty);
        line.put("fromStock", fromStock);
        line.put("batchSummary", summary);
        return line;
    }

    private String materialName(long materialId) {
        List<String> names = jdbcTemplate.queryForList(
                "select name from rm_product where id = ?", String.class, materialId);
        return names.isEmpty() ? String.valueOf(materialId) : names.get(0);
    }

    private void markIssued(long workOrderId, long materialId, BigDecimal qty) {
        jdbcTemplate.update("""
                update work_order_bom set issued_qty = greatest(0, issued_qty + ?)
                where work_order_id = ? and material_id = ? and source <> 'SUBSTITUTE'
                """, qty, workOrderId, materialId);
    }

    /**
     * 单件管理工单：把停在这道工序的件推进到下一道（或完工）。
     *
     * <p>外协按数量收回，件码按顺序推进，日志复用 {@code FINISH} 动作、备注写明外协收回，
     * 这样「这件在外协厂待过」在件流转记录里查得到。</p>
     */
    private void advanceUnits(long workOrderId, int seq, long operationId, BigDecimal qty,
                              String receiver, String remark) {
        String traceMode = jdbcTemplate.queryForObject(
                "select trace_mode from work_order where id = ?", String.class, workOrderId);
        if (!"SERIAL".equals(traceMode)) {
            return;
        }
        List<Map<String, Object>> units = jdbcTemplate.queryForList("""
                select id from work_order_unit
                where work_order_id = ? and current_operation_seq = ?
                  and status in ('PENDING', 'IN_PROGRESS')
                order by seq_no limit ?
                """, workOrderId, seq, qty.intValue());
        List<Map<String, Object>> next = jdbcTemplate.queryForList("""
                select id, seq from work_order_operation
                where work_order_id = ? and seq > ? order by seq limit 1
                """, workOrderId, seq);
        for (Map<String, Object> unit : units) {
            if (next.isEmpty()) {
                jdbcTemplate.update("""
                        update work_order_unit set status = 'FINISHED', current_operation_id = null,
                               current_operation_seq = null, last_action_at = now()
                        where id = ?
                        """, unit.get("id"));
            } else {
                jdbcTemplate.update("""
                        update work_order_unit set status = 'PENDING', current_operation_id = ?,
                               current_operation_seq = ?, last_action_at = now()
                        where id = ?
                        """, next.get(0).get("id"), next.get(0).get("seq"), unit.get("id"));
            }
            jdbcTemplate.update("""
                    insert into work_order_unit_log (unit_id, work_order_id, operation_seq,
                                                     operation_id, action, worker, operator, remark)
                    values (?, ?, ?, ?, 'FINISH', ?, ?, ?)
                    """, unit.get("id"), workOrderId, seq, operationId, cut(receiver, 64),
                    CurrentUser.name(), remark == null ? "外协收回" : cut("外协收回：" + remark, 200));
        }
    }

    /**
     * 外协收回后的工单进度：完成数取末道工序完成数，合格数取「报工合格 + 外协收回合格」。
     *
     * @return 是否整单完工
     */
    private boolean syncWorkOrderProgress(long workOrderId) {
        Integer notDone = jdbcTemplate.queryForObject("""
                select count(*) from work_order_operation where work_order_id = ? and done_qty < plan_qty
                """, Integer.class, workOrderId);
        Integer lastSeq = jdbcTemplate.queryForObject(
                "select max(seq) from work_order_operation where work_order_id = ?", Integer.class, workOrderId);
        BigDecimal lastDone = ZERO;
        BigDecimal qualifiedTotal = ZERO;
        if (lastSeq != null) {
            BigDecimal done = jdbcTemplate.queryForObject("""
                    select done_qty from work_order_operation where work_order_id = ? and seq = ?
                    """, BigDecimal.class, workOrderId, lastSeq);
            lastDone = done == null ? ZERO : done;
            BigDecimal reported = jdbcTemplate.queryForObject("""
                    select coalesce(sum(r.qualified_qty), 0) from work_report r
                    join work_order_operation o on o.id = r.operation_id
                    where r.work_order_id = ? and o.seq = ?
                    """, BigDecimal.class, workOrderId, lastSeq);
            BigDecimal outsourced = jdbcTemplate.queryForObject("""
                    select coalesce(sum(outsource_received_qty - outsource_defect_qty), 0)
                    from work_order_operation where work_order_id = ? and seq = ?
                    """, BigDecimal.class, workOrderId, lastSeq);
            qualifiedTotal = (reported == null ? ZERO : reported)
                    .add(outsourced == null ? ZERO : outsourced);
        }
        boolean finished = notDone != null && notDone == 0;
        jdbcTemplate.update("""
                update work_order set done_qty = ?, qualified_qty = ?, status = ?,
                       updated_at = now(), version = version + 1
                where id = ?
                """, lastDone, qualifiedTotal, finished ? "FINISHED" : "IN_PROGRESS", workOrderId);
        return finished;
    }

    private static String summarizeBatches(List<BatchMove> moves) {
        if (moves == null || moves.isEmpty()) {
            return null;
        }
        StringBuilder summary = new StringBuilder();
        for (BatchMove move : moves) {
            if (summary.length() > 0) {
                summary.append(" / ");
            }
            summary.append(move.batchNo()).append('×')
                    .append(move.qty().stripTrailingZeros().toPlainString());
        }
        String text = summary.toString();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static BigDecimal nonNegative(BigDecimal value, String label) {
        if (value.signum() < 0) {
            throw new BizException(1002, label + "不能为负数");
        }
        return value;
    }

    private static Date parseDate(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Date.valueOf(LocalDate.parse(text));
        } catch (DateTimeParseException error) {
            throw new BizException(1002, "日期格式应为 YYYY-MM-DD");
        }
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? ZERO : new BigDecimal(String.valueOf(value));
    }
}
