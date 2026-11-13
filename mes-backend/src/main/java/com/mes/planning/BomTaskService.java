package com.mes.planning;

import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.planning.api.BomTaskReminderPort;
import com.mes.sales.api.SalesOrderPort;
import com.mes.security.CurrentUser;
import com.mes.system.api.UserDirectoryPort;
import com.mes.task.api.TaskDraft;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 订单 BOM 任务：计划员把订单明细派给技术员做 BOM，技术员完成后 MRP 才能算这一单。
 *
 * <p>生产约束：派工同时生成待办并给技术员发通知；列表在数据库分页与关键字过滤；
 * 同一明细并发派工由唯一索引兜底；完成任务必须已有生效 BOM；
 * 改派会关闭旧待办并在待办里改指给新的人。</p>
 */
@Service
public class BomTaskService implements BomTaskReminderPort {

    private static final Set<String> ACTIVE_ORDER_STATUSES = Set.of("CONFIRMED", "IN_PRODUCTION");

    private final JdbcTemplate jdbcTemplate;
    private final SalesOrderPort salesOrders;
    private final MasterDataPort masterData;
    private final UserDirectoryPort users;
    private final TaskPort tasks;
    private final NoticePort notices;

    public BomTaskService(JdbcTemplate jdbcTemplate, SalesOrderPort salesOrders,
                          MasterDataPort masterData, UserDirectoryPort users,
                          TaskPort tasks, NoticePort notices) {
        this.jdbcTemplate = jdbcTemplate;
        this.salesOrders = salesOrders;
        this.masterData = masterData;
        this.users = users;
        this.tasks = tasks;
        this.notices = notices;
    }

    public record AssignRequest(List<Long> orderItemIds, long assigneeId, LocalDate dueDate,
                                String remark) {
    }

    public record ReassignRequest(long assigneeId, LocalDate dueDate) {
    }

    /** 可指派人：以「完成 BOM 任务」权限为准，避免派给做不了的人 */
    public List<UserDirectoryPort.UserRef> assignees() {
        return assignees(null, null);
    }

