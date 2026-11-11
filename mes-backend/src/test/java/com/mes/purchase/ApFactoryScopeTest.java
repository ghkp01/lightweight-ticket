package com.mes.purchase;

import com.mes.masterdata.MasterDataService;
import com.mes.org.OrgService;
import com.mes.platform.scope.OrgScopeService;
import com.mes.warehouse.InboundOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应付与发票的多工厂隔离：暂估随入库仓库、发票随采购单仓库、红字发票继承蓝票；
 * 概览、账龄、对账与可开票行都按同一工厂收口。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApFactoryScopeTest {

    @Autowired
    private ApService apService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private InboundOrderService inboundOrderService;

    @Autowired
    private MasterDataService masterDataService;

    @Autowired
    private OrgService orgService;

    @Autowired
    private OrgScopeService orgScope;

    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private String factoryUser;
    private long plant1;
    private long plant2;
    private long supplierId;
    private long accrual1;
    private long accrual2;

    private void login(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void receiveFully(long orderId) {
        purchaseOrderService.confirm(orderId);
        Map<String, Object> receipt = purchaseOrderService.receive(orderId,
                new PurchaseOrderService.ReceiveRequest(List.of(), false, "多工厂应付测试"));
        long inboundId = ((Number) receipt.get("inboundId")).longValue();
        inboundOrderService.submit(inboundId);
    }

    @BeforeEach
    void setUp() {
        tag = Long.toString(System.nanoTime());
        login("admin", "ADMIN");
        jdbc.update("update sys_config set config_value = 'true' where config_key = 'org.isolation.enabled'");

        plant1 = jdbc.queryForObject("select id from sys_org where type = 'PLANT' order by id limit 1", Long.class);
        plant2 = orgService.create(new OrgService.OrgRequest(
                "P2" + tag, "二号工厂" + tag, "PLANT", null, 1, null, null, true, null));
        factoryUser = "f2" + tag;
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, org_id, enabled)
                values (?, 'x', '二号厂计划', 'PLANNER', ?, true)
                """, factoryUser, plant2);
        supplierId = jdbc.queryForObject("select id from supplier order by id limit 1", Long.class);

        long w1 = masterDataService.createWarehouse(new MasterDataService.WarehouseRequest(
                "A1" + tag, "应付一号仓" + tag, "RAW", plant1));
        long w2 = masterDataService.createWarehouse(new MasterDataService.WarehouseRequest(
                "A2" + tag, "应付二号仓" + tag, "RAW", plant2));

        long po1 = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, w1, LocalDate.now(), LocalDate.now().plusDays(7), false, "一号厂收货",
                List.of(new PurchaseOrderService.ItemRequest(1L, BigDecimal.ONE, new BigDecimal("10"), null))));
        long po2 = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, w2, LocalDate.now(), LocalDate.now().plusDays(7), false, "二号厂收货",
                List.of(new PurchaseOrderService.ItemRequest(2L, BigDecimal.ONE, new BigDecimal("20"), null))));
        receiveFully(po1);
        receiveFully(po2);

        accrual1 = jdbc.queryForObject("select id from ap_accrual where purchase_order_id = ?",
                Long.class, po1);
        accrual2 = jdbc.queryForObject("select id from ap_accrual where purchase_order_id = ?",
                Long.class, po2);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("暂估随入库仓库的工厂落库")
    void accrualInheritsWarehouseFactory() {
        assertEquals(plant1, jdbc.queryForObject(
                "select factory_org_id from ap_accrual where id = ?", Long.class, accrual1));
        assertEquals(plant2, jdbc.queryForObject(
                "select factory_org_id from ap_accrual where id = ?", Long.class, accrual2));
    }

    @Test
    @DisplayName("二号厂只看到自己的暂估，概览与对账同口径")
    void accrualScoped() {
        login(factoryUser, "PLANNER");
        List<Long> ids = apService.accruals(null, null, null, 1, 50).records().stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue()).toList();
        assertTrue(ids.contains(accrual2), "本厂暂估应可见");
        assertFalse(ids.contains(accrual1), "别厂暂估不该出现");

        BigDecimal open = (BigDecimal) apService.summary().get("accrualOpen");
        assertEquals(0, new BigDecimal("20.00").compareTo(open), "概览只算本厂暂估 " + open);

        List<Map<String, Object>> recon = (List<Map<String, Object>>) apService.reconciliation(null).get("records");
        assertEquals(1, recon.size(), "对账只列本厂入库");
    }

    @Test
    @DisplayName("发票随采购单仓库落库，红字发票继承蓝票工厂")
    void invoiceInheritsPurchaseOrderFactory() {
        login(factoryUser, "PLANNER");
        List<Map<String, Object>> lines = apService.invoiceableLines(supplierId);
        assertFalse(lines.isEmpty(), "本厂应能开票");
        assertTrue(lines.stream().allMatch(row -> ((Number) row.get("productId")).longValue() == 2L),
                "别厂采购行不该出现在可开票行里");

        Map<String, Object> line = lines.get(0);
        String invoiceNo = "INV" + tag;
        long invoiceId = apService.createInvoice(new ApService.InvoiceRequest(
                invoiceNo, "NORMAL", supplierId,
                ((Number) line.get("purchaseOrderId")).longValue(),
                LocalDate.now(), new BigDecimal("13"), "多工厂开票",
                List.of(new ApService.InvoiceItemRequest(
                        ((Number) line.get("purchaseOrderItemId")).longValue(),
                        BigDecimal.ONE, new BigDecimal("20"), null))));
        assertEquals(plant2, jdbc.queryForObject(
                "select factory_org_id from ap_invoice where id = ?", Long.class, invoiceId));

        login(factoryUser, "PLANNER");
        List<Long> ids = apService.invoices(null, null, null, 1, 50).records().stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue()).toList();
        assertTrue(ids.contains(invoiceId), "本厂发票应可见");
        assertEquals(1, ((Number) apService.summary().get("pendingMatch")).intValue(), "概览只数本厂待匹配发票");
        assertTrue(apService.aging().get("buckets") != null);
        assertTrue(apService.creditCandidates(null).isEmpty(), "草稿发票还不是可红冲候选");
    }

    @Test
    @DisplayName("开关关闭后应付恢复全量")
    void disabledShowsEverything() {
        jdbc.update("update sys_config set config_value = 'false' where config_key = 'org.isolation.enabled'");
        login(factoryUser, "PLANNER");
        List<Long> ids = apService.accruals(null, null, null, 1, 50).records().stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue()).toList();
        assertTrue(ids.contains(accrual1) && ids.contains(accrual2), "开关关闭应看到全部暂估");
        assertTrue(orgScope.listFilter() == null);
    }
}
