package com.mes.sales;

import com.mes.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 明细级撤单：客户只砍订单里的某一两种货，其余照做。
 *
 * <p>撤行要连带撤掉这一行自己的工单，并把订单合计改成剩余有效行的金额；
 * 只砍一部分时原行减量、砍掉的数量单起一条已取消行留痕，工单按新数量缩量。
 * 已经动过料、报过工、发过货的行不许撤，宁可报错也不能让账实不符。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SalesItemCancelTest {

    @Autowired
    private SalesService salesService;

    @Autowired
    private JdbcTemplate jdbc;

    private long orderId;
    private long itemA;
    private long itemB;
    private long workA;
    private String workANo;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        long productA = newProduct("ICA" + suffix, "撤行测试成品甲");
        long productB = newProduct("ICB" + suffix, "撤行测试成品乙");
        long material = newProduct("ICM" + suffix, "撤行测试主料");

        orderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, status, total_qty, total_amount)
                values (?, (select id from customer order by id limit 1), current_date, 'IN_PRODUCTION', 30, 300)
                returning id
                """, Long.class, "SOI" + suffix);
        itemA = insertItem(orderId, productA, "10", "10");
        itemB = insertItem(orderId, productB, "20", "10");

        workA = jdbc.queryForObject("""
                insert into work_order (work_no, sales_order_id, sales_order_item_id, product_id, plan_qty, status)
                values (?, ?, ?, ?, 10, 'DRAFT') returning id
                """, Long.class, "WOI" + suffix, orderId, itemA, productA);
        workANo = "WOI" + suffix;
        jdbc.update("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, standard_minutes, unit_price)
                values (?, 1, '下料', 10, 10, 2)
                """, workA);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, operation_seq, source)
                values (?, ?, 10, 1, 'STANDARD')
                """, workA, material);
    }

    @Test
    @DisplayName("已确认/生产中订单：撤一行连带作废它的工单，合计按剩余行重算")
    void cancelOneItemKeepsTheRest() {
        Map<String, Object> result = salesService.cancelItem(orderId, itemA, null, "客户砍掉这一种");

        assertEquals("CANCELLED", itemStatus(itemA));
        assertEquals("ACTIVE", itemStatus(itemB), "另一行不受影响");
        assertEquals("CANCELLED", workOrderStatus(workA), "这一行的工单要连带作废");
        assertEquals(List.of(workANo), result.get("cancelledWorkNos"), "要回报作废了哪张工单");
        assertEquals("WHOLE", result.get("mode"));
        assertFalse(Boolean.TRUE.equals(result.get("allCancelled")));

        Map<String, Object> order = orderRow();
        assertEquals(0, new BigDecimal(String.valueOf(order.get("total_qty"))).compareTo(new BigDecimal("20")),
                "合计数量只算剩下的有效行");
        assertEquals(0, new BigDecimal(String.valueOf(order.get("total_amount"))).compareTo(new BigDecimal("200")),
                "合计金额只算剩下的有效行");
        assertEquals("IN_PRODUCTION", order.get("status"), "还有有效行，订单状态不变");
    }

    @Test
    @DisplayName("预检：干净的工单可以撤，且列出会连带作废哪张单")
    void previewShowsCancellable() {
        Map<String, Object> preview = salesService.cancelItemPreview(orderId, itemA, null);

        assertEquals(Boolean.TRUE, preview.get("cancellable"), String.valueOf(preview.get("blockers")));
        assertEquals(List.of(), preview.get("blockers"));
        assertEquals("WHOLE", preview.get("mode"));
        assertEquals(1, ((List<?>) preview.get("workOrders")).size());
        assertTrue(((List<?>) preview.get("pendingReturns")).isEmpty(), "没领料的工单不该有待退料行");
    }

    @Test
    @DisplayName("部分取消：原行减量、砍掉的数量单起一条已取消行，工单按新数量缩量")
    void partialCancelShrinksLineAndWorkOrder() {
        Map<String, Object> result = salesService.cancelItem(orderId, itemA, new BigDecimal("4"), "客户只砍 4 件");

        assertEquals("PARTIAL", result.get("mode"));
        assertEquals(0, new BigDecimal(String.valueOf(result.get("cancelQty"))).compareTo(new BigDecimal("4")));
        assertEquals(0, new BigDecimal(String.valueOf(result.get("remainingQty"))).compareTo(new BigDecimal("6")));
        assertEquals(List.of(workANo), result.get("cancelledWorkNos"), "缩量也要回报动过哪张工单");

        // 原行减量保留
        Map<String, Object> line = itemRow(itemA);
        assertEquals("ACTIVE", line.get("status"));
        assertEquals(0, new BigDecimal(String.valueOf(line.get("qty"))).compareTo(new BigDecimal("6")));
        assertEquals(0, new BigDecimal(String.valueOf(line.get("amount"))).compareTo(new BigDecimal("60")));

        // 砍掉的数量单起一条已取消行，事后查得到
        List<Map<String, Object>> cancelled = jdbc.queryForList("""
                select qty, amount, cancel_reason from sales_order_item
                where order_id = ? and status = 'CANCELLED'
                """, orderId);
        assertEquals(1, cancelled.size(), "要有留痕行");
        assertEquals(0, new BigDecimal(String.valueOf(cancelled.get(0).get("qty")))
                .compareTo(new BigDecimal("4")));
        assertEquals("客户只砍 4 件", cancelled.get(0).get("cancel_reason"));

        // 工单跟着缩：计划量、工序、BOM 用量一起变
        assertEquals(0, new BigDecimal(String.valueOf(jdbc.queryForObject(
                "select plan_qty from work_order where id = ?", BigDecimal.class, workA)))
                .compareTo(new BigDecimal("6")));
        Map<String, Object> op = jdbc.queryForMap(
                "select plan_qty, standard_minutes from work_order_operation where work_order_id = ?", workA);
        assertEquals(0, new BigDecimal(String.valueOf(op.get("plan_qty"))).compareTo(new BigDecimal("6")));
        assertEquals(0, new BigDecimal(String.valueOf(op.get("standard_minutes"))).compareTo(new BigDecimal("6")),
                "标准工时要跟着缩，否则排产还按老工时占产能");
        assertEquals(0, new BigDecimal(String.valueOf(jdbc.queryForObject(
                "select plan_qty from work_order_bom where work_order_id = ?", BigDecimal.class, workA)))
                .compareTo(new BigDecimal("6")));

        Map<String, Object> order = orderRow();
        assertEquals(0, new BigDecimal(String.valueOf(order.get("total_qty"))).compareTo(new BigDecimal("26")));
        assertEquals(0, new BigDecimal(String.valueOf(order.get("total_amount"))).compareTo(new BigDecimal("260")));
        assertEquals("IN_PRODUCTION", order.get("status"));
    }

    @Test
    @DisplayName("部分取消的预检：给出保留数量与缩量后的工单动作")
    void partialPreview() {
        Map<String, Object> preview = salesService.cancelItemPreview(orderId, itemA, new BigDecimal("4"));

        assertEquals(Boolean.TRUE, preview.get("cancellable"), String.valueOf(preview.get("blockers")));
        assertEquals("PARTIAL", preview.get("mode"));
        assertEquals(0, new BigDecimal(String.valueOf(preview.get("remainingQty"))).compareTo(new BigDecimal("6")));
        Map<?, ?> work = (Map<?, ?>) ((List<?>) preview.get("workOrders")).get(0);
        assertEquals("RESIZE", work.get("action"));
    }

    @Test
    @DisplayName("部分取消后再撤剩下的：走整行取消，工单作废")
    void partialThenWhole() {
        salesService.cancelItem(orderId, itemA, new BigDecimal("4"), null);
        Map<String, Object> result = salesService.cancelItem(orderId, itemA, null, "剩下的也不要了");

        assertEquals("WHOLE", result.get("mode"));
        assertEquals("CANCELLED", itemStatus(itemA));
        assertEquals("CANCELLED", workOrderStatus(workA));
        assertEquals(0, new BigDecimal(String.valueOf(orderRow().get("total_amount")))
                .compareTo(new BigDecimal("200")), "只剩另一行");
    }

    @Test
    @DisplayName("部分取消的数量校验：超过行数量、等于 0 都要报错")
    void partialQtyValidation() {
        BizException tooMuch = assertThrows(BizException.class,
                () -> salesService.cancelItem(orderId, itemA, new BigDecimal("20"), null));
        assertTrue(tooMuch.getMessage().contains("不能超过"), tooMuch.getMessage());

        BizException zero = assertThrows(BizException.class,
                () -> salesService.cancelItem(orderId, itemA, BigDecimal.ZERO, null));
        assertTrue(zero.getMessage().contains("必须大于 0"), zero.getMessage());

        assertEquals(0, new BigDecimal(String.valueOf(itemRow(itemA).get("qty")))
                .compareTo(new BigDecimal("10")), "校验失败不能改动数量");
    }

    @Test
    @DisplayName("工单已领料：部分取消同样被拒，预检给出待退料清单")
    void partialBlockedByIssuedMaterial() {
        jdbc.update("update work_order_bom set issued_qty = 6 where work_order_id = ?", workA);

        Map<String, Object> preview = salesService.cancelItemPreview(orderId, itemA, new BigDecimal("4"));
        assertEquals(Boolean.FALSE, preview.get("cancellable"));
        assertTrue(String.valueOf(preview.get("blockers")).contains("已领料"), String.valueOf(preview.get("blockers")));
        List<?> returns = (List<?>) preview.get("pendingReturns");
        assertEquals(1, returns.size(), "要摆出待退料清单");
        Map<?, ?> line = (Map<?, ?>) returns.get(0);
        assertEquals(0, new BigDecimal(String.valueOf(line.get("returnableQty"))).compareTo(new BigDecimal("6")));

        BizException ex = assertThrows(BizException.class,
                () -> salesService.cancelItem(orderId, itemA, new BigDecimal("4"), null));
        assertTrue(ex.getMessage().contains("请先做生产退料"), ex.getMessage());
        assertEquals("ACTIVE", itemStatus(itemA), "被拦回来时明细状态不能变");
        assertEquals(0, new BigDecimal(String.valueOf(itemRow(itemA).get("qty")))
                .compareTo(new BigDecimal("10")), "数量也不能变");
    }

    @Test
    @DisplayName("已发货的行不能撤，提示走退货")
    void blockedByShipped() {
        jdbc.update("update sales_order_item set shipped_qty = 2 where id = ?", itemA);

        BizException ex = assertThrows(BizException.class,
                () -> salesService.cancelItem(orderId, itemA, null, null));
        assertTrue(ex.getMessage().contains("已发货"), ex.getMessage());
    }

    @Test
    @DisplayName("草稿订单不走撤行，提示直接编辑")
    void draftOrderShouldBeEdited() {
        jdbc.update("update sales_order set status = 'DRAFT' where id = ?", orderId);

        BizException ex = assertThrows(BizException.class,
                () -> salesService.cancelItem(orderId, itemA, null, null));
        assertTrue(ex.getMessage().contains("草稿订单"), ex.getMessage());
    }

    @Test
    @DisplayName("同一行不能撤两次")
    void cannotCancelTwice() {
        salesService.cancelItem(orderId, itemA, null, null);

        BizException ex = assertThrows(BizException.class,
                () -> salesService.cancelItem(orderId, itemA, null, null));
        assertTrue(ex.getMessage().contains("已经取消过"), ex.getMessage());
    }

    @Test
    @DisplayName("工单已经作废过：撤行照样成功，不重复作废")
    void alreadyCancelledWorkOrderIsFine() {
        jdbc.update("update work_order set status = 'CANCELLED' where id = ?", workA);

        Map<String, Object> result = salesService.cancelItem(orderId, itemA, null, "客户砍单");
        assertEquals(List.of(), result.get("cancelledWorkNos"));
        assertEquals("CANCELLED", itemStatus(itemA));
    }

    @Test
    @DisplayName("两行都撤完：订单自动转已取消，合计归零")
    void cancelAllItemsCancelsOrder() {
        salesService.cancelItem(orderId, itemA, null, null);
        Map<String, Object> result = salesService.cancelItem(orderId, itemB, null, null);

        assertTrue(Boolean.TRUE.equals(result.get("allCancelled")));
        Map<String, Object> order = orderRow();
        assertEquals("CANCELLED", order.get("status"));
        assertEquals(0, new BigDecimal(String.valueOf(order.get("total_amount"))).signum());
    }

    // ---------------------------------------------------------------- 辅助

    private long newProduct(String code, String name) {
        return jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 10) returning id
                """, Long.class, code, name);
    }

    private long insertItem(long orderId, long productId, String qty, String price) {
        BigDecimal amount = new BigDecimal(qty).multiply(new BigDecimal(price));
        return jdbc.queryForObject("""
                insert into sales_order_item (order_id, product_id, qty, price, amount)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, orderId, productId,
                new BigDecimal(qty), new BigDecimal(price), amount);
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("select status from sales_order_item where id = ?", String.class, itemId);
    }

    private Map<String, Object> itemRow(long itemId) {
        return jdbc.queryForMap("select status, qty, amount from sales_order_item where id = ?", itemId);
    }

    private String workOrderStatus(long workOrderId) {
        return jdbc.queryForObject("select status from work_order where id = ?", String.class, workOrderId);
    }

    private Map<String, Object> orderRow() {
        return jdbc.queryForMap("select status, total_qty, total_amount, cancel_reason from sales_order where id = ?",
                orderId);
    }
}
