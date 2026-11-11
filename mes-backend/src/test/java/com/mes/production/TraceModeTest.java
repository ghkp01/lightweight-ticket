package com.mes.production;

import com.mes.common.BizException;
import com.mes.masterdata.MasterDataService;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追溯方式：产品给默认、工单做快照、下达前可改。
 *
 * <p>重点盯住三件事：① 老接口只传 serialManaged 时折算成 trace_mode；
 * ② 改产品不会让在制工单的追溯口径跟着变；③ 下达后不能再改。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TraceModeTest {

    @Autowired
    private MasterDataService masterData;

    @Autowired
    private ProductionService production;

    @Autowired
    private WorkOrderUnitService units;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String randomCode(String prefix) {
        return prefix + ThreadLocalRandom.current().nextInt(100000, 999999);
    }

    private long newProduct(String traceMode, Boolean serialManaged) {
        return masterData.createProduct(new MasterDataService.ProductRequest(
                randomCode("TM"), "追溯方式测试件", "TM-T", "FINISHED", "件",
                new BigDecimal("10"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO,
                serialManaged, traceMode));
    }

    private Map<String, Object> productRow(long id) {
        return jdbcTemplate.queryForMap("""
                select trace_mode as "traceMode", serial_managed as "serialManaged" from product where id = ?
                """, id);
    }

    private long newWorkOrder(long productId, BigDecimal planQty, String status, String traceMode) {
        return jdbcTemplate.queryForObject("""
                insert into work_order (work_no, product_id, plan_qty, status, plan_start, plan_end, trace_mode)
                values (?, ?, ?, ?, current_date, current_date, ?) returning id
                """, Long.class, randomCode("TMWO"), productId, planQty, status, traceMode);
    }

    private String workTraceMode(long workOrderId) {
        return jdbcTemplate.queryForObject(
                "select trace_mode from work_order where id = ?", String.class, workOrderId);
    }

    @Test
    @DisplayName("产品：traceMode 优先，老接口只传 serialManaged 也能折算")
    void productTraceMode() {
        long serial = newProduct("SERIAL", null);
        Map<String, Object> serialRow = productRow(serial);
        assertEquals("SERIAL", String.valueOf(serialRow.get("traceMode")));
        assertEquals(Boolean.TRUE, serialRow.get("serialManaged"), "serial_managed 应与 trace_mode 同步");

        // 老客户端只传布尔
        long legacy = newProduct(null, true);
        assertEquals("SERIAL", String.valueOf(productRow(legacy).get("traceMode")));

        // 两个都传时以 traceMode 为准
        long both = newProduct("NONE", true);
        Map<String, Object> bothRow = productRow(both);
        assertEquals("NONE", String.valueOf(bothRow.get("traceMode")));
        assertEquals(Boolean.FALSE, bothRow.get("serialManaged"));

        assertThrows(BizException.class, () -> newProduct("BY_LOT", null));
    }

    @Test
    @DisplayName("工单快照：改了产品的追溯方式，在制工单口径不变")
    void workOrderSnapshotIsIndependent() {
        long productId = newProduct("SERIAL", null);
        long workOrderId = newWorkOrder(productId, new BigDecimal("3"), "RELEASED", "SERIAL");
        assertEquals("SERIAL", workTraceMode(workOrderId));

        // 产品改成按数量
        masterData.updateProduct(productId, new MasterDataService.ProductRequest(
                jdbcTemplate.queryForObject("select code from product where id = ?", String.class, productId),
                "追溯方式测试件", "TM-T", "FINISHED", "件",
                new BigDecimal("10"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, "NONE"));
        assertEquals("NONE", String.valueOf(productRow(productId).get("traceMode")));
        assertEquals("SERIAL", workTraceMode(workOrderId), "在制工单应保持建单时的快照");

        // 件码仍然按工单口径生成
        assertEquals(3, units.generateForWorkOrder(workOrderId));
        Integer generated = jdbcTemplate.queryForObject(
                "select count(*) from work_order_unit where work_order_id = ?", Integer.class, workOrderId);
        assertEquals(3, generated);

        // 按数量的工单不生成件码
        long noneOrder = newWorkOrder(productId, new BigDecimal("2"), "RELEASED", "NONE");
        assertEquals(0, units.generateForWorkOrder(noneOrder));
    }

    @Test
    @DisplayName("改追溯方式：下达前可以，下达后锁住；单件追溯要求整数数量")
    void traceModeChangeRules() {
        long productId = newProduct("NONE", null);
        long draft = newWorkOrder(productId, new BigDecimal("4"), "DRAFT", "NONE");

        Map<String, Object> changed = production.setTraceMode(draft, "SERIAL");
        assertEquals("SERIAL", changed.get("traceMode"));
        assertEquals("SERIAL", workTraceMode(draft));

        // 小数数量做不了单件追溯
        long decimalOrder = newWorkOrder(productId, new BigDecimal("2.5"), "SCHEDULED", "NONE");
        assertThrows(BizException.class, () -> production.setTraceMode(decimalOrder, "SERIAL"));

        // 下达后锁住
        long released = newWorkOrder(productId, new BigDecimal("4"), "RELEASED", "NONE");
        assertThrows(BizException.class, () -> production.setTraceMode(released, "SERIAL"));

        // 非法值
        assertThrows(BizException.class, () -> production.setTraceMode(draft, "BY_CUSTOMER"));
    }

    @Test
    @DisplayName("工单详情带出追溯方式，列表口径一致")
    void detailExposesTraceMode() {
        long productId = newProduct("SERIAL", null);
        long workOrderId = newWorkOrder(productId, new BigDecimal("2"), "DRAFT", "SERIAL");
        Map<String, Object> detail = production.detail(workOrderId);
        assertEquals("SERIAL", String.valueOf(detail.get("traceMode")));
        assertTrue(detail.containsKey("serialManaged"), "老字段保留给前端兼容");
    }

    @Test
    @DisplayName("内置操作工角色：只有干活需要的权限，看不到成本与绩效")
    void operatorRolePermissions() {
        List<String> permissions = jdbcTemplate.queryForList("""
                select rp.permission from sys_role r
                join sys_role_permission rp on rp.role_id = r.id
                where r.code = 'OPERATOR' order by rp.permission
                """, String.class);
        assertTrue(permissions.contains("production"), "操作工要能看工单");
        assertTrue(permissions.contains("production:report"), "操作工要能报工");
        assertTrue(!permissions.contains("cost"), "操作工不该看到成本");
        assertTrue(!permissions.contains("perf"), "操作工不该看到绩效");
        assertTrue(!permissions.contains("production:release"), "操作工不该能下达工单");
        assertTrue(!permissions.contains("production:outsource"), "操作工不该能发外协");

        List<String> workers = jdbcTemplate.queryForList(
                "select username from sys_user where username in ('worker1','worker2') and role = 'OPERATOR'",
                String.class);
        assertEquals(2, workers.size(), "演示操作工账号应挂到操作工角色");
    }
}
