package com.mes.planning;

import com.mes.common.BizException;
import com.mes.masterdata.MasterDataService;
import com.mes.sales.SalesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单 BOM 任务与 MRP 的 BOM 门槛：
 * 任务没完成（没有生效 BOM）时 MRP 不算这一单，完成后才参与运算。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BomTaskTest {

    @Autowired
    private BomTaskService bomTasks;

    @Autowired
    private SalesService sales;

    @Autowired
    private MasterDataService masterData;

    @Autowired
    private SalesMrpService mrp;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("BOM 任务派工、完成与 MRP 门槛")
    void bomTaskGate() {
        try {
            login();
            String suffix = Long.toString(System.nanoTime());
            long customerId = jdbc.queryForObject(
                    "select id from customer order by id limit 1", Long.class);
            long adminId = jdbc.queryForObject(
                    "select id from sys_user where username = 'admin'", Long.class);

            long productId = createMakeProduct("BOMT-" + suffix, suffix);
            long orderId = sales.create(order(customerId, productId));
            sales.confirm(orderId);
            long itemId = jdbc.queryForObject(
                    "select id from sales_order_item where order_id = ?", Long.class, orderId);

            // 要求完成日期不能早于今天
            assertThrows(BizException.class, () -> bomTasks.assign(new BomTaskService.AssignRequest(
                    List.of(itemId), adminId, LocalDate.now().minusDays(1), "过期日期")));

            assertEquals(1, bomTasks.assign(new BomTaskService.AssignRequest(
                    List.of(itemId), adminId, LocalDate.now().plusDays(2), "先出 BOM")));
            long taskId = jdbc.queryForObject(
                    "select id from bom_task where sales_order_item_id = ?", Long.class, itemId);
            // 列表关键字命中订单号/产品，分页在数据库完成
            assertEquals(1, bomTasks.list("BOMT-" + suffix, "PENDING", false, 1, 20).total());
            // 没有生效 BOM 时不能完成任务
            assertThrows(BizException.class, () -> bomTasks.complete(taskId));

            Long bomId = jdbc.queryForObject("""
                    insert into bom (bom_no, product_id, version, status)
                    values (?, ?, 'V1.0', 'ACTIVE')
                    returning id
                    """, Long.class, "BOMT" + suffix, productId);
            long rawId = createBuyProduct("RAW-" + suffix, suffix);
            jdbc.update("insert into bom_item (bom_id, material_id, qty) values (?, ?, 2)",
                    bomId, rawId);
            bomTasks.complete(taskId);
            assertEquals("DONE", jdbc.queryForObject(
                    "select status from bom_task where id = ?", String.class, taskId));

            // 另一张订单没有 BOM：全局 MRP 跳过并列出「待补 BOM」，订单状态保持已确认
            long product2 = createMakeProduct("BOMU-" + suffix, suffix);
            long order2 = sales.create(order(customerId, product2));
            sales.confirm(order2);

            Map<String, Object> result = mrp.runGlobal(null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pending =
                    (List<Map<String, Object>>) result.get("bomPending");
            assertTrue(pending.stream().anyMatch(row ->
                    ((Number) row.get("salesOrderId")).longValue() == order2));
            assertEquals("CONFIRMED", jdbc.queryForObject(
                    "select status from sales_order where id = ?", String.class, order2));
            assertThrows(BizException.class, () -> mrp.run(order2, null));

            // 有生效 BOM 的订单正常进入生产中
            assertEquals("IN_PRODUCTION", jdbc.queryForObject(
                    "select status from sales_order where id = ?", String.class, orderId));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("半成品层缺 BOM 时 MRP 整体中止，不生成任何工单")
    void mrpBlocksWhenSemiBomMissing() {
        try {
            login();
            String suffix = Long.toString(System.nanoTime());
            long customerId = jdbc.queryForObject(
                    "select id from customer order by id limit 1", Long.class);
            long semiId = createMakeProduct("SEMI-" + suffix, suffix);
            long finishedId = createMakeProduct("FG-" + suffix, suffix);
            Long bomId = jdbc.queryForObject("""
                    insert into bom (bom_no, product_id, version, status)
                    values (?, ?, 'V1.0', 'ACTIVE')
                    returning id
                    """, Long.class, "BOMF" + suffix, finishedId);
            jdbc.update("insert into bom_item (bom_id, material_id, qty) values (?, ?, 1)",
                    bomId, semiId);

            long orderId = sales.create(order(customerId, finishedId));
            sales.confirm(orderId);
            BizException error = assertThrows(BizException.class, () -> mrp.run(orderId, null));
            assertTrue(error.getMessage().contains("SEMI-" + suffix));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from work_order where sales_order_id = ?
                    """, Integer.class, orderId));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long createMakeProduct(String code, String suffix) {
        return masterData.createProduct(new MasterDataService.ProductRequest(
                code, "BOM 测试件 " + suffix, "SPEC", "FINISHED", "件",
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                false, "NONE", null, "MAKE", 1, null));
    }

    private long createBuyProduct(String code, String suffix) {
        return masterData.createProduct(new MasterDataService.ProductRequest(
                code, "原料 " + suffix, "SPEC", "RAW", "件",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                false, "NONE", null, "BUY", 1, null));
    }

    private SalesService.OrderRequest order(long customerId, long productId) {
        return new SalesService.OrderRequest(
                customerId, LocalDate.now(), LocalDate.now().plusDays(7), "BOM 任务测试",
                List.of(new SalesService.OrderItem(productId, BigDecimal.ONE, BigDecimal.TEN)),
                "NORMAL", null, null, null, null, null);
    }

    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
