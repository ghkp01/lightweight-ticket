package com.mes.platform.scope;

import com.mes.inventory.InventoryService;
import com.mes.masterdata.MasterDataService;
import com.mes.org.OrgService;
import com.mes.purchase.PurchaseOrderService;
import com.mes.purchase.RequisitionService;
import com.mes.purchase.api.RequisitionDraft;
import com.mes.purchase.api.RequisitionLine;
import com.mes.warehouse.InboundOrderService;
import com.mes.warehouse.TransferService;
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
 * 多工厂隔离覆盖全链路：仓库归属工厂，库存、采购、申请、出入库沿仓库或工单归属过滤。
 * 开关关闭时任何列表都不加条件，单工厂部署不受影响。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrgScopeCoverageTest {

    @Autowired
    private OrgScopeService orgScope;

    @Autowired
    private OrgService orgService;

    @Autowired
    private MasterDataService masterDataService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private RequisitionService requisitionService;

    @Autowired
    private InboundOrderService inboundOrderService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private String factoryUser;
    private long plant1;
    private long plant2;
    private long warehouse1;
    private long warehouse2;
    private long supplierId;

    private void login(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void switchIsolation(boolean enabled) {
        jdbc.update("update sys_config set config_value = ? where config_key = 'org.isolation.enabled'",
                String.valueOf(enabled));
    }

    @BeforeEach
    void setUp() {
        tag = Long.toString(System.nanoTime());
        plant1 = jdbc.queryForObject(
                "select id from sys_org where type = 'PLANT' order by id limit 1", Long.class);
        plant2 = orgService.create(new OrgService.OrgRequest(
                "P2" + tag, "二号工厂" + tag, "PLANT", null, 1, null, null, true, null));
        factoryUser = "f2" + tag;
        jdbc.update("""
                insert into sys_user (username, password, real_name, role, org_id, enabled)
                values (?, 'x', '二号厂计划', 'PLANNER', ?, true)
                """, factoryUser, plant2);
        supplierId = jdbc.queryForObject("select id from supplier order by id limit 1", Long.class);

        login("admin", "ADMIN");
        switchIsolation(true);
        warehouse1 = masterDataService.createWarehouse(new MasterDataService.WarehouseRequest(
                "W1" + tag, "一号仓" + tag, "RAW", plant1));
        // 管理员替二号厂建仓：显式指定工厂
        warehouse2 = masterDataService.createWarehouse(new MasterDataService.WarehouseRequest(
                "W2" + tag, "二号仓" + tag, "RAW", plant2));
        jdbc.update("insert into inventory (product_id, warehouse_id, qty, locked_qty) values (1, ?, 5, 0)",
                warehouse1);
        jdbc.update("insert into inventory (product_id, warehouse_id, qty, locked_qty) values (2, ?, 5, 0)",
                warehouse2);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private boolean hasWarehouse(List<Map<String, Object>> rows, long warehouseId) {
        return rows.stream().anyMatch(row -> row.get("warehouseId") != null
                && ((Number) row.get("warehouseId")).longValue() == warehouseId);
    }

    /** PageResult.records() 是通配符列表，测试里统一转成 Map 列表再用 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(List<?> rows) {
        return (List<Map<String, Object>>) rows;
    }

    @Test
    @DisplayName("出入库与调拨按单据上的仓库工厂隔离，并补上仓库名")
    void warehouseDocumentsScoped() {
        jdbc.update("insert into inbound_order (inbound_no, warehouse_id) values (?, ?), (?, ?)",
                "IN1" + tag, warehouse1, "IN2" + tag, warehouse2);
        jdbc.update("""
                insert into transfer_order (transfer_no, from_warehouse_id, to_warehouse_id)
                values (?, ?, ?), (?, ?, ?)
                """, "TR1" + tag, warehouse1, warehouse1, "TR2" + tag, warehouse2, warehouse2);

        login(factoryUser, "PLANNER");
        List<Map<String, Object>> inbounds = rowsOf(
                inboundOrderService.list(null, null, null, 1, 200).records());
        assertTrue(hasWarehouse(inbounds, warehouse2), "本厂入库单应可见");
        assertFalse(hasWarehouse(inbounds, warehouse1), "别厂入库单不该出现");
        assertTrue(inbounds.stream().filter(row -> row.get("warehouseId") != null)
                        .allMatch(row -> row.get("warehouseName") != null),
                "仓库名要在不 join 的情况下补齐");

        List<Map<String, Object>> transfers = rowsOf(transferService.list(null, null, 1, 200).records());
        assertTrue(transfers.stream().anyMatch(row -> ("TR2" + tag).equals(row.get("transferNo"))),
                "本厂调拨应可见");
        assertFalse(transfers.stream().anyMatch(row -> ("TR1" + tag).equals(row.get("transferNo"))),
                "别厂调拨不该出现");
        assertTrue(transfers.stream().filter(row -> ("TR2" + tag).equals(row.get("transferNo")))
                        .allMatch(row -> row.get("fromWarehouseName") != null
                                && row.get("toWarehouseName") != null),
                "调出/调入仓库名都要补齐");
    }

    @Test
    @DisplayName("仓库按工厂隔离：每个厂只看到自己的仓")
    void warehouseScoped() {
        login("admin", "ADMIN");
        List<Map<String, Object>> firstFactory = masterDataService.warehouses();
        assertTrue(firstFactory.stream().anyMatch(row -> ((Number) row.get("id")).longValue() == warehouse1),
                "本厂仓库应可见");
        assertFalse(firstFactory.stream().anyMatch(row -> ((Number) row.get("id")).longValue() == warehouse2),
                "别厂仓库不该出现");

        login(factoryUser, "PLANNER");
        List<Map<String, Object>> scoped = masterDataService.warehouses();
        assertTrue(scoped.stream().anyMatch(row -> ((Number) row.get("id")).longValue() == warehouse2),
                "本厂仓库应可见");
        assertFalse(scoped.stream().anyMatch(row -> ((Number) row.get("id")).longValue() == warehouse1),
                "别厂仓库不该出现");

        // 开关关闭后恢复全量
        switchIsolation(false);
        assertEquals(true, masterDataService.warehouses().stream()
                .anyMatch(row -> ((Number) row.get("id")).longValue() == warehouse1));
        assertEquals(true, masterDataService.warehouses().stream()
                .anyMatch(row -> ((Number) row.get("id")).longValue() == warehouse2));
    }

    @Test
    @DisplayName("库存按仓库工厂过滤，流水同步过滤")
    void inventoryScoped() {
        jdbc.update("""
                insert into stock_txn (txn_no, txn_type, product_id, warehouse_id, qty,
                                       unit_cost, amount, operator)
                values (?, 'IN', 1, ?, 1, 1, 1, 'test'), (?, 'IN', 2, ?, 1, 1, 1, 'test')
                """, "T1" + tag, warehouse1, "T2" + tag, warehouse2);

        login(factoryUser, "PLANNER");
        List<Map<String, Object>> rows = inventoryService.list("", null, false);
        assertTrue(hasWarehouse(rows, warehouse2), "本厂库存应可见");
        assertFalse(hasWarehouse(rows, warehouse1), "别厂库存不该出现");

        List<String> warehouseNames = inventoryService.txns(null, null, 1, 200).records().stream()
                .map(row -> String.valueOf(((Map<?, ?>) row).get("warehouseName")))
                .toList();
        assertTrue(warehouseNames.contains("二号仓" + tag), "本厂流水应可见");
        assertFalse(warehouseNames.contains("一号仓" + tag), "别厂流水不该出现");
    }

    @Test
    @DisplayName("采购单跟着收货仓库的工厂走，采购申请跟随工单或当前工厂")
    void purchaseScoped() {
        login("admin", "ADMIN");
        long po1 = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, warehouse1, LocalDate.now(), LocalDate.now().plusDays(7), false,
                "一号厂采购", List.of(new PurchaseOrderService.ItemRequest(
                        1L, BigDecimal.ONE, new BigDecimal("10"), null))));
        long po2 = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, warehouse2, LocalDate.now(), LocalDate.now().plusDays(7), false,
                "二号厂采购", List.of(new PurchaseOrderService.ItemRequest(
                        2L, BigDecimal.ONE, new BigDecimal("10"), null))));
        long req1 = requisitionService.create(new RequisitionDraft(
                "MANUAL", "计划部", null, LocalDate.now().plusDays(3), "一号厂申请", null,
                List.of(new RequisitionLine(1L, BigDecimal.ONE, null, null, false, null)))).id();

        login(factoryUser, "PLANNER");
        long req2 = requisitionService.create(new RequisitionDraft(
                "MANUAL", "计划部", null, LocalDate.now().plusDays(3), "二号厂申请", null,
                List.of(new RequisitionLine(2L, BigDecimal.ONE, null, null, false, null)))).id();

        List<Long> orderIds = purchaseOrderService.list(null, null, null, 1, 50).records().stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue()).toList();
        assertTrue(orderIds.contains(po2), "本厂采购单应可见");
        assertFalse(orderIds.contains(po1), "别厂采购单不该出现");

        List<Long> reqIds = requisitionService.list(null, null, null, 1, 50).records().stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue()).toList();
        assertTrue(reqIds.contains(req2), "本厂申请应可见");
        assertFalse(reqIds.contains(req1), "别厂申请不该出现");

        // 采购申请记的工厂：来源工单为空时取当前账号所属工厂
        Long reqFactory = jdbc.queryForObject(
                "select factory_org_id from purchase_requisition where id = ?", Long.class, req2);
        assertEquals(plant2, reqFactory);
    }

    @Test
    @DisplayName("开关关闭后不再过滤，恢复单工厂视图")
    void disabledShowsEverything() {
        login("admin", "ADMIN");
        long po1 = purchaseOrderService.create(new PurchaseOrderService.OrderRequest(
                supplierId, warehouse1, LocalDate.now(), LocalDate.now().plusDays(7), false,
                "关闭开关采购", List.of(new PurchaseOrderService.ItemRequest(
                        1L, BigDecimal.ONE, new BigDecimal("10"), null))));

        switchIsolation(false);
        login(factoryUser, "PLANNER");
        List<Long> orderIds = purchaseOrderService.list(null, null, null, 1, 50).records().stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue()).toList();
        assertTrue(orderIds.contains(po1), "开关关闭时应看到全部采购单");
        assertTrue(masterDataService.warehouses().size() >= 2);
    }

    @Test
    @DisplayName("inScope：本厂与无归属放行，别厂拦截")
    void inScopeHelper() {
        login(factoryUser, "PLANNER");
        assertTrue(orgScope.inScope(plant2));
        assertTrue(orgScope.inScope(null), "历史无归属数据应放行");
        assertFalse(orgScope.inScope(plant1));
        assertEquals(plant2, orgScope.writeFactoryOrgId());
    }
}