    /** 引用选择专用：关键字过滤 + 条数上限；limit 为空时保持原行为（返回全部可指派人员） */
    public List<UserDirectoryPort.UserRef> assignees(String keyword, Integer limit) {
        List<UserDirectoryPort.UserRef> all = users.byPermission("bom_task:complete");
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        List<UserDirectoryPort.UserRef> filtered = kw.isEmpty() ? all : all.stream()
                .filter(user -> contains(user.realName(), kw) || contains(user.username(), kw))
                .toList();
        if (limit != null && limit > 0) {
            int size = Math.min(Math.min(limit, 50), filtered.size());
            return new ArrayList<>(filtered.subList(0, size));
        }
        return filtered;
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    @Override
    public List<BomTaskReminderPort.TaskReminder> taskReminders(int aheadDays) {
        LocalDate today = LocalDate.now();
        LocalDate until = today.plusDays(Math.max(aheadDays, 0));
        List<BomTaskReminderPort.TaskReminder> result = new ArrayList<>();
        for (Map<String, Object> task : jdbcTemplate.queryForList("""
                select * from bom_task
                where status = 'PENDING' and due_date is not null and due_date <= ?
                order by due_date, id
                """, until)) {
            long orderId = ((Number) task.get("sales_order_id")).longValue();
            long productId = ((Number) task.get("product_id")).longValue();
            SalesOrderPort.SalesOrderRef order = salesOrders.order(orderId);
            ProductInfo product = masterData.product(productId).orElse(null);
            UserDirectoryPort.UserRef assignee =
                    users.find(((Number) task.get("assignee_id")).longValue()).orElse(null);
            LocalDate due = toLocalDate(task.get("due_date"));
            result.add(new BomTaskReminderPort.TaskReminder(
                    order == null ? null : order.orderNo(),
                    product == null ? null : product.code(),
                    task.get("assigned_by") == null ? null : String.valueOf(task.get("assigned_by")),
                    assignee == null ? null : assignee.username(),
                    due, due != null && due.isBefore(today)));
        }
        return result;
    }

    public PageResult list(String keyword, String status, boolean onlyMine, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (status != null && !status.isBlank()) {
            where.append(" and status = ? ");
            args.add(status);
        }
        if (onlyMine) {
            Optional<UserDirectoryPort.UserRef> current = users.findByUsername(CurrentUser.name());
            if (current.isEmpty()) {
                where.append(" and 1 = 0 ");
            } else {
                where.append(" and assignee_id = ? ");
                args.add(current.get().id());
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            where.append(" and (remark ilike ? ");
            args.add(like);
            Set<Long> orderIds = salesOrders.searchIds(keyword);
            if (!orderIds.isEmpty()) {
                where.append(" or sales_order_id in (")
                        .append(String.join(", ",
                                java.util.Collections.nCopies(orderIds.size(), "?")))
                        .append(") ");
                args.addAll(orderIds);
            }
            Set<Long> productIds = masterData.searchProductIds(keyword);
            if (!productIds.isEmpty()) {
                where.append(" or product_id in (")
                        .append(String.join(", ",
                                java.util.Collections.nCopies(productIds.size(), "?")))
                        .append(") ");
                args.addAll(productIds);
            }
            where.append(") ");
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from bom_task" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Math.max(size, 1));
        pageArgs.add(Math.max(page - 1, 0) * Math.max(size, 1));
        List<Map<String, Object>> taskRows = jdbcTemplate.queryForList(
                "select * from bom_task" + where + " order by id desc limit ? offset ?",
                pageArgs.toArray());
        return new PageResult(enrich(taskRows), total == null ? 0 : total, page, size);
    }

    /** 订单明细 + BOM 就绪状态 + 已有任务，派工弹窗用 */
    public List<Map<String, Object>> orderItems(long salesOrderId) {
        SalesOrderPort.SalesOrderRef order = salesOrders.order(salesOrderId);
        if (order == null) {
            throw new BizException(1002, "销售订单不存在");
        }
        List<SalesOrderPort.OrderItemRef> items = salesOrders.items(salesOrderId);
        Set<Long> productIds = new LinkedHashSet<>();
        items.forEach(item -> productIds.add(item.productId()));
        Map<Long, ProductInfo> products = productIds.isEmpty()
                ? Map.of() : masterData.products(productIds);
        Map<Long, Long> bomCache = new HashMap<>();
        Map<Long, Map<String, Object>> taskMap = new HashMap<>();
        for (Map<String, Object> task : jdbcTemplate.queryForList("""
                select id, sales_order_item_id, assignee_id, status, due_date
                from bom_task where sales_order_id = ? order by id desc
                """, salesOrderId)) {
            taskMap.putIfAbsent(((Number) task.get("sales_order_item_id")).longValue(), task);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SalesOrderPort.OrderItemRef item : items) {
            ProductInfo product = products.get(item.productId());
            Map<String, Object> task = taskMap.get(item.id());
            Long bomId = product == null || !product.make() ? null
                    : bomCache.computeIfAbsent(product.id(), masterData::activeBomId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.id());
            row.put("productId", item.productId());
            row.put("productCode", product == null ? null : product.code());
            row.put("productName", product == null ? null : product.name());
            row.put("spec", product == null ? null : product.spec());
            row.put("unit", product == null ? null : product.unit());
            row.put("qty", item.qty().subtract(item.shippedQty() == null
                    ? BigDecimal.ZERO : item.shippedQty()));
            row.put("cancelled", item.cancelled());
            row.put("make", product != null && product.make());
            row.put("bomReady", product != null && (!product.make() || bomId != null));
            row.put("taskId", task == null ? null : task.get("id"));
            row.put("taskStatus", task == null ? null : task.get("status"));
            row.put("assigneeId", task == null ? null : task.get("assignee_id"));
            rows.add(row);
        }
        return rows;
    }

    @Transactional
    public int assign(AssignRequest request) {
        if (request.orderItemIds() == null || request.orderItemIds().isEmpty()) {
            throw new BizException(1002, "请选择要派工的订单明细");
        }
        if (request.dueDate() != null && request.dueDate().isBefore(LocalDate.now())) {
            throw new BizException(1002, "要求完成日期不能早于今天");
        }
        UserDirectoryPort.UserRef assignee = users.find(request.assigneeId())
                .orElseThrow(() -> new BizException(1002, "指派人不存在"));
        boolean candidate = assignees().stream()
                .anyMatch(user -> user.id() == assignee.id());
        if (!candidate) {
            throw new BizException(1002, "该账号没有 BOM 任务处理权限");
        }
        int created = 0;
        for (Long itemId : request.orderItemIds()) {
            SalesOrderPort.OrderItemRef item = salesOrders.item(itemId);
            if (item == null) {
                throw new BizException(1002, "订单明细不存在：" + itemId);
            }
            SalesOrderPort.SalesOrderRef order = salesOrders.order(item.orderId());
            if (order == null || !ACTIVE_ORDER_STATUSES.contains(order.status())) {
                throw new BizException(1002, "订单 " + (order == null ? item.orderId() : order.orderNo())
                        + " 当前状态不能派 BOM 任务");
            }
            if (item.cancelled()) {
                throw new BizException(1002, "订单明细已取消，不能派 BOM 任务");
            }
            ProductInfo product = masterData.product(item.productId())
                    .orElseThrow(() -> new BizException(1002, "产品不存在：" + item.productId()));
            if (!product.make()) {
                throw new BizException(1002, "外购件不需要 BOM：" + product.code());
            }
            if (masterData.activeBomId(product.id()) != null) {
                throw new BizException(1002, "产品 " + product.code() + " 已有生效 BOM，无需派工");
            }
            Optional<Map<String, Object>> pending = jdbcTemplate.queryForList("""
                    select id from bom_task where sales_order_item_id = ? and status = 'PENDING'
                    """, itemId).stream().findFirst();
            if (pending.isPresent()) {
                tasks.close(TaskTypes.BOM_TASK, taskRefNo(order.orderNo(), itemId),
                        CurrentUser.name(), "BOM 任务改派");
                updatePending(pending.get().get("id"), request.assigneeId(), request.dueDate(),
                        request.remark());
            } else {
                try {
                    jdbcTemplate.update("""
                            insert into bom_task (sales_order_id, sales_order_item_id, product_id,
                                                  assignee_id, assigned_by, due_date, remark)
                            values (?, ?, ?, ?, ?, ?, ?)
                            """, item.orderId(), itemId, item.productId(), request.assigneeId(),
                            CurrentUser.name(), request.dueDate(), blankToNull(request.remark()));
                } catch (DuplicateKeyException race) {
                    // 并发派工：另一个请求已插入待处理任务，接管为改派
                    Long taskId = jdbcTemplate.queryForObject("""
                            select id from bom_task
                            where sales_order_item_id = ? and status = 'PENDING'
                            """, Long.class, itemId);
                    updatePending(taskId, request.assigneeId(), request.dueDate(), request.remark());
                }
                created++;
            }
            openTask(order, itemId, product, assignee, request.dueDate());
            notices.push(assignee.username(), NoticeMessage.of("TASK", "BOM 任务待办",
                    "订单 " + order.orderNo() + " 的 " + product.code() + " 需要编制 BOM",
                    "SALES_ORDER", order.orderNo()));
        }
        return created;
    }

    @Transactional
    public void reassign(long id, ReassignRequest request) {
        Map<String, Object> task = requireTask(id);
        if (!"PENDING".equals(task.get("status"))) {
            throw new BizException(1002, "只有待处理的任务可以改派");
        }
        if (request.dueDate() != null && request.dueDate().isBefore(LocalDate.now())) {
            throw new BizException(1002, "要求完成日期不能早于今天");
        }
        UserDirectoryPort.UserRef assignee = users.find(request.assigneeId())
                .orElseThrow(() -> new BizException(1002, "指派人不存在"));
        boolean candidate = assignees().stream()
                .anyMatch(user -> user.id() == assignee.id());
        if (!candidate) {
            throw new BizException(1002, "该账号没有 BOM 任务处理权限");
        }
        long orderId = ((Number) task.get("sales_order_id")).longValue();
        long itemId = ((Number) task.get("sales_order_item_id")).longValue();
        SalesOrderPort.SalesOrderRef order = salesOrders.order(orderId);
        if (order == null) {
            throw new BizException(1002, "销售订单不存在");
        }
        tasks.close(TaskTypes.BOM_TASK, taskRefNo(order.orderNo(), itemId),
                CurrentUser.name(), "BOM 任务改派");
        jdbcTemplate.update("""
                update bom_task set assignee_id = ?, due_date = ?, assigned_by = ?,
                       updated_at = now()
                 where id = ?
                """, request.assigneeId(), request.dueDate(), CurrentUser.name(), id);
        ProductInfo product = masterData.product(((Number) task.get("product_id")).longValue())
                .orElse(null);
        openTask(order, itemId, product, assignee, request.dueDate());
        notices.push(assignee.username(), NoticeMessage.of("TASK", "BOM 任务改派",
                "订单 " + order.orderNo() + " 的 BOM 任务已改派给你", "SALES_ORDER", order.orderNo()));
    }

    /** 技术员完成：必须已有生效 BOM，否则任务完成 MRP 仍然算不出料 */
    @Transactional
    public void complete(long id) {
        Map<String, Object> task = requireTask(id);
        if (!"PENDING".equals(task.get("status"))) {
            throw new BizException(1002, "任务已完成或已取消");
        }
        long assigneeId = ((Number) task.get("assignee_id")).longValue();
        UserDirectoryPort.UserRef current = users.findByUsername(CurrentUser.name())
                .orElseThrow(() -> new BizException(1002, "当前账号不存在"));
        boolean admin = "ADMIN".equals(current.role());
        if (!admin && current.id() != assigneeId) {
            throw new BizException(1002, "只有指派人或管理员可以完成任务");
        }
        long productId = ((Number) task.get("product_id")).longValue();
        Long bomId = masterData.activeBomId(productId);
        if (bomId == null) {
            throw new BizException(1002, "该产品还没有生效 BOM，请先在 BOM 管理里建立并审批生效");
        }
        jdbcTemplate.update("""
                update bom_task set status = 'DONE', bom_id = ?, finished_at = now(),
                       updated_at = now()
                 where id = ?
                """, bomId, id);
        long orderId = ((Number) task.get("sales_order_id")).longValue();
        SalesOrderPort.SalesOrderRef order = salesOrders.order(orderId);
        if (order != null) {
            tasks.close(TaskTypes.BOM_TASK,
                    taskRefNo(order.orderNo(), ((Number) task.get("sales_order_item_id")).longValue()),
                    CurrentUser.name(), "BOM 已完成");
        }
        String assignedBy = task.get("assigned_by") == null ? null
                : String.valueOf(task.get("assigned_by"));
        if (assignedBy != null) {
            notices.push(assignedBy, NoticeMessage.of("INFO", "BOM 任务已完成",
                    "订单 " + (order == null ? orderId : order.orderNo()) + " 的 BOM 已完成",
                    "SALES_ORDER", order == null ? null : order.orderNo()));
        }
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> task = requireTask(id);
        if (!"PENDING".equals(task.get("status"))) {
            throw new BizException(1002, "只有待处理的任务可以取消");
        }
        jdbcTemplate.update("""
                update bom_task set status = 'CANCELLED', updated_at = now() where id = ?
                """, id);
        long orderId = ((Number) task.get("sales_order_id")).longValue();
        SalesOrderPort.SalesOrderRef order = salesOrders.order(orderId);
        if (order != null) {
            tasks.close(TaskTypes.BOM_TASK,
                    taskRefNo(order.orderNo(), ((Number) task.get("sales_order_item_id")).longValue()),
                    CurrentUser.name(), "BOM 任务已取消");
        }
    }

    // ---------------------------------------------------------------- 内部

    private List<Map<String, Object>> enrich(List<Map<String, Object>> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductInfo> products = productsOf(tasks);
        Set<Long> orderIds = new LinkedHashSet<>();
        for (Map<String, Object> task : tasks) {
            orderIds.add(((Number) task.get("sales_order_id")).longValue());
        }
        Map<Long, SalesOrderPort.SalesOrderRef> orders = salesOrders.orders(orderIds);
        Map<Long, UserDirectoryPort.UserRef> userMap = usersOf(tasks);
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> task : tasks) {
            long orderId = ((Number) task.get("sales_order_id")).longValue();
            long productId = ((Number) task.get("product_id")).longValue();
            SalesOrderPort.SalesOrderRef order = orders.get(orderId);
            ProductInfo product = products.get(productId);
            UserDirectoryPort.UserRef assignee =
                    userMap.get(((Number) task.get("assignee_id")).longValue());
            LocalDate dueDate = toLocalDate(task.get("due_date"));
            boolean pending = "PENDING".equals(task.get("status"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.get("id"));
            row.put("salesOrderId", orderId);
            row.put("salesOrderItemId", task.get("sales_order_item_id"));
            row.put("orderNo", order == null ? null : order.orderNo());
            row.put("productId", productId);
            row.put("productCode", product == null ? null : product.code());
            row.put("productName", product == null ? null : product.name());
            row.put("spec", product == null ? null : product.spec());
            row.put("unit", product == null ? null : product.unit());
            row.put("assigneeId", task.get("assignee_id"));
            row.put("assigneeName", assignee == null ? null : assignee.realName());
            row.put("assignedBy", task.get("assigned_by"));
            row.put("dueDate", dueDate == null ? null : dueDate.toString());
            row.put("overdue", pending && dueDate != null && dueDate.isBefore(today));
            row.put("status", task.get("status"));
            row.put("bomId", task.get("bom_id"));
            row.put("remark", task.get("remark"));
            row.put("finishedAt", task.get("finished_at") == null ? null
                    : String.valueOf(task.get("finished_at")));
            row.put("createdAt", String.valueOf(task.get("created_at")));
            rows.add(row);
        }
        return rows;
    }

    private void updatePending(Object taskId, long assigneeId, LocalDate dueDate, String remark) {
        jdbcTemplate.update("""
                update bom_task set assignee_id = ?, due_date = ?, assigned_by = ?,
                       remark = ?, updated_at = now()
                 where id = ?
                """, assigneeId, dueDate, CurrentUser.name(), blankToNull(remark), taskId);
    }

    private void openTask(SalesOrderPort.SalesOrderRef order, long itemId, ProductInfo product,
                          UserDirectoryPort.UserRef assignee, LocalDate dueDate) {
        tasks.open(new TaskDraft(TaskTypes.BOM_TASK,
                "编 BOM：" + (product == null ? itemId : product.code()),
                "订单 " + order.orderNo() + " 需要编制 BOM",
                TaskTypes.REF_SALES_ORDER, order.id(), taskRefNo(order.orderNo(), itemId),
                "TECH", assignee.username(), TaskTypes.PRIORITY_NORMAL,
                dueDate == null ? LocalDate.now().plusDays(3) : dueDate));
    }

    private String taskRefNo(String orderNo, long itemId) {
        return orderNo + "-" + itemId;
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return value == null ? null : LocalDate.parse(String.valueOf(value));
    }

    private Map<String, Object> requireTask(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from bom_task where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "BOM 任务不存在");
        }
        return rows.get(0);
    }

    private Map<Long, ProductInfo> productsOf(List<Map<String, Object>> tasks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> task : tasks) {
            ids.add(((Number) task.get("product_id")).longValue());
        }
        return ids.isEmpty() ? Map.of() : masterData.products(ids);
    }

    private Map<Long, UserDirectoryPort.UserRef> usersOf(List<Map<String, Object>> tasks) {
        Map<Long, UserDirectoryPort.UserRef> result = new HashMap<>();
        for (Map<String, Object> task : tasks) {
            long id = ((Number) task.get("assignee_id")).longValue();
            if (!result.containsKey(id)) {
                users.find(id).ifPresent(user -> result.put(id, user));
            }
        }
        return result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
