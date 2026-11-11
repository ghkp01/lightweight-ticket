package com.mes.production;

import com.mes.common.BizException;
import com.mes.sales.SalesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤单链路：工单作废 + 订单取消。
 * 场景是「客户下单后又改主意」，要求还没开工的活能干净撤掉，已经动过料/人的不许撤。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CancelFlowTest {

    @Autowired
    private ProductionService productionService;

    @Autowired
    private SalesService salesService;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;
    private long workOrderId;
    private long salesOrderId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        productId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 0) returning id
                """, Long.class, "CP" + suffix, "撤单测试成品" + suffix);
        long materialId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'RAW', '个', 0) returning id
                """, Long.class, "CM" + suffix, "撤单测试主料" + suffix);

        salesOrderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, status, total_amount)
                values (?, (select id from customer order by id limit 1), current_date, 'IN_PRODUCTION', 100)
                returning id
                """, Long.class, "SOC" + suffix);
        jdbc.update("""
                insert into sales_order_item (order_id, product_id, qty, price, amount)
                values (?, ?, 1, 100, 100)
                """, salesOrderId, productId);

        workOrderId = jdbc.queryForObject("""
                insert into work_order (work_no, sales_order_id, product_id, plan_qty, status)
                values (?, ?, ?, 1, 'DRAFT') returning id
                """, Long.class, "WOC" + suffix, salesOrderId, productId);
        jdbc.update("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty)
                values (?, 1, '下料', 1)
                """, workOrderId);
        jdbc.update("""
                insert into work_order_bom (work_order_id, material_id, plan_qty, operation_seq, source)
                values (?, ?, 1, 1, 'STANDARD')
                """, workOrderId, materialId);
    }

    @Test
    @DisplayName("草稿工单可以作废：状态、工序、排产一起收拾干净")
    void draftWorkOrderCanBeCancelled() {
        jdbc.update("update work_order set status = 'SCHEDULED', schedule_date = current_date, schedule_seq = 3 where id = ?",
                workOrderId);

        Map<String, Object> result = productionService.cancelWorkOrder(workOrderId, "客户取消订单");

        assertEquals("CANCELLED", result.get("status"));
        Map<String, Object> row = jdbc.queryForMap("""
                select status, cancel_reason, cancelled_at, cancelled_by, schedule_date
                from work_order where id = ?
                """, workOrderId);
        assertEquals("CANCELLED", row.get("status"));
        assertEquals("客户取消订单", row.get("cancel_reason"));
        assertTrue(row.get("cancelled_at") != null, "作废时间要留痕");
        assertTrue(row.get("cancelled_by") != null, "作废人要留痕");
        assertEquals(null, row.get("schedule_date"), "作废要释放排产占用");

        String opStatus = jdbc.queryForObject(
                "select status from work_order_operation where work_order_id = ? order by seq limit 1",
                String.class, workOrderId);
        assertEquals("CANCELLED", opStatus, "工序跟着一起作废，看板不再催这单");
    }

    @Test
    @DisplayName("已领料的工单不能作废，要求先退料")
    void issuedWorkOrderCannotBeCancelled() {
        jdbc.update("update work_order_bom set issued_qty = 1 where work_order_id = ?", workOrderId);
        BizException error = assertThrows(BizException.class,
                () -> productionService.cancelWorkOrder(workOrderId, "客户取消"));
        assertEquals(2016, error.getCode());
        assertTrue(error.getMessage().contains("退料"), error.getMessage());
    }

    @Test
    @DisplayName("已有报工的工单不能作废")
    void reportedWorkOrderCannotBeCancelled() {
        long operationId = jdbc.queryForObject(
                "select id from work_order_operation where work_order_id = ? order by seq limit 1",
                Long.class, workOrderId);
        jdbc.update("""
                insert into work_report (work_order_id, operation_id, qty, qualified_qty, worker)
                values (?, ?, 1, 1, 'worker1')
                """, workOrderId, operationId);

        BizException error = assertThrows(BizException.class,
                () -> productionService.cancelWorkOrder(workOrderId, "客户取消"));
        assertEquals(2015, error.getCode());
    }

    @Test
    @DisplayName("已开工的工单不能作废，只能走完工或返工")
    void startedWorkOrderCannotBeCancelled() {
        jdbc.update("update work_order set status = 'IN_PROGRESS' where id = ?", workOrderId);
        BizException error = assertThrows(BizException.class,
                () -> productionService.cancelWorkOrder(workOrderId, "客户取消"));
        assertEquals(2014, error.getCode());
    }

    @Test
    @DisplayName("作废后订单可以重新转生产（幂等校验不再拦住作废单）")
    void cancelledWorkOrderDoesNotBlockReTransfer() {
        assertTrue(productionService.existsForSalesOrder(salesOrderId), "作废前算已转生产");
        productionService.cancelWorkOrder(workOrderId, "下错单了");
        assertFalse(productionService.existsForSalesOrder(salesOrderId), "作废后允许重新转生产");
        assertTrue(productionService.openWorkNos(salesOrderId).isEmpty(), "作废单不算未收尾");
    }

    @Test
    @DisplayName("订单取消：还有未收尾工单时拦住并指出工单号，工单作废后才放行")
    void orderCancelRequiresWorkOrdersSettled() {
        BizException blocked = assertThrows(BizException.class,
                () -> salesService.cancel(salesOrderId, "客户改需求"));
        assertEquals(2003, blocked.getCode());
        assertTrue(blocked.getMessage().contains("WOC"), blocked.getMessage());

        productionService.cancelWorkOrder(workOrderId, "客户改需求");
        salesService.cancel(salesOrderId, "客户改需求");

        Map<String, Object> order = jdbc.queryForMap(
                "select status, cancel_reason from sales_order where id = ?", salesOrderId);
        assertEquals("CANCELLED", order.get("status"));
        assertEquals("客户改需求", order.get("cancel_reason"));
        Long remaining = jdbc.queryForObject("""
                select count(*) from work_order where sales_order_id = ? and status <> 'CANCELLED'
                """, Long.class, salesOrderId);
        assertEquals(0L, remaining == null ? -1L : remaining.longValue(), "只剩作废工单");
    }
}
