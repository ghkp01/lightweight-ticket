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
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 没有工艺路线时的补救路径：建单不再一刀切拒绝，工单先落成空工序，
 * 计划在工单里逐道手工添加，加完才允许下达。
 *
 * <p>这条路径是为「上线初期主数据没录全」准备的：先开工、后补路线，
 * 但不能让车间拿到一张没有工序的空单。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManualOperationTest {

    @Autowired
    private ProductionService production;

    @Autowired
    private MasterDataService masterData;

    @Autowired
    private JdbcTemplate jdbc;

    private long newProductWithoutRouting() {
        return masterData.createProduct(new MasterDataService.ProductRequest(
                "MO" + ThreadLocalRandom.current().nextInt(100000, 999999),
                "无路线测试件", "MO-T", "FINISHED", "件",
                new BigDecimal("10"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, "NONE", null));
    }

    private long anyProcessId() {
        return jdbc.queryForObject("select id from process where enabled = true order by id limit 1", Long.class);
    }

    private int operationCount(long workOrderId) {
        return jdbc.queryForObject("select count(*) from work_order_operation where work_order_id = ?",
                Integer.class, workOrderId);
    }

    private String statusOf(long workOrderId) {
        return jdbc.queryForObject("select status from work_order where id = ?", String.class, workOrderId);
    }

    @Test
    @DisplayName("建单可带优先级、追溯方式与备注，不传就按产品默认与普通优先级")
    void createCarriesPriorityTraceModeAndRemark() {
        long productId = newProductWithoutRouting();
        long workId = production.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("5"), null, null, "URGENT", "LOT", "试制单，先做 5 件"));

        Map<String, Object> detail = production.detail(workId);
        assertEquals("URGENT", detail.get("priority"));
        assertEquals("LOT", detail.get("traceMode"));
        assertEquals("试制单，先做 5 件", detail.get("remark"));

        long plain = production.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("1"), null, null));
        Map<String, Object> plainDetail = production.detail(plain);
        assertEquals("NORMAL", plainDetail.get("priority"));
        assertEquals("NONE", plainDetail.get("traceMode"), "不传追溯方式时跟随产品默认值");
        assertEquals(null, plainDetail.get("remark"));
    }

    @Test
    @DisplayName("建单字段校验：优先级非法、单件追溯配小数数量都要拒")
    void createRejectsBadFields() {
        long productId = newProductWithoutRouting();

        BizException badPriority = assertThrows(BizException.class,
                () -> production.create(new ProductionService.WorkOrderRequest(
                        productId, BigDecimal.ONE, null, null, "ASAP", null, null)));
        assertTrue(badPriority.getMessage().contains("优先级"), badPriority.getMessage());

        BizException badTrace = assertThrows(BizException.class,
                () -> production.create(new ProductionService.WorkOrderRequest(
                        productId, BigDecimal.ONE, null, null, null, "BATCH", null)));
        assertTrue(badTrace.getMessage().contains("追溯方式"), badTrace.getMessage());

        BizException decimalQty = assertThrows(BizException.class,
                () -> production.create(new ProductionService.WorkOrderRequest(
                        productId, new BigDecimal("1.5"), null, null, null, "SERIAL", null)));
        assertTrue(decimalQty.getMessage().contains("整数"), decimalQty.getMessage());
    }

    @Test
    @DisplayName("没有工艺路线也能建单：工序为空，下达被拦，手工加完工序后可下达")
    void createWithoutRoutingThenAddManually() {
        long productId = newProductWithoutRouting();
        long workId = production.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("3"), null, null));

        assertEquals(0, operationCount(workId), "没有工艺路线时不该退回硬编码工序");

        // 排产到已排产状态，才轮到下达这一步
        jdbc.update("update work_order set status = 'SCHEDULED' where id = ?", workId);
        BizException blocked = assertThrows(BizException.class, () -> production.release(workId));
        assertTrue(blocked.getMessage().contains("还没有工序"), "实际提示：" + blocked.getMessage());
        assertEquals("SCHEDULED", statusOf(workId), "下达被拦后状态不能变");

        long processId = anyProcessId();
        Map<String, Object> first = production.addOperation(workId,
                new ProductionService.AddOperationRequest(processId, null, null, null, null, null));
        assertEquals(1, ((Number) first.get("seq")).intValue());
        assertEquals(1, operationCount(workId));

        Map<String, Object> second = production.addOperation(workId,
                new ProductionService.AddOperationRequest(processId, null, new BigDecimal("2"),
                        new BigDecimal("8"), new BigDecimal("1.5"), true));
        assertEquals(2, ((Number) second.get("seq")).intValue(), "序号应顺延，不覆盖已有工序");
        assertEquals(2, operationCount(workId));

        production.release(workId);
        assertEquals("RELEASED", statusOf(workId));
    }

    @Test
    @DisplayName("计划数量默认取工单计划量，标准工时应按它推算")
    void planQtyFallsBackToWorkOrderPlanQty() {
        long productId = newProductWithoutRouting();
        long workId = production.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("4"), null, null));

        production.addOperation(workId, new ProductionService.AddOperationRequest(
                anyProcessId(), null, null, new BigDecimal("2.5"), null, null));

        BigDecimal standard = jdbc.queryForObject(
                "select standard_minutes from work_order_operation where work_order_id = ?",
                BigDecimal.class, workId);
        assertEquals(0, standard.compareTo(new BigDecimal("10.0")), "4 件 × 2.5 分钟 = 10 分钟");
    }

    @Test
    @DisplayName("删除工序：只放行未下达的工单，下达后不能再删")
    void deleteOperationGuards() {
        long productId = newProductWithoutRouting();
        long workId = production.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("1"), null, null));
        production.addOperation(workId, new ProductionService.AddOperationRequest(
                anyProcessId(), null, null, null, null, null));

        production.deleteOperation(workId, 1);
        assertEquals(0, operationCount(workId));

        BizException missing = assertThrows(BizException.class, () -> production.deleteOperation(workId, 9));
        assertTrue(missing.getMessage().contains("工序不存在"), "实际提示：" + missing.getMessage());

        // 重新加一道再下达，下达后应禁止删除
        production.addOperation(workId, new ProductionService.AddOperationRequest(
                anyProcessId(), null, null, null, null, null));
        jdbc.update("update work_order set status = 'SCHEDULED' where id = ?", workId);
        production.release(workId);
        BizException released = assertThrows(BizException.class, () -> production.deleteOperation(workId, 1));
        assertTrue(released.getMessage().contains("未下达"), "实际提示：" + released.getMessage());
    }

    @Test
    @DisplayName("加工序的入参校验：工序不存在、没有工序也没名称、数量非法都要报错")
    void addOperationValidation() {
        long productId = newProductWithoutRouting();
        long workId = production.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("1"), null, null));

        BizException noTarget = assertThrows(BizException.class, () -> production.addOperation(workId,
                new ProductionService.AddOperationRequest(null, "  ", null, null, null, null)));
        assertTrue(noTarget.getMessage().contains("请选择工序"), "实际提示：" + noTarget.getMessage());

        BizException unknown = assertThrows(BizException.class, () -> production.addOperation(workId,
                new ProductionService.AddOperationRequest(-1L, null, null, null, null, null)));
        assertTrue(unknown.getMessage().contains("工序不存在"), "实际提示：" + unknown.getMessage());

        BizException badQty = assertThrows(BizException.class, () -> production.addOperation(workId,
                new ProductionService.AddOperationRequest(anyProcessId(), null, BigDecimal.ZERO, null, null, null)));
        assertTrue(badQty.getMessage().contains("必须大于 0"), "实际提示：" + badQty.getMessage());

        assertEquals(0, operationCount(workId), "校验失败不能留下半条工序");
    }

    @Test
    @DisplayName("已完工的工单不能再加工序")
    void cannotAddToFinishedWorkOrder() {
        long productId = newProductWithoutRouting();
        long workId = production.create(new ProductionService.WorkOrderRequest(
                productId, new BigDecimal("1"), null, null));
        jdbc.update("update work_order set status = 'FINISHED' where id = ?", workId);

        BizException ex = assertThrows(BizException.class, () -> production.addOperation(workId,
                new ProductionService.AddOperationRequest(anyProcessId(), null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("不能再加工序"), "实际提示：" + ex.getMessage());
    }
}
