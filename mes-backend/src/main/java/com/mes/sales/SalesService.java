package com.mes.sales;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.masterdata.api.CustomerInfo;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.platform.customfield.CustomFieldService;
import com.mes.platform.scope.OrgScopeService;
import com.mes.production.api.WorkOrderPort;
import com.mes.sales.api.SalesOrderPort;
import com.mes.security.CurrentUser;
import com.mes.security.DataScopeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SalesService implements SalesOrderPort {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final WorkOrderPort productionService;
    private final DataScopeService dataScopeService;
    private final OrgScopeService orgScopeService;
    private final MasterDataPort masterData;
    private final CustomFieldService customFieldService;

    public SalesService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                        WorkOrderPort productionService, DataScopeService dataScopeService,
                        OrgScopeService orgScopeService,
                        MasterDataPort masterData,
                        CustomFieldService customFieldService) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.productionService = productionService;
        this.dataScopeService = dataScopeService;
        this.orgScopeService = orgScopeService;
        this.masterData = masterData;
        this.customFieldService = customFieldService;
    }

    @Override
    public void markReadyToShip(long salesOrderId) {
        jdbcTemplate.update("""
                update sales_order set status = 'READY_TO_SHIP', updated_at = now()
                where id = ? and status = 'IN_PRODUCTION'
                """, salesOrderId);
    }

    @Override
    public void markInProduction(long salesOrderId) {
        jdbcTemplate.update("""
                update sales_order set status = 'IN_PRODUCTION', updated_at = now()
                where id = ? and status = 'CONFIRMED'
                """, salesOrderId);
    }

    @Override
    public Map<Long, SalesOrderBrief> briefs(Collection<Long> salesOrderIds) {
        Map<Long, SalesOrderBrief> map = new LinkedHashMap<>();
        if (salesOrderIds == null) {
            return map;
        }
        List<Long> ids = salesOrderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, order_no, delivery_date, status from sales_order where id in (" + marks + ")",
                ids.toArray())) {
            long id = ((Number) row.get("id")).longValue();
            map.put(id, new SalesOrderBrief(id,
                    row.get("order_no") == null ? null : String.valueOf(row.get("order_no")),
                    row.get("delivery_date") == null
                            ? null : ((java.sql.Date) row.get("delivery_date")).toLocalDate(),
                    row.get("status") == null ? null : String.valueOf(row.get("status"))));
        }
        return map;
    }

    @Override
    public void markShippedIfComplete(long salesOrderId) {
        if (!allItemsShipped(salesOrderId)) {
            return;
        }
        jdbcTemplate.update("update sales_order set status = 'SHIPPED', updated_at = now() where id = ?",
                salesOrderId);
    }

    @Override
    public void markCompletedIfComplete(long salesOrderId) {
        if (!allItemsShipped(salesOrderId)) {
            return;
        }
        jdbcTemplate.update("update sales_order set status = 'COMPLETED', updated_at = now() where id = ?",
                salesOrderId);
    }

    @Override
    public SalesOrderRef order(long salesOrderId) {
        List<SalesOrderRef> rows = jdbcTemplate.query("""
                select id, order_no, status, customer_id, factory_org_id from sales_order where id = ?
                """, (rs, rowNum) -> new SalesOrderRef(rs.getLong("id"), rs.getString("order_no"),
                rs.getString("status"),
                rs.getObject("customer_id") == null ? null : rs.getLong("customer_id"),
                rs.getObject("factory_org_id") == null ? null : rs.getLong("factory_org_id")), salesOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Map<Long, SalesOrderRef> orders(Collection<Long> salesOrderIds) {
        Map<Long, SalesOrderRef> map = new LinkedHashMap<>();
        for (Long id : distinctIds(salesOrderIds)) {
            SalesOrderRef ref = order(id);
            if (ref != null) {
                map.put(id, ref);
            }
        }
        return map;
    }

    @Override
    public Map<Long, String> orderNos(Collection<Long> salesOrderIds) {
        Map<Long, String> map = new LinkedHashMap<>();
        List<Long> ids = distinctIds(salesOrderIds);
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, order_no from sales_order where id in (" + marks + ")", ids.toArray())) {
            map.put(((Number) row.get("id")).longValue(), String.valueOf(row.get("order_no")));
        }
        return map;
    }

    @Override
    public Set<Long> searchIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Set.of();
        }
        return new java.util.LinkedHashSet<>(jdbcTemplate.queryForList(
                "select id from sales_order where order_no ilike ? order by id limit 500",
                Long.class, "%" + keyword.trim() + "%"));
    }

    @Override
    public List<SalesOrderRef> openForMrp() {
        return jdbcTemplate.query("""
                select id, order_no, status, customer_id, factory_org_id from sales_order
                where status = 'CONFIRMED'
                order by delivery_date nulls last, id
                """, (rs, rowNum) -> new SalesOrderRef(rs.getLong("id"), rs.getString("order_no"),
                rs.getString("status"),
                rs.getObject("customer_id") == null ? null : rs.getLong("customer_id"),
                rs.getObject("factory_org_id") == null ? null : rs.getLong("factory_org_id")))
                .stream()
                .filter(order -> !productionService.existsForSalesOrder(order.id()))
                .toList();
    }

    @Override
    public List<OrderItemRef> items(long salesOrderId) {
        return jdbcTemplate.query("""
                select id, order_id, product_id, qty, shipped_qty, status from sales_order_item
                where order_id = ? order by id
                """, (rs, rowNum) -> new OrderItemRef(rs.getLong("id"), rs.getLong("order_id"),
                rs.getLong("product_id"), rs.getBigDecimal("qty"), rs.getBigDecimal("shipped_qty"),
                rs.getString("status")),
                salesOrderId);
    }

    @Override
    public OrderItemRef item(long orderItemId) {
        List<OrderItemRef> rows = jdbcTemplate.query("""
                select id, order_id, product_id, qty, shipped_qty, status from sales_order_item where id = ?
                """, (rs, rowNum) -> new OrderItemRef(rs.getLong("id"), rs.getLong("order_id"),
                rs.getLong("product_id"), rs.getBigDecimal("qty"), rs.getBigDecimal("shipped_qty"),
                rs.getString("status")),
                orderItemId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void addShippedQty(long orderItemId, BigDecimal qty) {
        jdbcTemplate.update("update sales_order_item set shipped_qty = shipped_qty + ? where id = ?",
                qty, orderItemId);
    }

    @Override
    public Set<Long> idsInFactory(Long factoryOrgId) {
        if (factoryOrgId == null) {
            return Set.of();
        }
        return new java.util.LinkedHashSet<>(jdbcTemplate.queryForList("""
                select id from sales_order where factory_org_id = ? or factory_org_id is null order by id
                """, Long.class, factoryOrgId));
    }

    @Override
    public List<DeliveryLine> pendingDeliveries(LocalDate aheadDate, int limit) {
        return jdbcTemplate.query("""
                select so.id, so.order_no, so.customer_id, so.delivery_date, so.total_qty,
                       coalesce((select sum(i.shipped_qty) from sales_order_item i
                                 where i.order_id = so.id), 0) as "shippedQty"
                from sales_order so
                where so.status not in ('COMPLETED', 'CANCELLED')
                  and so.delivery_date is not null
                  and so.delivery_date <= ?
                  and so.total_qty > coalesce((select sum(i.shipped_qty) from sales_order_item i
                                               where i.order_id = so.id), 0)
                order by so.delivery_date, so.id
                limit ?
                """, (rs, rowNum) -> new DeliveryLine(rs.getLong("id"), rs.getString("order_no"),
                rs.getObject("customer_id") == null ? null : rs.getLong("customer_id"),
                rs.getDate("delivery_date") == null ? null : rs.getDate("delivery_date").toLocalDate(),
                rs.getBigDecimal("total_qty"), rs.getBigDecimal("shippedQty")),
                java.sql.Date.valueOf(aheadDate), limit);
    }

    private boolean allItemsShipped(long salesOrderId) {
        // 被单独取消的行不参与「是否发完」的判断，否则取消一行后订单永远到不了已发货
        Integer unshipped = jdbcTemplate.queryForObject("""
                select count(*) from sales_order_item
                where order_id = ? and status = 'ACTIVE' and shipped_qty < qty
                """, Integer.class, salesOrderId);
        return unshipped == null || unshipped == 0;
    }

    private static List<Long> distinctIds(Collection<Long> ids) {
        return ids == null ? List.of()
                : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    /**
     * 建单/改单入参。
     *
     * <p>后四个字段是补的：客户 PO 号、联系人、交货地址、跟单人 ——
     * 真实下单这些信息必须有地方记，原来只能挤进备注，打印与对账都不好使。</p>
     */
    public record OrderRequest(Long customerId,
                               LocalDate orderDate,
                               LocalDate deliveryDate,
                               String remark,
                               List<OrderItem> items,
                               String priority,
                               String customerPoNo,
                               String contact,
                               String deliveryAddress,
                               String salesOwner,
                               Map<String, Object> customFields,
                               String currency) {

        /** 兼容不传币种的调用：默认人民币 */
        public OrderRequest(Long customerId, LocalDate orderDate, LocalDate deliveryDate, String remark,
                            List<OrderItem> items, String priority,
                            String customerPoNo, String contact,
                            String deliveryAddress, String salesOwner,
                            Map<String, Object> customFields) {
            this(customerId, orderDate, deliveryDate, remark, items, priority,
                    customerPoNo, contact, deliveryAddress, salesOwner, customFields, null);
        }

        /** 兼容老调用：只给客户、日期、明细与优先级 */
        public OrderRequest(Long customerId, LocalDate orderDate, LocalDate deliveryDate, String remark,
                            List<OrderItem> items, String priority) {
            this(customerId, orderDate, deliveryDate, remark, items, priority,
                    null, null, null, null, null, null);
        }
    }

    public record OrderItem(Long productId, BigDecimal qty, BigDecimal price,
                            Map<String, Object> customFields) {

        public OrderItem(Long productId, BigDecimal qty, BigDecimal price) {
            this(productId, qty, price, null);
        }
    }

    public PageResult list(String keyword, String status, int page, int size,
                           String customFieldKey, String customFieldValue) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        // 客户名属于 masterdata：先按关键字换成 id 集合，订单侧只用 in 条件
        Set<Long> customerIds = masterData.searchCustomerIds(keyword);
        String customerCondition = customerIds.isEmpty()
                ? "1 = 0"
                : "so.customer_id in (" + String.join(", ",
                        java.util.Collections.nCopies(customerIds.size(), "?")) + ")";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(
                " where (so.order_no ilike ? or " + customerCondition + ") ");
        args.add(like);
        args.addAll(customerIds);
        if (status != null && !status.isBlank()) {
            where.append(" and so.status = ? ");
            args.add(status);
        }
        if (customFieldKey != null && !customFieldKey.isBlank()
                && customFieldValue != null && !customFieldValue.isBlank()) {
            // 明细字段用 item: 前缀与表头字段区分，语义是「任意一行满足」
            boolean itemField = customFieldKey.startsWith("item:");
            String entity = itemField ? "sales_order_item" : "sales_order";
            String fieldKey = itemField ? customFieldKey.substring("item:".length()) : customFieldKey;
            CustomFieldService.FormField definition =
                    customFieldService.formDefinition(entity, fieldKey);
            if (definition == null) {
                throw new BizException(1002, "自定义字段不存在、已停用或无权查看：" + fieldKey);
            }
            String dataRef = itemField ? "i.ext_data" : "so.ext_data";
            String lead = itemField
                    ? " and exists (select 1 from sales_order_item i where i.order_id = so.id and "
                    : " and ";
            String tail = itemField ? ") " : " ";
            String value = customFieldValue.trim();
            switch (definition.fieldType()) {
                case "NUMBER" -> {
                    try {
                        new BigDecimal(value);
                    } catch (NumberFormatException error) {
                        throw new BizException(1002, "自定义字段「" + definition.label() + "」筛选值必须是数字");
                    }
                    where.append(lead).append("(").append(dataRef)
                            .append(" ->> ?)::numeric = ?::numeric").append(tail);
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
                    where.append(lead).append("(").append(dataRef)
                            .append(" ->> ?)::boolean = ?::boolean").append(tail);
                    args.add(definition.fieldKey());
                    args.add(normalized);
                }
                default -> {
                    where.append(lead).append(dataRef).append(" ->> ? = ?").append(tail);
                    args.add(definition.fieldKey());
                    args.add(value);
                }
            }
        }
        if (isSelfScope()) {
            where.append(" and so.created_by = ? ");
            args.add(CurrentUser.name());
        } else if (dataScopeService.scope().equals("ORG")) {
            String path = dataScopeService.orgPathPrefix();
            if (path == null) {
                where.append(" and so.created_by = ? ");
                args.add(CurrentUser.name());
            } else {
                where.append("""
                         and exists (
                             select 1 from sys_user u join sys_org o on o.id = u.org_id
                             where u.username = so.created_by and o.path like ?
                         )
                        """);
                args.add(path + "%");
            }
        }
        // 多工厂隔离：只开在本工厂名下，历史未归属的订单不隐藏
        Long factoryFilter = orgScopeService.listFilter();
        if (factoryFilter != null) {
            where.append(" and (so.factory_org_id = ? or so.factory_org_id is null) ");
            args.add(factoryFilter);
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from sales_order so
                """ + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select so.id, so.order_no as "orderNo", so.customer_id as "customerId",
                       so.order_date as "orderDate", so.delivery_date as "deliveryDate", so.status, so.priority,
                       so.total_qty as "totalQty", so.total_amount as "totalAmount",
                       so.currency,
                       so.customer_po_no as "customerPoNo",
                       (select count(*) from sales_order_item i where i.order_id = so.id) as "itemCount",
                       so.remark, so.cancel_reason as "cancelReason", so.created_by as "createdBy",
                       (select q.quote_no from sales_quote q where q.id = so.quote_id) as "quoteNo",
                       to_char(so.created_at, 'YYYY-MM-DD') as "createdAt",
                       so.ext_data::text as "extData"
                from sales_order so
                """ + where + " order by so.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillCustomerNames(masterData, records);
        List<CustomFieldService.FormField> headerFields =
                customFieldService.formDefinitions("sales_order");
        for (Map<String, Object> record : records) {
            Object extData = record.remove("extData");
            record.put("customFields", customFieldService.visibleJson(headerFields,
                    extData == null ? null : String.valueOf(extData)));
        }
        fillItemCustomFields(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    /** 引用选择专用：销售订单下拉只返回单号、客户与状态，status 支持逗号分隔多个 */
    public List<Map<String, Object>> options(String keyword, String status, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>(List.of(like, like));
        StringBuilder where = new StringBuilder(
                " where (so.order_no ilike ? or coalesce(so.customer_po_no, '') ilike ?) ");
        List<String> statuses = splitStatus(status);
        if (!statuses.isEmpty()) {
            where.append(" and so.status in (")
                    .append(String.join(",", java.util.Collections.nCopies(statuses.size(), "?")))
                    .append(") ");
            args.addAll(statuses);
        }
        Long factoryFilter = orgScopeService.listFilter();
        if (factoryFilter != null) {
            where.append(" and (so.factory_org_id = ? or so.factory_org_id is null) ");
            args.add(factoryFilter);
        }
        args.add(size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select so.id, so.order_no as "orderNo", so.customer_id as "customerId",
                       so.status, so.delivery_date as "deliveryDate", so.total_qty as "totalQty"
                from sales_order so
                """ + where + " order by so.id desc limit ?", args.toArray());
        MasterDataViews.fillCustomerNames(masterData, rows);
        return rows;
    }

    private static List<String> splitStatus(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(status.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    /**
     * 列表带出明细行的自定义字段：一张订单多行时按行去重合并成「值1、值2」，
     * 用户把明细字段加到列设置里就能在订单列表直接看到，不必逐单点详情。
     * 只取当前账号可见的明细字段，避免从列表绕过字段权限。
     */
    private void fillItemCustomFields(List<Map<String, Object>> records) {
        if (records.isEmpty()) {
            return;
        }
        List<CustomFieldService.FormField> fields =
                customFieldService.formDefinitions("sales_order_item");
        if (fields.isEmpty()) {
            return;
        }
        List<Long> orderIds = records.stream()
                .map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(orderIds.size(), "?"));
        Map<Long, Map<String, List<String>>> values = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select order_id, ext_data::text as "extData"
                from sales_order_item
                where order_id in (""" + placeholders + ") order by id", orderIds.toArray())) {
            Map<String, Object> ext = customFieldService.readJson(
                    row.get("extData") == null ? null : String.valueOf(row.get("extData")));
            if (ext.isEmpty()) {
                continue;
            }
            long orderId = ((Number) row.get("order_id")).longValue();
            Map<String, List<String>> perOrder =
                    values.computeIfAbsent(orderId, key -> new LinkedHashMap<>());
            for (CustomFieldService.FormField field : fields) {
                Object value = ext.get(field.fieldKey());
                if (value == null) {
                    continue;
                }
                String text = value instanceof Boolean flag ? (flag ? "是" : "否")
                        : String.valueOf(value);
                if (text.isBlank()) {
                    continue;
                }
                List<String> list = perOrder.computeIfAbsent(field.fieldKey(),
                        key -> new ArrayList<>());
                if (!list.contains(text)) {
                    list.add(text);
                }
            }
        }
        for (Map<String, Object> record : records) {
            long orderId = ((Number) record.get("id")).longValue();
            Map<String, List<String>> perOrder = values.get(orderId);
            Map<String, Object> summary = new LinkedHashMap<>();
            if (perOrder != null) {
                perOrder.forEach((key, list) -> {
                    if (!list.isEmpty()) {
                        summary.put(key, String.join("、", list));
                    }
                });
            }
            record.put("itemCustomFields", summary);
        }
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select so.id, so.order_no as "orderNo", so.customer_id as "customerId",
                       so.order_date as "orderDate", so.delivery_date as "deliveryDate",
                       so.status, so.priority, so.total_qty as "totalQty", so.total_amount as "totalAmount", so.remark,
                       so.currency,
                       so.customer_po_no as "customerPoNo", so.contact as "orderContact",
                       so.delivery_address as "deliveryAddress", so.sales_owner as "salesOwner",
                       so.cancel_reason as "cancelReason",
                       so.created_by as "createdBy", to_char(so.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       (select q.quote_no from sales_quote q where q.id = so.quote_id) as "quoteNo",
                       so.ext_data::text as "extData"
                from sales_order so where so.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException("销售订单不存在");
        }
        // 客户名称与联系方式都跟着客户走，从 masterdata 取，不再 join customer
        MasterDataViews.fillCustomerNames(masterData, rows);
        CustomerInfo customer = rows.get(0).get("customerId") == null ? null
                : masterData.customer(((Number) rows.get(0).get("customerId")).longValue()).orElse(null);
        if (customer != null) {
            rows.get(0).put("contact", customer.contact());
            rows.get(0).put("phone", customer.phone());
            rows.get(0).put("address", customer.address());
        }
        Map<String, Object> order = new LinkedHashMap<>(rows.get(0));
        Object extData = order.remove("extData");
        order.put("customFields", customFieldService.visibleJson("sales_order",
                extData == null ? null : String.valueOf(extData)));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.qty, i.price, i.amount,
                       i.shipped_qty as "shippedQty", i.status,
                       i.cancel_reason as "cancelReason",
                       to_char(i.cancelled_at, 'YYYY-MM-DD HH24:MI') as "cancelledAt",
                       i.ext_data::text as "extData",
                       case when i.status = 'CANCELLED' then 0 else (i.qty - i.shipped_qty) end as "remainingQty"
                from sales_order_item i
                where i.order_id = ? order by i.id
                """, id);
        MasterDataViews.fillProductNames(masterData, items);
        List<CustomFieldService.FormField> itemFields =
                customFieldService.formDefinitions("sales_order_item");
        for (Map<String, Object> item : items) {
            Object itemExt = item.remove("extData");
            item.put("customFields", customFieldService.visibleJson(itemFields,
                    itemExt == null ? null : String.valueOf(itemExt)));
        }
        order.put("items", items);
        return order;
    }

    @Transactional
    public long create(OrderRequest request) {
        validate(request);
        String orderNo = codeGenerator.next("SO");
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItem item : request.items()) {
            totalQty = totalQty.add(item.qty());
            totalAmount = totalAmount.add(item.qty().multiply(item.price()).setScale(2, RoundingMode.HALF_UP));
        }
        Long factoryOrgId = orgScopeService.currentFactoryOrgId();
        if (factoryOrgId == null) {
            factoryOrgId = orgScopeService.defaultFactoryOrgId();
        }
        Long orderId = jdbcTemplate.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, delivery_date, status,
                                         priority, total_qty, total_amount, remark, created_by,
                                         factory_org_id, customer_po_no, contact, delivery_address, sales_owner,
                                         currency)
                values (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, orderNo, request.customerId(),
                request.orderDate() == null ? LocalDate.now() : request.orderDate(),
                request.deliveryDate(), priorityOf(request), totalQty, totalAmount, request.remark(),
                CurrentUser.name(), factoryOrgId, blankToNull(request.customerPoNo()),
                blankToNull(request.contact()), blankToNull(request.deliveryAddress()),
                blankToNull(request.salesOwner()), normalizeCurrency(request.currency()));
        insertItems(orderId, request.items());
        String extJson = request.customFields() == null ? "{}"
                : customFieldService.mergeJson("sales_order", "{}", request.customFields(), true);
        jdbcTemplate.update("update sales_order set ext_data = ?::jsonb where id = ?", extJson, orderId);
        return orderId;
    }

    @Transactional
    public void update(long id, OrderRequest request) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿订单可以修改");
        validate(request);
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItem item : request.items()) {
            totalQty = totalQty.add(item.qty());
            totalAmount = totalAmount.add(item.qty().multiply(item.price()).setScale(2, RoundingMode.HALF_UP));
        }
        String existingExt = jdbcTemplate.queryForObject(
                "select ext_data::text from sales_order where id = ?", String.class, id);
        String extJson = customFieldService.mergeJson("sales_order", existingExt,
                request.customFields(), false);
        jdbcTemplate.update("""
                update sales_order set customer_id = ?, order_date = ?, delivery_date = ?, remark = ?,
                       priority = ?, total_qty = ?, total_amount = ?, customer_po_no = ?, contact = ?,
                       delivery_address = ?, sales_owner = ?, currency = ?, ext_data = ?::jsonb,
                       updated_at = now(), version = version + 1
                where id = ?
                """, request.customerId(),
                request.orderDate() == null ? LocalDate.now() : request.orderDate(),
                request.deliveryDate(), request.remark(), priorityOf(request), totalQty, totalAmount,
                blankToNull(request.customerPoNo()), blankToNull(request.contact()),
                blankToNull(request.deliveryAddress()), blankToNull(request.salesOwner()),
                normalizeCurrency(request.currency()), extJson, id);
        jdbcTemplate.update("delete from sales_order_item where order_id = ?", id);
        insertItems(id, request.items());
    }

    @Transactional
    public void confirm(long id) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿订单可以审核");
        Integer items = jdbcTemplate.queryForObject(
                "select count(*) from sales_order_item where order_id = ? and status = 'ACTIVE'", Integer.class, id);
        if (items == null || items == 0) {
            throw new BizException(2002, "订单没有可执行的有效明细，不能审核");
        }
        jdbcTemplate.update("update sales_order set status = 'CONFIRMED', updated_at = now() where id = ?", id);
    }

    @Transactional
    public void cancel(long id) {
        cancel(id, null);
    }

    /**
     * 取消订单。
     *
     * <p>草稿、已确认直接取消；已转生产的订单要先让下游收尾 ——
     * 工单全部作废或关闭、且没有发过货，才允许取消，否则提示还卡在哪几张工单上。
     * 已经发货的只能走退货流程，不能直接把订单抹掉。</p>
     */
    @Transactional
    public void cancel(long id, String reason) {
        Map<String, Object> order = loadOrder(id);
        String status = String.valueOf(order.get("status"));
        if ("IN_PRODUCTION".equals(status)) {
            Integer shipped = jdbcTemplate.queryForObject("""
                    select count(*) from rm_shipment
                    where sales_order_id = ? and status in ('SHIPPED', 'SIGNED')
                    """, Integer.class, id);
            if (shipped != null && shipped > 0) {
                throw new BizException(2007, "订单已发货，不能取消，请走退货流程");
            }
            List<String> opening = productionService.openWorkNos(id);
            if (!opening.isEmpty()) {
                throw new BizException(2003, "订单下还有未收尾的工单：" + String.join("、", opening)
                        + "，请先到「生产工单」里作废或关闭，再取消订单");
            }
        } else if (!"DRAFT".equals(status) && !"CONFIRMED".equals(status)) {
            throw new BizException(2003, "当前状态不允许取消：只有草稿、已确认、生产中（工单已全部作废或关闭）的订单可以取消");
        }
        String trimmed = reason == null ? null : reason.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        jdbcTemplate.update("""
                update sales_order set status = 'CANCELLED', cancel_reason = ?, updated_at = now()
                where id = ?
                """, trimmed, id);
    }

    /**
     * 明细级撤单预检：这一行能不能撤、卡在哪、还压着哪些料没退。
     *
     * <p>客户常常只砍订单里的某一两种货，整单取消再重开代价太大（工单、领料、排产全要重来）。
     * 预检只读不写，前端拿它渲染确认弹窗：能撤就列「将连带作废哪几张工单」，
     * 不能撤就列阻塞原因与待退料清单，让人知道下一步该干什么。</p>
     *
     * @param cancelQty 本次取消的数量；为空表示整行取消，小于行数量时按「部分取消」处理
     */
    public Map<String, Object> cancelItemPreview(long orderId, long itemId, BigDecimal cancelQty) {
        Map<String, Object> order = loadOrder(orderId);
        Map<String, Object> item = loadItem(orderId, itemId);
        List<WorkOrderPort.WorkOrderRef> workOrders = workOrdersOf(order, item);
        BigDecimal orderedQty = qtyOf(item);
        BigDecimal toCancel = cancelQtyOf(item, cancelQty);
        boolean whole = toCancel.compareTo(orderedQty) >= 0;
        BigDecimal remainingQty = orderedQty.subtract(toCancel);

        List<String> blockers = itemBlockers(order, item);
        List<WorkOrderPort.WorkOrderRef> openWorks = new ArrayList<>();
        List<Map<String, Object>> workOrderViews = new ArrayList<>();
        for (WorkOrderPort.WorkOrderRef work : workOrders) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", work.id());
            view.put("workNo", work.workNo());
            view.put("status", work.status());
            if ("CANCELLED".equals(work.status())) {
                view.put("action", "NONE");
                view.put("cancellable", true);
                view.put("blockers", List.of());
            } else {
                List<String> workBlockers = productionService.cancelBlockers(work.id());
                view.put("action", whole ? "CANCEL" : "RESIZE");
                view.put("cancellable", workBlockers.isEmpty());
                view.put("blockers", workBlockers);
                if (!workBlockers.isEmpty()) {
                    blockers.add(work.workNo() + "：" + String.join("；", workBlockers));
                }
                openWorks.add(work);
            }
            workOrderViews.add(view);
        }
        // 缩量只能对着一张工单做：多张在制单怎么分摊没有唯一答案，交给人工决定
        if (!whole && openWorks.size() > 1) {
            blockers.add("这一行有 " + openWorks.size() + " 张在制工单，没法按比例缩量，请先合并或整行取消");
        }
        List<Long> openIds = openWorks.stream().map(WorkOrderPort.WorkOrderRef::id).toList();
        // 待退料清单：撤单前先把「还压着的料」摆出来，免得撤完才发现库存对不上
        List<Map<String, Object>> pendingReturns = new ArrayList<>();
        for (WorkOrderPort.PendingReturn line : productionService.pendingReturns(openIds)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workOrderId", line.workOrderId());
            row.put("workNo", line.workNo());
            row.put("materialCode", line.materialCode());
            row.put("materialName", line.materialName());
            row.put("unit", line.unit());
            row.put("issuedQty", line.issuedQty());
            row.put("returnedQty", line.returnedQty());
            row.put("returnableQty", line.returnableQty());
            pendingReturns.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("orderNo", order.get("order_no"));
        result.put("orderStatus", order.get("status"));
        result.put("itemId", itemId);
        result.put("productId", item.get("productId"));
        result.put("productCode", item.get("productCode"));
        result.put("productName", item.get("productName"));
        result.put("qty", item.get("qty"));
        result.put("amount", item.get("amount"));
        result.put("shippedQty", item.get("shipped_qty"));
        result.put("status", item.get("status"));
        result.put("mode", whole ? "WHOLE" : "PARTIAL");
        result.put("cancelQty", toCancel);
        result.put("remainingQty", remainingQty);
        result.put("cancellable", blockers.isEmpty());
        result.put("blockers", blockers);
        result.put("workOrders", workOrderViews);
        result.put("pendingReturns", pendingReturns);
        return result;
    }

    /**
     * 明细级撤单：作废这一行自己的工单，然后把这行标成已取消。
     *
     * <p>先撤工单再改行状态，任何一步失败整笔回滚 —— 不允许出现
     * 「订单行取消了、工单还在车间做」的半状态。全部明细都撤完时订单自动转已取消。</p>
     *
     * <p>{@code cancelQty} 小于行数量时走「部分取消」：原行减量保留，另起一条已取消行留痕，
     * 工单按新数量缩量（工序计划量、标准工时、BOM 快照用量一起缩）。</p>
     */
    @Transactional
    public Map<String, Object> cancelItem(long orderId, long itemId, BigDecimal cancelQty, String reason) {
        Map<String, Object> order = loadOrder(orderId);
        Map<String, Object> item = loadItem(orderId, itemId);
        BigDecimal orderedQty = qtyOf(item);
        BigDecimal toCancel = cancelQtyOf(item, cancelQty);
        boolean whole = toCancel.compareTo(orderedQty) >= 0;
        BigDecimal remainingQty = orderedQty.subtract(toCancel);

        List<String> blockers = itemBlockers(order, item);
        List<WorkOrderPort.WorkOrderRef> openWorks = new ArrayList<>();
        for (WorkOrderPort.WorkOrderRef work : workOrdersOf(order, item)) {
            if ("CANCELLED".equals(work.status())) {
                continue;
            }
            openWorks.add(work);
            List<String> workBlockers = productionService.cancelBlockers(work.id());
            if (!workBlockers.isEmpty()) {
                blockers.add(work.workNo() + "：" + String.join("；", workBlockers));
            }
        }
        if (!whole && openWorks.size() > 1) {
            blockers.add("这一行有 " + openWorks.size() + " 张在制工单，没法按比例缩量，请先合并或整行取消");
        }
        if (!blockers.isEmpty()) {
            throw new BizException(2003, String.join("；", blockers));
        }

        String trimmed = reason == null ? null : reason.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        List<String> touchedWorkNos = new ArrayList<>();
        if (whole) {
            for (WorkOrderPort.WorkOrderRef work : openWorks) {
                productionService.cancelWorkOrder(work.id(), trimmed);
                touchedWorkNos.add(work.workNo());
            }
            jdbcTemplate.update("""
                    update sales_order_item
                       set status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(), cancelled_by = ?
                     where id = ?
                    """, trimmed, CurrentUser.name(), itemId);
        } else {
            // 拆行：原行减量保留，砍掉的数量单起一条已取消行，事后能查到「哪一次砍了多少」
            BigDecimal price = new BigDecimal(String.valueOf(item.get("price")));
            jdbcTemplate.update("""
                    update sales_order_item set qty = ?, amount = ? where id = ?
                    """, remainingQty, remainingQty.multiply(price).setScale(2, RoundingMode.HALF_UP), itemId);
            jdbcTemplate.update("""
                    insert into sales_order_item (order_id, product_id, qty, price, amount, shipped_qty,
                                                  status, cancel_reason, cancelled_at, cancelled_by)
                    values (?, ?, ?, ?, ?, 0, 'CANCELLED', ?, now(), ?)
                    """, orderId, item.get("productId"), toCancel, price,
                    toCancel.multiply(price).setScale(2, RoundingMode.HALF_UP), trimmed, CurrentUser.name());
            for (WorkOrderPort.WorkOrderRef work : openWorks) {
                productionService.resizeWorkOrder(work.id(), remainingQty);
                touchedWorkNos.add(work.workNo());
            }
        }

        Map<String, Object> totals = recalculateTotals(orderId);
        boolean allCancelled = Boolean.TRUE.equals(totals.get("allCancelled"));
        if (allCancelled) {
            jdbcTemplate.update("""
                    update sales_order set status = 'CANCELLED', cancel_reason = ?, updated_at = now()
                    where id = ?
                    """, trimmed == null ? "全部明细已取消" : trimmed, orderId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("itemId", itemId);
        result.put("mode", whole ? "WHOLE" : "PARTIAL");
        result.put("cancelQty", toCancel);
        result.put("remainingQty", whole ? BigDecimal.ZERO : remainingQty);
        result.put("cancelledWorkNos", touchedWorkNos);
        result.put("totalQty", totals.get("totalQty"));
        result.put("totalAmount", totals.get("totalAmount"));
        result.put("orderStatus", allCancelled ? "CANCELLED" : order.get("status"));
        result.put("allCancelled", allCancelled);
        return result;
    }

    /** 撤行前置条件：订单状态、明细状态、是否已发货 */
    private List<String> itemBlockers(Map<String, Object> order, Map<String, Object> item) {
        List<String> blockers = new ArrayList<>();
        String orderStatus = String.valueOf(order.get("status"));
        if (!"CONFIRMED".equals(orderStatus) && !"IN_PRODUCTION".equals(orderStatus)) {
            if ("DRAFT".equals(orderStatus)) {
                blockers.add("草稿订单直接编辑删掉这一行即可，不用单独取消");
            } else {
                blockers.add("订单当前状态为 " + orderStatus + "，只有已确认、生产中的订单可以取消单行");
            }
        }
        if ("CANCELLED".equals(String.valueOf(item.get("status")))) {
            blockers.add("这一行已经取消过了");
        }
        BigDecimal shipped = item.get("shipped_qty") == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(item.get("shipped_qty")));
        if (shipped.signum() > 0) {
            blockers.add("这一行已发货 " + shipped.stripTrailingZeros().toPlainString()
                    + "，请走退货流程，不能直接取消");
        }
        return blockers;
    }

    private List<WorkOrderPort.WorkOrderRef> workOrdersOf(Map<String, Object> order, Map<String, Object> item) {
        return productionService.workOrdersOfItem(
                ((Number) item.get("id")).longValue(),
                ((Number) order.get("id")).longValue(),
                ((Number) item.get("productId")).longValue());
    }

    /** 按未取消的明细重算订单合计量与金额，并回答「是不是全撤完了」 */
    private Map<String, Object> recalculateTotals(long orderId) {
        Map<String, Object> agg = jdbcTemplate.queryForMap("""
                select coalesce(sum(qty), 0) as qty, coalesce(sum(amount), 0) as amount,
                       count(*) as items
                from sales_order_item where order_id = ? and status = 'ACTIVE'
                """, orderId);
        BigDecimal qty = new BigDecimal(String.valueOf(agg.get("qty")));
        BigDecimal amount = new BigDecimal(String.valueOf(agg.get("amount")));
        jdbcTemplate.update("""
                update sales_order set total_qty = ?, total_amount = ?, updated_at = now()
                where id = ?
                """, qty, amount, orderId);
        boolean allCancelled = ((Number) agg.get("items")).longValue() == 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalQty", qty);
        result.put("totalAmount", amount);
        result.put("allCancelled", allCancelled);
        return result;
    }

    private BigDecimal qtyOf(Map<String, Object> item) {
        return new BigDecimal(String.valueOf(item.get("qty")));
    }

    /** 空串按 null 落库，避免「有没有填」在报表里变成两种空值 */
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 订单币种只做记录不做换算：未填按人民币，填了必须是系统认可的币种 */
    private String normalizeCurrency(String currency) {
        String value = blankToNull(currency);
        if (value == null) {
            return "CNY";
        }
        String upper = value.toUpperCase();
        if (!List.of("CNY", "USD", "EUR").contains(upper)) {
            throw new BizException(1002, "币种只支持 CNY / USD / EUR");
        }
        return upper;
    }

    /** 本次取消数量：不传就是整行取消，传了要落在 0 与行数量之间 */
    private BigDecimal cancelQtyOf(Map<String, Object> item, BigDecimal requested) {
        BigDecimal ordered = qtyOf(item);
        if (requested == null) {
            return ordered;
        }
        if (requested.signum() <= 0) {
            throw new BizException(1002, "取消数量必须大于 0");
        }
        if (requested.compareTo(ordered) > 0) {
            throw new BizException(1002, "取消数量不能超过这一行的数量 "
                    + ordered.stripTrailingZeros().toPlainString());
        }
        return requested;
    }

    private Map<String, Object> loadItem(long orderId, long itemId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.id, i.order_id as "orderId", i.product_id as "productId", i.qty, i.price, i.amount,
                       i.shipped_qty, i.status, i.cancel_reason as "cancelReason"
                from sales_order_item i
                where i.id = ? and i.order_id = ?
                """, itemId, orderId);
        if (rows.isEmpty()) {
            throw new BizException(2002, "订单明细不存在");
        }
        Map<String, Object> item = new LinkedHashMap<>(rows.get(0));
        MasterDataViews.fillProductNames(masterData, List.of(item));
        return item;
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> order = loadOrder(id);
        assertStatus(order, "DRAFT", "只有草稿订单可以删除");
        jdbcTemplate.update("delete from sales_order_item where order_id = ?", id);
        jdbcTemplate.update("delete from sales_order where id = ?", id);
    }

    private void insertItems(long orderId, List<OrderItem> items) {
        for (OrderItem item : items) {
            BigDecimal amount = item.qty().multiply(item.price()).setScale(2, RoundingMode.HALF_UP);
            Long itemId = jdbcTemplate.queryForObject("""
                    insert into sales_order_item (order_id, product_id, qty, price, amount)
                    values (?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, orderId, item.productId(), item.qty(), item.price(), amount);
            String extJson = item.customFields() == null ? "{}"
                    : customFieldService.mergeJson("sales_order_item", "{}",
                    item.customFields(), true);
            jdbcTemplate.update("update sales_order_item set ext_data = ?::jsonb where id = ?",
                    extJson, itemId);
        }
    }

    private Map<String, Object> loadOrder(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, status, order_no, priority from sales_order where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException("销售订单不存在");
        }
        return rows.get(0);
    }

    private void assertStatus(Map<String, Object> order, String expected, String message) {
        if (!expected.equals(String.valueOf(order.get("status")))) {
            throw new BizException(2001, message);
        }
    }

    private String priorityOf(OrderRequest request) {
        String priority = request.priority() == null ? "" : request.priority().trim().toUpperCase();
        if (priority.isEmpty()) {
            return "NORMAL";
        }
        if (!"NORMAL".equals(priority) && !"URGENT".equals(priority) && !"RUSH".equals(priority)) {
            throw new BizException(1002, "优先级只能是 NORMAL、URGENT 或 RUSH");
        }
        return priority;
    }

    private String priorityOf(Object value) {
        String priority = value == null ? "" : String.valueOf(value).trim().toUpperCase();
        return priority.isEmpty() ? "NORMAL" : priority;
    }

    private boolean isSelfScope() {
        List<String> scopes = jdbcTemplate.queryForList(
                "select data_scope from sys_role where code = ?", String.class, CurrentUser.role());
        return !scopes.isEmpty() && "SELF".equals(scopes.get(0));
    }

    private void validate(OrderRequest request) {
        if (request.customerId() == null) {
            throw new BizException(1002, "请选择客户");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少添加一条订单明细");
        }
        if (masterData.customer(request.customerId()).isEmpty()) {
            throw new BizException(1002, "客户不存在");
        }
        for (OrderItem item : request.items()) {
            if (item.productId() == null || item.qty() == null || item.qty().signum() <= 0 || item.price() == null) {
                throw new BizException(1002, "订单明细的产品、数量、单价不能为空，数量必须大于 0");
            }
            if (!masterData.productExists(item.productId())) {
                throw new BizException(1002, "产品不存在");
            }
        }
    }
}
