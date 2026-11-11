package com.mes.platform.module;

import java.util.List;
import java.util.Set;

/**
 * 模块目录：把「这个系统由哪些业务模块组成、每个模块拥有哪些表」写成代码。
 *
 * <p>作用有三个：</p>
 * <ol>
 *   <li>给架构守卫测试用：判断某个包名是不是业务模块，从而执行「跨模块只能依赖对方 api 包」的规则；</li>
 *   <li>给拆分做参照：表归属决定了将来能不能独立一个库，一个模块的表如果散落在别人的 SQL 里，就拆不走；</li>
 *   <li>给新人看：不用点开 30 个目录猜边界。</li>
 * </ol>
 *
 * <p>表归属目前只覆盖主要业务表；{@code sys_*}、{@code act_*}、{@code flyway_*} 属于平台层，所有模块都可访问。</p>
 */
public final class ModuleCatalog {

    /** 一个业务模块：包名、中文名、拥有的表、依赖的模块 */
    public record Module(String name, String displayName, Set<String> tables, Set<String> dependsOn) {
    }

    /** 平台层包：所有模块都可以直接依赖 */
    public static final Set<String> PLATFORM_PACKAGES = Set.of("common", "platform", "security");

    /**
     * 平台层表：所有模块都可以访问。
     *
     * <p>{@code code_seq} 放在这里：它是「按前缀取下一个业务单号」的序号表，
     * 由 {@code com.mes.common.CodeGenerator} 统一读写，30 多个模块都在用。
     * 它不属于库存（虽然历史上是跟着库存建的表），登记成平台表才符合实际归属。</p>
     */
    public static final Set<String> PLATFORM_TABLES = Set.of("sys_config", "oper_log", "code_seq");

