package com.mes.returns;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.production.api.ReworkPort;
import com.mes.production.api.ReworkRequest;
import com.mes.production.api.WorkOrderPort;
import com.mes.quality.api.DefectPort;
import com.mes.quality.api.DefectRequest;
import com.mes.sales.api.SalesOrderPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReturnService {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final InventoryPort inventoryService;
    private final ReworkPort reworkService;
    private final DefectPort defectWriter;
    private final NoticePort notice;
    private final SalesOrderPort salesOrders;
    private final WorkOrderPort workOrders;
    private final MasterDataPort masterData;

    public ReturnService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                         InventoryPort inventoryService, ReworkPort reworkService,
                         DefectPort defectWriter, NoticePort notice,
                         SalesOrderPort salesOrders, WorkOrderPort workOrders,
                         MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.inventoryService = inventoryService;
        this.reworkService = reworkService;
        this.defectWriter = defectWriter;
        this.notice = notice;
        this.salesOrders = salesOrders;
        this.workOrders = workOrders;
        this.masterData = masterData;
    }

    public record ReturnItemRequest(Long productId, BigDecimal qty) {
    }

    public record ReturnRequest(Long salesOrderId, String reason, String disposition,
                                List<ReturnItemRequest> items) {
    }

    public PageResult list(String keyword, String status, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        // 订单号与客户名都不在退货单自己的表里：先换成 id 集合，退货侧只用 in 条件
        Set<Long> orderIds = salesOrders.searchIds(keyword);
        Set<Long> customerIds = masterData.searchCustomerIds(keyword);
        StringBuilder where = new StringBuilder(" where (r.return_no ilike ? ");
        args.add(like);
        if (!orderIds.isEmpty()) {
            where.append(" or r.sales_order_id in (").append(marks(orderIds)).append(") ");
            args.addAll(orderIds);
        }
        if (!customerIds.isEmpty()) {
            where.append(" or r.customer_id in (").append(marks(customerIds)).append(") ");
            args.addAll(customerIds);
        }
        where.append(") ");
        if (status != null && !status.isBlank()) {
            where.append(" and r.status = ? ");
            args.add(status);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from return_order r" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select r.id, r.return_no as "returnNo", r.sales_order_id as "salesOrderId",
                       r.customer_id as "customerId", r.status, r.reason,
                       r.disposition, r.total_qty as "totalQty",
                       r.rework_work_order_id as "reworkWorkOrderId",
                       to_char(r.created_at, 'YYYY-MM-DD') as "createdAt",
                       to_char(r.processed_at, 'YYYY-MM-DD HH24:MI') as "processedAt"
                from return_order r
                """ + where + " order by r.id desc limit ? offset ?", pageArgs.toArray());
        fillForeignNames(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select r.id, r.return_no as "returnNo", r.sales_order_id as "salesOrderId",
                       r.customer_id as "customerId", r.status, r.reason,
                       r.disposition, r.total_qty as "totalQty",
                       r.rework_work_order_id as "reworkWorkOrderId",
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(r.processed_at, 'YYYY-MM-DD HH24:MI') as "processedAt"
                from return_order r
                where r.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException("退货单不存在");
        }
        fillForeignNames(rows);
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.qty
                from return_item i
                where i.return_id = ? order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        detail.put("items", items);
        return detail;
    }

    @Transactional
    public long create(ReturnRequest request) {
        if (request.salesOrderId() == null) {
            throw new BizException(1002, "请选择销售订单");
        }
        SalesOrderPort.SalesOrderRef order = salesOrders.order(request.salesOrderId());
        if (order == null) {
            throw new BizException("销售订单不存在");
        }
        if (!List.of("SHIPPED", "COMPLETED").contains(order.status())) {
            throw new BizException(2019, "只有已发货或已完成的订单可以申请退货");
        }
        if (request.disposition() == null || !List.of("REWORK", "SCRAP", "RESTOCK").contains(request.disposition())) {
            throw new BizException(1002, "请选择退货处理方式");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请填写退货明细");
        }
        if (order.customerId() == null) {
            throw new BizException("销售订单没有客户，无法退货");
        }
        long customerId = order.customerId();
        BigDecimal totalQty = BigDecimal.ZERO;
        for (ReturnItemRequest item : request.items()) {
            if (item.productId() == null || item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "退货数量必须大于 0");
            }
            // 可退量 = 该产品在这个订单上的已发数量，口径由销售订单回答
            BigDecimal shippedQty = salesOrders.items(request.salesOrderId()).stream()
                    .filter(line -> line.productId() == item.productId())
                    .map(SalesOrderPort.OrderItemRef::shippedQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (item.qty().compareTo(shippedQty) > 0) {
                throw new BizException(2019, "退货数量不能超过该产品已发货数量 "
                        + shippedQty.stripTrailingZeros().toPlainString());
            }
            totalQty = totalQty.add(item.qty());
        }
        String returnNo = codeGenerator.next("RT");
        Long id = jdbcTemplate.queryForObject("""
                insert into return_order (return_no, sales_order_id, customer_id, status, reason, disposition, total_qty)
                values (?, ?, ?, 'PENDING', ?, ?, ?) returning id
                """, Long.class, returnNo, request.salesOrderId(), customerId, request.reason(),
                request.disposition(), totalQty);
        for (ReturnItemRequest item : request.items()) {
            jdbcTemplate.update("insert into return_item (return_id, product_id, qty) values (?, ?, ?)",
                    id, item.productId(), item.qty());
        }
        return id;
    }

    @Transactional
    public void process(long id) {
        Map<String, Object> order = loadReturn(id);
        if (!"PENDING".equals(String.valueOf(order.get("status")))) {
            throw new BizException(2020, "只有待处理退货单可以处理");
        }
        String returnNo = String.valueOf(order.get("return_no"));
        String disposition = String.valueOf(order.get("disposition"));
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "select product_id, qty from return_item where return_id = ?", id);
        Long salesOrderId = order.get("sales_order_id") == null
                ? null : ((Number) order.get("sales_order_id")).longValue();
        Long reworkWorkOrderId = null;

        if ("RESTOCK".equals(disposition)) {
            // 直接回成品仓，可再次发货
            long finishedWarehouse = inventoryService.warehouseOfType("FINISHED");
            for (Map<String, Object> item : items) {
                inventoryService.returnIn(((Number) item.get("product_id")).longValue(), finishedWarehouse,
                        new BigDecimal(String.valueOf(item.get("qty"))), returnNo);
            }
        } else if ("REWORK".equals(disposition)) {
            // 退货返修：按明细各开一张返修工单，返修完走完工检后重新入库；同时进不良台账留痕
            String reason = "退货返修 " + returnNo;
            for (Map<String, Object> item : items) {
                long productId = ((Number) item.get("product_id")).longValue();
                BigDecimal qty = new BigDecimal(String.valueOf(item.get("qty")));
                long workId = reworkService.createRework(new ReworkRequest(productId, qty, null,
                        salesOrderId, null, null, reason, null));
                if (reworkWorkOrderId == null) {
                    reworkWorkOrderId = workId;
                }
                long defectId = defectWriter.write(
                        DefectRequest.disposed(productId, qty, "RETURN", reason));
                if (defectId > 0) {
                    jdbcTemplate.update("""
                            update defect_record set disposition = 'REWORK', status = 'DISPOSED',
                                   rework_work_order_id = ?, handled_by = ?, handled_at = now()
                            where id = ?
                            """, workId, com.mes.security.CurrentUser.name(), defectId);
                }
            }
            jdbcTemplate.update("update return_order set rework_work_order_id = ? where id = ?",
                    reworkWorkOrderId, id);
        } else if ("SCRAP".equals(disposition)) {
            // 报废：进不良台账，写明来源与处置，不再回库存
            for (Map<String, Object> item : items) {
                defectWriter.write(DefectRequest.disposed(
                        ((Number) item.get("product_id")).longValue(),
                        new BigDecimal(String.valueOf(item.get("qty"))), "RETURN",
                        "退货报废 " + returnNo));
            }
            jdbcTemplate.update("""
                    update defect_record set disposition = 'SCRAP', status = 'DISPOSED',
                           handled_by = 'return', handled_at = now()
                    where source = 'RETURN' and status = 'PENDING' and reason = ?
                    """, "退货报废 " + returnNo);
        }
        jdbcTemplate.update("""
                update return_order set status = 'PROCESSED', processed_at = now() where id = ?
                """, id);
        String outcome = switch (disposition) {
            case "REWORK" -> "转返修";
            case "SCRAP" -> "已报废";
            default -> "已回库";
        };
        notice.pushToRoles(List.of("QC", "PLANNER", "WAREHOUSE"), NoticeMessage.of("INFO",
                "退货单 " + returnNo + " 已处理（" + outcome + "）",
                "处理方式：" + outcome + (reworkWorkOrderId == null ? "" : "，返修工单 ID " + reworkWorkOrderId),
                "RETURN", returnNo));
    }

    @Transactional
    public void close(long id) {
        Map<String, Object> order = loadReturn(id);
        if (!"PROCESSED".equals(String.valueOf(order.get("status")))) {
            throw new BizException(2021, "只有已处理退货单可以关闭");
        }
        jdbcTemplate.update("update return_order set status = 'CLOSED' where id = ?", id);
    }

    /** 补订单号、客户名、返修工单号与状态：三者都在别人家里 */
    private void fillForeignNames(List<Map<String, Object>> rows) {
        Set<Long> orderIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (row.get("salesOrderId") instanceof Number number) {
                orderIds.add(number.longValue());
            }
        }
        Map<Long, String> orderNos = salesOrders.orderNos(orderIds);
        for (Map<String, Object> row : rows) {
            long orderId = row.get("salesOrderId") instanceof Number number ? number.longValue() : 0L;
            row.put("orderNo", orderNos.get(orderId));
            if (row.get("reworkWorkOrderId") instanceof Number workId) {
                WorkOrderPort.WorkOrderRef ref = workOrders.workOrder(workId.longValue());
                row.put("reworkWorkNo", ref == null ? null : ref.workNo());
                row.put("reworkStatus", ref == null ? null : ref.status());
            } else {
                row.put("reworkWorkNo", null);
                row.put("reworkStatus", null);
            }
        }
        MasterDataViews.fillCustomerNames(masterData, rows, "customerId", "customerName");
    }

    private static String marks(Set<Long> ids) {
        return String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private Map<String, Object> loadReturn(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, return_no, status, disposition from return_order where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException("退货单不存在");
        }
        return rows.get(0);
    }
}
