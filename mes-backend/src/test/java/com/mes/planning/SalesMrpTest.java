package com.mes.planning;

import com.mes.common.BizException;
import com.mes.production.ProductionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 销售订单 MRP：按用户给出的控制柜示例校验净需求、跨层汇总与单据生成。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SalesMrpTest {

    @Autowired
    private SalesMrpService service;

    @Autowired
    private ProductionService productionService;

    @Autowired
    private JdbcTemplate jdbc;

    private long product(String code, String name, String category, String source, int leadDays) {
        return jdbc.queryForObject("""
                insert into product (code, name, category, unit, price, source, lead_days)
                values (?, ?, ?, '件', 1, ?, ?) returning id
                """, Long.class, code, name, category, source, leadDays);
    }

    private void stock(long productId, String qty) {
        Long warehouseId = jdbc.queryForObject("select id from warehouse order by id limit 1", Long.class);
        jdbc.update("""
                insert into inventory (product_id, warehouse_id, qty, locked_qty)
                values (?, ?, ?, 0)
                on conflict (product_id, warehouse_id) do update set qty = excluded.qty, locked_qty = 0
                """, productId, warehouseId, new BigDecimal(qty));
    }

    private void bom(long productId, String version, Object... lines) {
        String tag = String.valueOf(System.nanoTime());
        Long bomId = jdbc.queryForObject("""
                insert into bom (bom_no, product_id, version, status, base_qty, effective_from)
                values (?, ?, ?, 'ACTIVE', 1, current_date) returning id
                """, Long.class, "BOMT" + productId + tag, productId, version);
        for (int i = 0; i < lines.length; i += 2) {
            jdbc.update("""
                    insert into bom_item (bom_id, material_id, qty, operation_seq)
                    values (?, ?, ?, 1)
                    """, bomId, ((Number) lines[i]).longValue(), new BigDecimal(String.valueOf(lines[i + 1])));
        }
    }

    @Test
    @DisplayName("控制柜示例：成品 7、半成品 3/14、PU-502 合并采购 80、RM-402 采购 13")
    void controlCabinetExample() {
        long fg = product("FG-100", "控制柜成品", "FINISHED", "MAKE", 3);
        long sf200 = product("SF-200", "柜体组件", "SEMI", "MAKE", 2);
        long sf300 = product("SF-300", "接线模块", "SEMI", "MAKE", 2);
        long rm401 = product("RM-401", "冷轧钢板", "RAW", "BUY", 10);
        long rm402 = product("RM-402", "铜排", "RAW", "BUY", 7);
        long pu501 = product("PU-501", "PLC控制器", "PURCHASED", "BUY", 15);
        long pu502 = product("PU-502", "端子排", "PURCHASED", "BUY", 5);
        long pu503 = product("PU-503", "断路器", "PURCHASED", "BUY", 7);

        stock(fg, "3");
        stock(sf200, "4");
        stock(sf300, "0");
        stock(rm401, "80");
        stock(rm402, "15");
        stock(pu501, "8");
        stock(pu502, "60");
        stock(pu503, "6");

        bom(fg, "V1.0", sf200, "1", sf300, "2", pu501, "1", pu502, "10");
        bom(sf200, "V1.0", rm401, "5", pu503, "1");
        bom(sf300, "V1.0", rm402, "2", pu502, "5");

        long orderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, delivery_date, status, total_amount)
                values (?, (select id from customer order by id limit 1), current_date, ?, 'CONFIRMED', 100)
                returning id
                """, Long.class, "SOMRP" + System.nanoTime(), LocalDate.now().plusDays(30));
        jdbc.update("""
                insert into sales_order_item (order_id, product_id, qty, price, amount)
                values (?, ?, 10, 10, 100)
                """, orderId, fg);

        Map<String, Object> result = service.run(orderId, 6);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> make = (List<Map<String, Object>>) result.get("make");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buy = (List<Map<String, Object>>) result.get("buy");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> works = (List<Map<String, Object>>) result.get("workOrders");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqs = (List<Map<String, Object>>) result.get("requisitions");

        Map<String, Map<String, Object>> makeByCode = byCode(make, "productCode");
        Map<String, Map<String, Object>> buyByCode = byCode(buy, "productCode");

        assertEquals(0, decimal(makeByCode.get("FG-100").get("netNeed")).compareTo(new BigDecimal("7")));
        assertEquals(0, decimal(makeByCode.get("SF-200").get("netNeed")).compareTo(new BigDecimal("3")));
        assertEquals(0, decimal(makeByCode.get("SF-300").get("netNeed")).compareTo(new BigDecimal("14")));

        assertEquals(0, decimal(buyByCode.get("PU-501").get("netNeed")).compareTo(BigDecimal.ZERO));
        assertEquals(0, decimal(buyByCode.get("PU-502").get("grossNeed")).compareTo(new BigDecimal("140")));
        assertEquals(0, decimal(buyByCode.get("PU-502").get("netNeed")).compareTo(new BigDecimal("80")));
        assertEquals(0, decimal(buyByCode.get("RM-402").get("netNeed")).compareTo(new BigDecimal("13")));
        assertEquals(0, decimal(buyByCode.get("RM-401").get("netNeed")).compareTo(BigDecimal.ZERO));
        assertEquals(0, decimal(buyByCode.get("PU-503").get("netNeed")).compareTo(BigDecimal.ZERO));

        assertEquals(3, works.size(), "成品 1 张 + 半成品 2 张");
        assertEquals(2, reqs.size(), "PU-502 与 RM-402 各一张采购申请");
        assertEquals(0, decimal(byCode(works, "productCode").get("FG-100").get("qty"))
                .compareTo(new BigDecimal("7")));
        assertEquals(0, decimal(byCode(reqs, "productCode").get("PU-502").get("qty"))
                .compareTo(new BigDecimal("80")));
        assertEquals(0, decimal(byCode(reqs, "productCode").get("RM-402").get("qty"))
                .compareTo(new BigDecimal("13")));
        assertEquals("IN_PRODUCTION", result.get("status"));
    }

    @Test
    @DisplayName("全局 MRP：成品库存按交期 ATP 预留，组件需求按物料汇总只扣一次库存")
    void globalAtpAndReservation() {
        long fg = product("FG-100", "控制柜成品", "FINISHED", "MAKE", 3);
        long sf200 = product("SF-200", "柜体组件", "SEMI", "MAKE", 2);
        long sf300 = product("SF-300", "接线模块", "SEMI", "MAKE", 2);
        long rm401 = product("RM-401", "冷轧钢板", "RAW", "BUY", 10);
        long rm402 = product("RM-402", "铜排", "RAW", "BUY", 7);
        long pu501 = product("PU-501", "PLC控制器", "PURCHASED", "BUY", 15);
        long pu502 = product("PU-502", "端子排", "PURCHASED", "BUY", 5);
        long pu503 = product("PU-503", "断路器", "PURCHASED", "BUY", 7);

        stock(fg, "3");
        stock(sf200, "4");
        stock(sf300, "0");
        stock(rm401, "80");
        stock(rm402, "15");
        stock(pu501, "8");
        stock(pu502, "60");
        stock(pu503, "6");

        bom(fg, "V1.0", sf200, "1", sf300, "2", pu501, "1", pu502, "10");
        bom(sf200, "V1.0", rm401, "5", pu503, "1");
        bom(sf300, "V1.0", rm402, "2", pu502, "5");

        // 种子数据里还有已确认订单，先移出全局 MRP 范围，避免影响断言
        jdbc.update("update sales_order set status = 'COMPLETED' where status = 'CONFIRMED'");

        long early = salesOrder("SOGLOBAL1", 30, fg, 10);
        long late = salesOrder("SOGLOBAL2", 40, fg, 5);

        Map<String, Object> result = service.runGlobal(6);

        assertEquals(2, result.get("orderCount"));
        assertEquals(4, result.get("createdWorkOrders"), "成品 2 张 + 半成品 2 张");
        assertEquals(4, result.get("createdRequisitions"), "PU-501、PU-502、PU-503、RM-402");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) result.get("orders");
        Map<String, Map<String, Object>> orderByNo = byCode(orders, "orderNo");
        assertEquals(0, decimal(orderByNo.get("SOGLOBAL1").get("reservedQty"))
                .compareTo(new BigDecimal("3")), "交期早的订单先占用 3 台库存");
        assertEquals(0, decimal(orderByNo.get("SOGLOBAL1").get("netQty"))
                .compareTo(new BigDecimal("7")));
        assertEquals(0, decimal(orderByNo.get("SOGLOBAL2").get("reservedQty"))
                .compareTo(BigDecimal.ZERO), "库存已被交期早的订单占满");
        assertEquals(0, decimal(orderByNo.get("SOGLOBAL2").get("netQty"))
                .compareTo(new BigDecimal("5")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> make = (List<Map<String, Object>>) result.get("make");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buy = (List<Map<String, Object>>) result.get("buy");
        Map<String, Map<String, Object>> makeByCode = byCode(make, "productCode");
        Map<String, Map<String, Object>> buyByCode = byCode(buy, "productCode");

        assertEquals(0, decimal(makeByCode.get("FG-100").get("netNeed")).compareTo(new BigDecimal("12")));
        assertEquals(0, decimal(makeByCode.get("SF-200").get("netNeed")).compareTo(new BigDecimal("8")));
        assertEquals(0, decimal(makeByCode.get("SF-300").get("netNeed")).compareTo(new BigDecimal("24")));
        assertEquals(0, decimal(buyByCode.get("PU-501").get("netNeed")).compareTo(new BigDecimal("4")));
        assertEquals(0, decimal(buyByCode.get("PU-502").get("grossNeed")).compareTo(new BigDecimal("240")));
        assertEquals(0, decimal(buyByCode.get("PU-502").get("netNeed")).compareTo(new BigDecimal("180")));
        assertEquals(0, decimal(buyByCode.get("PU-503").get("netNeed")).compareTo(new BigDecimal("2")));
        assertEquals(0, decimal(buyByCode.get("RM-402").get("netNeed")).compareTo(new BigDecimal("33")));
        assertEquals(0, decimal(buyByCode.get("RM-401").get("netNeed")).compareTo(BigDecimal.ZERO));

        Integer active = jdbc.queryForObject(
                "select count(*) from sales_stock_reservation where status = 'ACTIVE'", Integer.class);
        assertEquals(1, active, "只有交期早的订单拿到库存，写一条 ACTIVE 预留");
        BigDecimal reserved = jdbc.queryForObject(
                "select sum(qty) from sales_stock_reservation where status = 'ACTIVE'", BigDecimal.class);
        assertEquals(0, reserved.compareTo(new BigDecimal("3")));

        // 两张订单已进入生产中，再次全局 MRP 不应重复占用库存或重复建单
        Map<String, Object> again = service.runGlobal(6);
        assertEquals(0, again.get("orderCount"));
        assertEquals(0, again.get("createdWorkOrders"));
        assertEquals(0, again.get("createdRequisitions"));
        Integer stillActive = jdbc.queryForObject(
                "select count(*) from sales_stock_reservation where status = 'ACTIVE'", Integer.class);
        assertEquals(1, stillActive);

        // 取消交期早的订单后再跑，预留应释放，第二张订单仍未发完但已进入生产，不在全局范围
        jdbc.update("update sales_order set status = 'CANCELLED' where id = ?", early);
        service.runGlobal(6);
        Integer afterCancel = jdbc.queryForObject(
                "select count(*) from sales_stock_reservation where status = 'ACTIVE'", Integer.class);
        assertEquals(0, afterCancel, "取消订单的预留应被释放");
        assertEquals("IN_PRODUCTION", jdbc.queryForObject(
                "select status from sales_order where id = ?", String.class, late));
    }

    @Test
    @DisplayName("跨轮次组件预留：先跑 A 再跑 B，B 不会重复使用 A 已占用的组件库存")
    void crossRunComponentCommitment() {
        long fg = product("FG-100", "控制柜成品", "FINISHED", "MAKE", 3);
        long sf200 = product("SF-200", "柜体组件", "SEMI", "MAKE", 2);
        long sf300 = product("SF-300", "接线模块", "SEMI", "MAKE", 2);
        long rm401 = product("RM-401", "冷轧钢板", "RAW", "BUY", 10);
        long rm402 = product("RM-402", "铜排", "RAW", "BUY", 7);
        long pu501 = product("PU-501", "PLC控制器", "PURCHASED", "BUY", 15);
        long pu502 = product("PU-502", "端子排", "PURCHASED", "BUY", 5);
        long pu503 = product("PU-503", "断路器", "PURCHASED", "BUY", 7);

        stock(fg, "3");
        stock(sf200, "4");
        stock(sf300, "0");
        stock(rm401, "80");
        stock(rm402, "15");
        stock(pu501, "8");
        stock(pu502, "60");
        stock(pu503, "6");

        bom(fg, "V1.0", sf200, "1", sf300, "2", pu501, "1", pu502, "10");
        bom(sf200, "V1.0", rm401, "5", pu503, "1");
        bom(sf300, "V1.0", rm402, "2", pu502, "5");

        jdbc.update("update sales_order set status = 'COMPLETED' where status = 'CONFIRMED'");

        salesOrder("SOCROSS1", 30, fg, 10);
        Map<String, Object> first = service.runGlobal(6);
        assertEquals(1, first.get("orderCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstBuy = (List<Map<String, Object>>) first.get("buy");
        Map<String, Map<String, Object>> firstBuyByCode = byCode(firstBuy, "productCode");
        assertEquals(0, decimal(firstBuyByCode.get("PU-502").get("netNeed"))
                .compareTo(new BigDecimal("80")), "第一轮按库存 60 净买 80");

        // 第一轮已生成 FG/SF-200/SF-300 三张在制工单，它们的未领料需求应占用组件库存
        salesOrder("SOCROSS2", 40, fg, 5);
        Map<String, Object> second = service.runGlobal(6);
        assertEquals(1, second.get("orderCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondMake = (List<Map<String, Object>>) second.get("make");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondBuy = (List<Map<String, Object>>) second.get("buy");
        Map<String, Map<String, Object>> makeByCode = byCode(secondMake, "productCode");
        Map<String, Map<String, Object>> buyByCode = byCode(secondBuy, "productCode");

        assertEquals(0, decimal(makeByCode.get("SF-200").get("netNeed"))
                .compareTo(new BigDecimal("5")), "SF-200 库存 4 已被第一轮工单占用，第二轮需再生产 5");
        assertEquals(0, decimal(makeByCode.get("SF-300").get("netNeed"))
                .compareTo(new BigDecimal("10")));

        assertEquals(0, decimal(buyByCode.get("PU-502").get("committed"))
                .compareTo(new BigDecimal("140")), "第一轮工单已占用 PU-502 140");
        assertEquals(0, decimal(buyByCode.get("PU-502").get("netNeed"))
                .compareTo(new BigDecimal("100")), "不能再把库存 60 用一次，第二轮应净买 100");
        assertEquals(0, decimal(buyByCode.get("PU-501").get("netNeed"))
                .compareTo(new BigDecimal("4")), "PU-501 可用库存只剩 1");
        assertEquals(0, decimal(buyByCode.get("PU-503").get("netNeed"))
                .compareTo(new BigDecimal("2")), "PU-503 可用库存只剩 3");
        assertEquals(0, decimal(buyByCode.get("RM-402").get("netNeed"))
                .compareTo(new BigDecimal("20")), "RM-402 库存 15 已被第一轮占用");
        assertEquals(0, decimal(buyByCode.get("RM-401").get("netNeed"))
                .compareTo(BigDecimal.ZERO));

        // 第一轮未用完的成品库存仍只预留给第一张订单，第二张订单 5 台全部要生产
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondOrders = (List<Map<String, Object>>) second.get("orders");
        Map<String, Map<String, Object>> orderByNo = byCode(secondOrders, "orderNo");
        assertEquals(0, decimal(orderByNo.get("SOCROSS2").get("reservedQty"))
                .compareTo(BigDecimal.ZERO));
        assertEquals(0, decimal(orderByNo.get("SOCROSS2").get("netQty"))
                .compareTo(new BigDecimal("5")));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from work_order w
                join sales_order so on so.id = w.sales_order_id
                where so.order_no = 'SOCROSS1'
                """, Integer.class), "第一张订单只生成一张成品工单");
    }

    @Test
    @DisplayName("安全库存参与 MRP：销售需求不能吃掉安全库存")
    void safetyStockIsPreserved() {
        long fg = product("FG-100", "控制柜成品", "FINISHED", "MAKE", 3);
        long sf200 = product("SF-200", "柜体组件", "SEMI", "MAKE", 2);
        long sf300 = product("SF-300", "接线模块", "SEMI", "MAKE", 2);
        long rm401 = product("RM-401", "冷轧钢板", "RAW", "BUY", 10);
        long rm402 = product("RM-402", "铜排", "RAW", "BUY", 7);
        long pu501 = product("PU-501", "PLC控制器", "PURCHASED", "BUY", 15);
        long pu502 = product("PU-502", "端子排", "PURCHASED", "BUY", 5);
        long pu503 = product("PU-503", "断路器", "PURCHASED", "BUY", 7);

        stock(fg, "3");
        stock(sf200, "4");
        stock(sf300, "0");
        stock(rm401, "80");
        stock(rm402, "15");
        stock(pu501, "8");
        stock(pu502, "60");
        stock(pu503, "6");

        safety(rm401, "50");
        safety(rm402, "20");
        safety(pu501, "5");
        safety(pu502, "50");
        safety(pu503, "10");

        bom(fg, "V1.0", sf200, "1", sf300, "2", pu501, "1", pu502, "10");
        bom(sf200, "V1.0", rm401, "5", pu503, "1");
        bom(sf300, "V1.0", rm402, "2", pu502, "5");

        jdbc.update("update sales_order set status = 'COMPLETED' where status = 'CONFIRMED'");
        long orderId = salesOrder("SOSAFETY1", 30, fg, 10);

        Map<String, Object> result = service.run(orderId, 6);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buy = (List<Map<String, Object>>) result.get("buy");
        Map<String, Map<String, Object>> buyByCode = byCode(buy, "productCode");

        assertEquals(0, decimal(buyByCode.get("PU-502").get("netNeed"))
                .compareTo(new BigDecimal("130")), "库存 60 先保留安全库存 50，只剩 10 可抵需求");
        assertEquals(0, decimal(buyByCode.get("PU-502").get("safetyStock"))
                .compareTo(new BigDecimal("50")));
        assertEquals(0, decimal(buyByCode.get("PU-501").get("netNeed"))
                .compareTo(new BigDecimal("4")), "库存 8 保留安全库存 5，只剩 3 可用");
        assertEquals(0, decimal(buyByCode.get("PU-503").get("netNeed"))
                .compareTo(new BigDecimal("3")), "库存低于安全库存时不能再被消耗");
        assertEquals(0, decimal(buyByCode.get("RM-402").get("netNeed"))
                .compareTo(new BigDecimal("28")), "库存 15 低于安全库存 20，全部按需求采购");
        assertEquals(0, decimal(buyByCode.get("RM-401").get("netNeed"))
                .compareTo(BigDecimal.ZERO), "库存 80 扣掉安全库存 50 仍够用");
        assertEquals(4, ((List<?>) result.get("requisitions")).size(),
                "PU-501、PU-502、PU-503、RM-402 各一张采购申请");
    }

    @Test
    @DisplayName("半成品工单先于成品工单：前置未完工时成品不能下达")
    void semiWorkOrdersMustFinishFirst() {
        long fg = product("FG-100", "控制柜成品", "FINISHED", "MAKE", 3);
        long sf200 = product("SF-200", "柜体组件", "SEMI", "MAKE", 2);
        long sf300 = product("SF-300", "接线模块", "SEMI", "MAKE", 2);
        long rm401 = product("RM-401", "冷轧钢板", "RAW", "BUY", 10);
        long rm402 = product("RM-402", "铜排", "RAW", "BUY", 7);
        long pu501 = product("PU-501", "PLC控制器", "PURCHASED", "BUY", 15);
        long pu502 = product("PU-502", "端子排", "PURCHASED", "BUY", 5);
        long pu503 = product("PU-503", "断路器", "PURCHASED", "BUY", 7);

        stock(fg, "3");
        stock(sf200, "4");
        stock(sf300, "0");
        stock(rm401, "80");
        stock(rm402, "15");
        stock(pu501, "8");
        stock(pu502, "60");
        stock(pu503, "6");
        safety(pu502, "50");

        bom(fg, "V1.0", sf200, "1", sf300, "2", pu501, "1", pu502, "10");
        bom(sf200, "V1.0", rm401, "5", pu503, "1");
        bom(sf300, "V1.0", rm402, "2", pu502, "5");

        jdbc.update("update sales_order set status = 'COMPLETED' where status = 'CONFIRMED'");
        long orderId = salesOrder("SODEP1", 30, fg, 10);

        Map<String, Object> result = service.run(orderId, 6);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> works = (List<Map<String, Object>>) result.get("workOrders");
        Map<String, Map<String, Object>> byProduct = byCode(works, "productCode");
        long fgWorkOrderId = ((Number) byProduct.get("FG-100").get("id")).longValue();
        long sf200WorkOrderId = ((Number) byProduct.get("SF-200").get("id")).longValue();
        long sf300WorkOrderId = ((Number) byProduct.get("SF-300").get("id")).longValue();

        assertEquals(2, jdbc.queryForObject(
                "select count(*) from work_order_dependency", Integer.class),
                "SF-200、SF-300 各有一条指向成品工单的依赖");
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from work_order_dependency
                where predecessor_id = ? and successor_id = ?
                """, Integer.class, sf200WorkOrderId, fgWorkOrderId));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from work_order_dependency
                where predecessor_id = ? and successor_id = ?
                """, Integer.class, sf300WorkOrderId, fgWorkOrderId));

        // 成品工单排产并补一道工序后尝试下达：半成品还没完工，应被拦住
        jdbc.update("""
                insert into work_order_operation (work_order_id, seq, name, plan_qty, done_qty, status)
                values (?, 1, '装配', ?, 0, 'PENDING')
                """, fgWorkOrderId, new BigDecimal("7"));
        jdbc.update("update work_order set status = 'SCHEDULED' where id = ?", fgWorkOrderId);

        BizException blocked = assertThrows(BizException.class,
                () -> productionService.release(fgWorkOrderId));
        assertTrue(blocked.getMessage().contains("前置半成品工单"));

        // 半成品完工后，成品工单才允许下达
        jdbc.update("update work_order set status = 'FINISHED' where id in (?, ?)",
                sf200WorkOrderId, sf300WorkOrderId);
        productionService.release(fgWorkOrderId);
        assertEquals("RELEASED", jdbc.queryForObject(
                "select status from work_order where id = ?", String.class, fgWorkOrderId));
    }

    private void safety(long productId, String qty) {
        jdbc.update("update product set safety_stock = ? where id = ?",
                new BigDecimal(qty), productId);
    }

    private long salesOrder(String orderNo, int deliveryInDays, long productId, int qty) {
        long orderId = jdbc.queryForObject("""
                insert into sales_order (order_no, customer_id, order_date, delivery_date, status, total_amount)
                values (?, (select id from customer order by id limit 1), current_date, ?, 'CONFIRMED', 100)
                returning id
                """, Long.class, orderNo, LocalDate.now().plusDays(deliveryInDays));
        jdbc.update("""
                insert into sales_order_item (order_id, product_id, qty, price, amount)
                values (?, ?, ?, 10, 100)
                """, orderId, productId, qty);
        return orderId;
    }

    private Map<String, Map<String, Object>> byCode(List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> map = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            map.put(String.valueOf(row.get(key)), row);
        }
        return map;
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