    private static final List<Module> MODULES = List.of(
            new Module("sales", "销售订单",
                    Set.of("sales_order", "sales_order_item",
                            "sales_quote", "sales_quote_item", "sales_quote_review"),
                    Set.of("masterdata", "inventory", "production", "notice")),
            new Module("production", "生产工单",
                    Set.of("work_order", "work_order_operation", "work_order_bom", "work_order_material_batch",
                            "work_order_unit", "work_order_cost", "operation_report", "production_issue",
                            "production_issue_item", "production_return", "production_return_item",
                            "work_order_change", "work_order_unit_log",
                            "work_order_dependency",
                            "outsource_order", "outsource_order_item"),
                    Set.of("masterdata", "inventory", "process", "org", "notice", "cost", "andon", "system",
                            "quality")),
            new Module("planning", "排产计划",
                    Set.of("schedule_plan", "mrp_alert_log", "sales_stock_reservation", "bom_task"),
                    Set.of("production", "process", "org", "masterdata", "purchase", "sales", "notice", "system")),
            new Module("workshop", "车间看板",
                    Set.of(), Set.of("production", "inventory", "andon", "device")),
            new Module("inventory", "库存与流水",
                    Set.of("inventory", "inventory_batch", "stock_txn"),
                    Set.of("masterdata", "system", "production", "warehouse")),
            new Module("warehouse", "仓储单据",
                    Set.of("inbound_order", "inbound_order_item", "outbound_order", "outbound_order_item",
                            "transfer_order", "transfer_order_item", "stocktake", "stocktake_item",
                            "warehouse_location", "stock_move"),
                    Set.of("inventory", "masterdata", "approval")),
            new Module("purchase", "采购与应付",
                    Set.of("purchase_requisition", "purchase_requisition_item", "purchase_order",
                            "purchase_order_item", "purchase_return", "purchase_return_item",
                            "supplier", "supplier_price", "rfq", "rfq_quote",
                            "ap_accrual", "ap_accrual_item", "ap_invoice", "ap_invoice_item",
                            "ap_invoice_match", "ap_payment", "ap_refund"),
                    Set.of("inventory", "warehouse", "masterdata", "approval", "notice")),
            new Module("quality", "质量检验",
                    Set.of("inspection", "inspection_item", "inspection_item_def", "sip", "sop",
                            "defect", "defect_disposal", "sampling_plan"),
                    Set.of("production", "inventory", "masterdata", "notice")),
            new Module("returns", "退货与返修",
                    Set.of("sales_return", "sales_return_item"),
                    Set.of("production", "inventory", "quality", "notice", "sales", "masterdata")),
            new Module("shipping", "发货与签收",
                    Set.of("shipment", "shipment_item"),
                    Set.of("inventory", "masterdata", "notice", "sales")),
            new Module("masterdata", "基础数据",
                    Set.of("product", "bom", "bom_item", "routing", "routing_operation",
                            "customer", "warehouse", "process_def", "code_rule"),
                    Set.of("purchase")),
            new Module("process", "工序主数据", Set.of("process", "process_skill"),
                    Set.of("org", "production")),
            new Module("org", "组织与产能",
                    Set.of("sys_org", "work_center", "device"), Set.of("masterdata", "process")),
            new Module("device", "设备与点检",
                    Set.of("device_check_item", "device_check_task", "device_maintain_plan",
                            "device_maintain_log", "device_repair", "device_telemetry"),
                    Set.of("org")),
            // approval 与 cost 互为依赖：绩效要提交审批（cost → approval），审批通过要回写绩效状态
            // （approval → cost）。回调那一头走 cost.api.PerformanceApprovalPort + 只带 JdbcTemplate 的
            // PerformanceApprovalWriter，所以构造上没有环，这里如实记下业务上的双向关系
            new Module("approval", "审批流",
                    Set.of("sys_approval_flow", "sys_approval_instance", "sys_approval_task",
                            "sys_approval_log", "sys_approval_extra_task", "sys_approval_field_rule",
                            "sys_approval_signature", "sys_approval_node_policy"),
                    Set.of("notice", "org", "warehouse", "cost")),
            new Module("notice", "站内通知", Set.of("sys_notice", "sys_push_device"), Set.of("push")),
            new Module("cost", "成本核算",
                    Set.of("work_order_cost_snapshot", "performance_sheet", "work_hour"),
                    Set.of("production", "inventory", "masterdata", "approval")),
            new Module("finance", "财务",
                    Set.of("fin_account", "fin_period", "fin_exchange_rate",
                            "fin_voucher", "fin_voucher_line",
                            "ar_invoice", "ar_invoice_item", "ar_receipt", "ar_receipt_apply",
                            "fa_asset", "fa_depreciation", "tax_filing",
                            "tax_income_settlement", "tax_adjustment", "tax_prepayment",
                            "tax_adjustment_rule", "tax_adjustment_ledger",
                            "tax_branch", "tax_branch_allocation",
                            "rd_project", "rd_expense"),
                    Set.of("sales", "shipping", "purchase", "masterdata")),
            new Module("report", "报表", Set.of(), Set.of("production", "inventory", "purchase", "sales")),
            new Module("dashboard", "工作台", Set.of(), Set.of("production", "inventory", "sales", "quality")),
            new Module("trace", "批次追溯", Set.of(), Set.of("inventory", "production", "quality")),
            new Module("andon", "异常安灯", Set.of("andon_call"),
                    Set.of("production", "notice", "inventory", "masterdata")),
            new Module("system", "系统管理",
                    Set.of("sys_user", "sys_role", "sys_role_permission", "sys_code_rule",
                            "sys_user_view", "sys_job_log", "sys_job_lock", "sys_domain_event",
                            "sys_file"),
                    Set.of("org", "auth")),
            new Module("auth", "登录与令牌", Set.of("sys_refresh_token"), Set.of()),
            new Module("file", "附件", Set.of("sys_file"), Set.of()),
            new Module("integration", "对外接口", Set.of("sys_open_token"), Set.of("production", "inventory", "cost")),
            new Module("offline", "离线重传", Set.of("sys_offline_idempotent"), Set.of()),
            new Module("push", "推送通道", Set.of("sys_push_device"), Set.of()),
            new Module("job", "定时任务", Set.of("sys_job_log", "sys_job_lock"),
                    Set.of("approval", "cost", "device", "notice", "offline",
                            "sales", "production", "inventory", "masterdata"))
    );

    private ModuleCatalog() {
    }

    public static List<Module> modules() {
        return MODULES;
    }

    /** 是不是一个业务模块（平台层不算） */
    public static boolean isModule(String packageName) {
        return MODULES.stream().anyMatch(module -> module.name().equals(packageName));
    }

    /** 某个模块拥有的表；返回 null 表示不在目录里（不做归属校验） */
    public static Set<String> tablesOf(String moduleName) {
        return MODULES.stream()
                .filter(module -> module.name().equals(moduleName))
                .map(Module::tables)
                .findFirst().orElse(null);
    }

    /** 这张表归哪个模块所有；返回 null 表示平台表或未登记 */
    public static String ownerOfTable(String table) {
        if (table == null || table.isBlank()) {
            return null;
        }
        String name = table.toLowerCase();
        if (PLATFORM_TABLES.contains(name)) {
            return null;
        }
        if (name.startsWith("sys_") || name.startsWith("act_") || name.startsWith("flyway_")) {
            return null;
        }
        return MODULES.stream()
                .filter(module -> module.tables().contains(name))
                .map(Module::name)
                .findFirst().orElse(null);
    }
}
