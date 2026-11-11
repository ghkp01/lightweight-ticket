package com.mes.job;

import com.mes.approval.api.ApprovalPort;
import com.mes.cost.api.CostPort;
import com.mes.device.api.DeviceTelemetryPort;
import com.mes.device.api.MaintenancePort;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.offline.api.OfflineSyncPort;
import com.mes.planning.api.BomTaskReminderPort;
import com.mes.planning.api.MrpAlertPort;
import com.mes.platform.audit.OperLogService;
import com.mes.platform.config.ConfigService;
import com.mes.production.api.RetentionPort;
import com.mes.production.api.WorkOrderPort;
import com.mes.push.api.ReportPushPort;
import com.mes.sales.api.QuotationPort;
import com.mes.sales.api.SalesOrderPort;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台定时任务：交期预警、缺料巡检、成本补算、通知清理。
 * 定时触发与手工触发都走 run()，每次执行写一条 sys_job_log，页面能看最近执行情况。
 * 同一任务不允许并发执行：靠 sys_job_lock 做跨节点互斥，多实例部署也只有一个节点真正执行。
 */
@Service
public class JobService {

    public record JobDef(String code, String name, String cron, String description) {
    }

    private static final List<JobDef> DEFS = List.of(
            new JobDef("DELIVERY_ALERT", "订单交期预警", "每天 08:00",
                    "扫描未完成订单，交期已过或 3 天内到期时提醒销售与计划"),
            new JobDef("SHORTAGE_SCAN", "缺料工单巡检", "每天 08:10",
                    "扫描已下达与在制工单，待领物料库存不够时提醒仓库补料"),
            new JobDef("COST_REFRESH", "未结算成本补算", "每天 01:30",
                    "把还没有成本快照的工单按料工费补算一遍"),
            new JobDef("DEVICE_UPKEEP", "设备点检与保养提醒", "每天 07:00",
                    "按设备生成当天点检任务，并提醒即将到期或已逾期的保养"),
            new JobDef("NOTICE_CLEANUP", "过期通知清理", "每天 02:00",
                    "清理 90 天前的已读消息，未读消息不受影响"),
            new JobDef("OFFLINE_CLEANUP", "离线重传记录清理", "每天 02:20",
                    "清理超过保留天数的移动端重传幂等键，防止表无限增长"),
            new JobDef("APPROVAL_TIMEOUT", "审批超时提醒", "每小时",
                    "扫描配置了超时的审批节点，到期后提醒审批人或按配置自动通过"),
            new JobDef("DOMAIN_EVENT_CLEANUP", "领域事件清理", "每天 03:00",
                    "清理 7 天前已投递的领域事件；投递失败的保留，便于排查与将来重放"),
            new JobDef("TASK_OVERDUE", "逾期任务提醒", "每天 09:00",
                    "把「我的任务」里已过到期日的活按人、按角色汇总提醒一次"),
            new JobDef("QUOTE_BOM_REMINDER", "报价评审与 BOM 任务提醒", "每天 09:15",
                    "评审/BOM 任务到期前提醒处理人，逾期后升级提醒报价人与派单计划员"),
            new JobDef("MRP_ALERT", "物料缺口逾期预警", "每天 08:20",
                    "扫描已过建议下单日仍有缺口的物料，提醒计划尽快生成采购申请"),
            new JobDef("DAILY_REPORT", "经营日报推送", "每天 08:30",
                    "把当天销售额、产量、合格率、待发货与低库存摘要推到飞书/钉钉/企业微信群"),
            new JobDef("RETENTION_CLEANUP", "历史数据保留清理", "每天 03:30",
                    "按保留天数清理设备采集、操作日志、单件流转日志与任务日志，控制表体积")
    );

    /** 交期预警提前天数 */
    private static final int DELIVERY_AHEAD_DAYS = 3;
    /** 单批通知最多覆盖的订单数 / 工单数，避免一次刷屏 */
    private static final int MAX_PER_RUN = 30;

    private final JdbcTemplate jdbcTemplate;
    private final NoticePort notice;
    private final CostPort costService;
    private final MaintenancePort deviceService;
    private final OfflineSyncPort offlineSyncService;
    private final ApprovalPort approval;
    private final InventoryPort inventory;
    private final SalesOrderPort salesOrders;
    private final WorkOrderPort workOrders;
    private final MasterDataPort masterData;
    private final MrpAlertPort mrpAlert;
    private final ReportPushPort reportPush;
    private final DeviceTelemetryPort deviceTelemetry;
    private final RetentionPort productionRetention;
    private final OperLogService operLogService;
    private final ConfigService configService;
    private final QuotationPort quotations;
    private final BomTaskReminderPort bomTaskReminders;
    /** 本进程标识：主机名 + 进程号，任务锁按它区分持有者 */
    private final String owner = ownerOf();

    public JobService(JdbcTemplate jdbcTemplate, NoticePort notice, CostPort costService,
                      MaintenancePort deviceService, OfflineSyncPort offlineSyncService,
                      ApprovalPort approval, InventoryPort inventory,
                      SalesOrderPort salesOrders, WorkOrderPort workOrders,
                      MasterDataPort masterData, MrpAlertPort mrpAlert,
                      ReportPushPort reportPush, DeviceTelemetryPort deviceTelemetry,
                      RetentionPort productionRetention, OperLogService operLogService,
                      ConfigService configService, QuotationPort quotations,
                      BomTaskReminderPort bomTaskReminders) {
        this.quotations = quotations;
        this.bomTaskReminders = bomTaskReminders;
        this.mrpAlert = mrpAlert;
        this.reportPush = reportPush;
        this.deviceTelemetry = deviceTelemetry;
        this.productionRetention = productionRetention;
        this.operLogService = operLogService;
        this.configService = configService;
        this.jdbcTemplate = jdbcTemplate;
        this.notice = notice;
        this.costService = costService;
        this.deviceService = deviceService;
        this.offlineSyncService = offlineSyncService;
        this.approval = approval;
        this.inventory = inventory;
        this.salesOrders = salesOrders;
        this.workOrders = workOrders;
        this.masterData = masterData;
    }

    /** 审批超时：提醒或自动通过，返回处理的待办数 */
    private int approvalTimeout() {
        ApprovalPort.TimeoutScan scan = approval.scanTimeouts();
        return scan.reminded() + scan.autoPassed();
    }

    /** 按配置的保留天数清理历史表，返回删除总条数 */
    private int retentionCleanup() {
        LocalDate today = LocalDate.now();
        int deleted = 0;
        deleted += deviceTelemetry.purgeBefore(today.minusDays(days("retention.telemetry.days", 180)));
        deleted += operLogService.purgeBefore(today.minusDays(days("retention.operLog.days", 365)));
        deleted += productionRetention.purgeUnitLogsBefore(today.minusDays(days("retention.unitLog.days", 730)));
        deleted += jdbcTemplate.update("delete from sys_job_log where started_at::date < ?",
                today.minusDays(days("retention.jobLog.days", 90)));
        return deleted;
    }

    private int days(String key, int fallback) {
        int value = configService.intValue(key, fallback);
        return value <= 0 ? fallback : value;
    }

    /** 任务清单 + 最近一次执行结果 */
    public List<Map<String, Object>> jobs() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JobDef def : DEFS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", def.code());
            row.put("name", def.name());
            row.put("cron", def.cron());
            row.put("description", def.description());
            List<Map<String, Object>> last = jdbcTemplate.queryForList("""
                    select status, trigger_type as "triggerType", affected,
                           to_char(started_at, 'YYYY-MM-DD HH24:MI:SS') as "startedAt",
                           duration_ms as "durationMs", message
                    from sys_job_log where job_code = ? order by id desc limit 1
                    """, def.code());
            if (last.isEmpty()) {
                row.put("status", null);
                row.put("lastRunAt", null);
            } else {
                Map<String, Object> info = last.get(0);
                row.put("status", info.get("status"));
                row.put("lastRunAt", info.get("startedAt"));
                row.put("lastTrigger", info.get("triggerType"));
                row.put("lastAffected", info.get("affected"));
                row.put("lastDurationMs", info.get("durationMs"));
                row.put("lastMessage", info.get("message"));
            }
            row.put("running", isRunning(def.code()));
            result.add(row);
        }
        return result;
    }

    /** 执行记录，code 为空看全部 */
    public List<Map<String, Object>> logs(String code, int limit) {
        int size = limit <= 0 ? 50 : Math.min(limit, 200);
        String where = code == null || code.isBlank() ? "" : " where job_code = ? ";
        Object[] args = code == null || code.isBlank()
                ? new Object[]{size}
                : new Object[]{code, size};
        return jdbcTemplate.queryForList("""
                select id, job_code as "jobCode", job_name as "jobName",
                       trigger_type as "triggerType", status, affected,
                       duration_ms as "durationMs", message, operator,
                       to_char(started_at, 'YYYY-MM-DD HH24:MI:SS') as "startedAt"
                from sys_job_log
                """ + where + " order by id desc limit ?", args);
    }

    /**
     * 执行一个任务。trigger 传 SCHEDULED / MANUAL，用于区分是谁叫起来的。
     * 失败不抛异常，写 FAILED 日志并把原因回传给页面。
     */
    public Map<String, Object> run(String code, String trigger) {
        JobDef def = DEFS.stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new com.mes.common.BizException(1002, "任务不存在：" + code));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", def.code());
        result.put("name", def.name());
        if (!tryAcquire(code)) {
            result.put("status", "SKIPPED");
            result.put("message", "上一次执行还没结束，本次跳过");
            result.put("affected", 0);
            return result;
        }
        long startAt = System.currentTimeMillis();
        String status = "SUCCESS";
        String message = null;
        int affected = 0;
        try {
            affected = switch (code) {
                case "DELIVERY_ALERT" -> deliveryAlert();
                case "SHORTAGE_SCAN" -> shortageScan();
                case "COST_REFRESH" -> costService.recalcAll(null);
                case "DEVICE_UPKEEP" -> deviceService.dailyUpkeep();
                case "NOTICE_CLEANUP" -> notice.purgeRead(90);
                case "OFFLINE_CLEANUP" -> offlineSyncService.cleanup();
                case "APPROVAL_TIMEOUT" -> approvalTimeout();
                case "DOMAIN_EVENT_CLEANUP" -> domainEventCleanup();
                case "TASK_OVERDUE" -> taskOverdue();
                case "QUOTE_BOM_REMINDER" -> quoteBomReminder();
                case "MRP_ALERT" -> mrpAlert.alertOverdueShortages();
                case "DAILY_REPORT" -> reportPush.dailyPush() ? 1 : 0;
                case "RETENTION_CLEANUP" -> retentionCleanup();
                default -> 0;
            };
        } catch (Exception e) {
            status = "FAILED";
            message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            release(code);
        }
        long duration = System.currentTimeMillis() - startAt;
        jdbcTemplate.update("""
                insert into sys_job_log (job_code, job_name, trigger_type, status,
                                         started_at, finished_at, duration_ms, affected, message, operator)
                values (?, ?, ?, ?, now() - (? || ' milliseconds')::interval, now(), ?, ?, ?, ?)
                """, def.code(), def.name(), trigger == null ? "MANUAL" : trigger, status,
                duration, duration, affected, message, CurrentUser.name());
        result.put("status", status);
        result.put("affected", affected);
        result.put("durationMs", duration);
        result.put("message", message);
        return result;
    }

    // ---------------------------------------------------------------- 任务实现

    /** 交期预警：逾期与临近交期的订单各推一条，同一张订单同一天只推一次 */
    private int deliveryAlert() {
        LocalDate today = LocalDate.now();
        List<SalesOrderPort.DeliveryLine> orders =
                salesOrders.pendingDeliveries(today.plusDays(DELIVERY_AHEAD_DAYS), MAX_PER_RUN);
        List<Long> customerIds = orders.stream().map(SalesOrderPort.DeliveryLine::customerId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        var customers = masterData.customers(customerIds);
        int pushed = 0;
        for (SalesOrderPort.DeliveryLine order : orders) {
            String orderNo = order.orderNo();
            // 每张订单每天最多提醒一次，手工重跑也不会重复轰炸
            Integer todayPushed = jdbcTemplate.queryForObject("""
                    select count(*) from sys_notice
                    where ref_type = 'SALES_ORDER' and ref_no = ? and created_at::date = current_date
                    """, Integer.class, orderNo);
            if (todayPushed != null && todayPushed > 0) {
                continue;
            }
            var customer = order.customerId() == null ? null : customers.get(order.customerId());
            String customerName = customer == null ? "-" : customer.name();
            long remainDays = ChronoUnit.DAYS.between(today, order.deliveryDate());
            String title;
            if (remainDays < 0) {
                title = "订单 " + orderNo + " 已逾期 " + (-remainDays) + " 天";
            } else {
                title = "订单 " + orderNo + " 还有 " + remainDays + " 天到期";
            }
            String content = "客户 " + customerName + " 交期 " + order.deliveryDate()
                    + "，订单 " + plain(order.totalQty()) + " 件已发 " + plain(order.shippedQty())
                    + " 件。" + (remainDays < 0
                    ? "请确认剩余数量能否交付，必要时调整排产。"
                    : "请提前确认物料与产能。");
            notice.pushToRoles(List.of("SALES", "PLANNER"), NoticeMessage.of("ALERT", title, content,
                    "SALES_ORDER", orderNo));
            pushed++;
        }
        return pushed;
    }

    /**
     * 任务逾期：按负责人与角色池各汇总一条，不逐条刷屏。
     * 读的是任务模块发布的 rm_biz_task 读模型，不直接碰它的主表。
     */
    private int taskOverdue() {
        int pushed = 0;
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select assignee_username as "username", count(*) as "total"
                from rm_biz_task
                where status = 'OPEN' and assignee_username is not null and due_date < current_date
                group by assignee_username
                order by count(*) desc
                limit ?
                """, MAX_PER_RUN)) {
            notice.push(String.valueOf(row.get("username")), NoticeMessage.of("TASK",
                    "你有 " + plain(new BigDecimal(String.valueOf(row.get("total")))) + " 项任务已逾期",
                    "逾期任务会一直挂在「我的任务」里，请尽快处理；做不了就找管理员改派给别人。"));
            pushed++;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select assignee_role as "role", count(*) as "total"
                from rm_biz_task
                where status = 'OPEN' and assignee_username is null and due_date < current_date
                group by assignee_role
                limit ?
                """, MAX_PER_RUN)) {
            notice.pushToRoles(List.of(String.valueOf(row.get("role"))), NoticeMessage.of("TASK",
                    "角色池里有 " + plain(new BigDecimal(String.valueOf(row.get("total")))) + " 项任务已逾期",
                    "这些活还没有人认领，请到「我的任务」里认领后处理。"));
            pushed++;
        }
        return pushed;
    }

    /** 报价评审与 BOM 任务：到期前提醒处理人，逾期后升级提醒报价人/派单计划员 */
    private int quoteBomReminder() {
        int aheadDays = Math.max(configService.intValue("task.remind.ahead_days", 1), 0);
        int pushed = 0;
        for (QuotationPort.ReviewReminder review : quotations.reviewReminders(aheadDays)) {
            if (review.overdue()) {
                if (review.createdBy() != null) {
                    notice.push(review.createdBy(), NoticeMessage.of("TASK", "报价评审已逾期",
                            "报价单 " + review.quoteNo() + " 评审已超过 " + review.dueDate()
                                    + "，待评审人：" + String.join("、", review.pendingReviewers()),
                            "QUOTE", review.quoteNo()));
                    pushed++;
                }
            } else {
                for (String reviewer : review.pendingReviewers()) {
                    notice.push(reviewer, NoticeMessage.of("TASK", "报价评审即将到期",
                            "报价单 " + review.quoteNo() + " 评审截止 " + review.dueDate(),
                            "QUOTE", review.quoteNo()));
                    pushed++;
                }
            }
        }
        for (BomTaskReminderPort.TaskReminder task : bomTaskReminders.taskReminders(aheadDays)) {
            if (task.overdue()) {
                if (task.assignedBy() != null) {
                    notice.push(task.assignedBy(), NoticeMessage.of("TASK", "BOM 任务已逾期",
                            "订单 " + task.orderNo() + " 的 " + task.productCode()
                                    + " BOM 任务已逾期，技术员：" + task.assigneeUsername()
                                    + "，截止 " + task.dueDate(),
                            "SALES_ORDER", task.orderNo()));
                    pushed++;
                }
            } else if (task.assigneeUsername() != null) {
                notice.push(task.assigneeUsername(), NoticeMessage.of("TASK", "BOM 任务即将到期",
                        "订单 " + task.orderNo() + " 的 " + task.productCode()
                                + " BOM 任务将在 " + task.dueDate() + " 到期",
                        "SALES_ORDER", task.orderNo()));
                pushed++;
            }
        }
        return pushed;
    }

    /** 缺料巡检：把库存不够的已下达/在制工单汇总成一条提醒，避免逐单刷屏 */
    private int shortageScan() {
        // 待领行由生产侧给（含工单号与产品），结存再从库存契约批量取，缺口在本方法比出来
        List<WorkOrderPort.ActiveMaterialNeed> lines = workOrders.activeMaterialNeeds();
        if (lines.isEmpty()) {
            return 0;
        }
        Map<Long, BigDecimal> stocks = inventory.totalStocks(lines.stream()
                .map(WorkOrderPort.ActiveMaterialNeed::materialId).distinct().toList());
        List<Long> productIds = lines.stream().map(WorkOrderPort.ActiveMaterialNeed::productId)
                .distinct().toList();
        Map<Long, ProductInfo> products = masterData.products(productIds);
        Map<Long, Map<String, Object>> byOrder = new LinkedHashMap<>();
        for (WorkOrderPort.ActiveMaterialNeed line : lines) {
            BigDecimal stock = stocks.getOrDefault(line.materialId(), BigDecimal.ZERO);
            if (stock.compareTo(line.needQty()) >= 0) {
                continue;
            }
            Map<String, Object> row = byOrder.computeIfAbsent(line.workOrderId(), key -> {
                ProductInfo product = products.get(line.productId());
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("id", line.workOrderId());
                created.put("workNo", line.workNo());
                created.put("productCode", product == null ? null : product.code());
                created.put("shortLines", 0);
                return created;
            });
            row.put("shortLines", ((Number) row.get("shortLines")).intValue() + 1);
        }
        List<Map<String, Object>> rows = byOrder.values().stream().limit(MAX_PER_RUN).toList();
        if (rows.isEmpty()) {
            return 0;
        }
        String title = rows.size() + " 张工单待领物料不足";
        Integer today = jdbcTemplate.queryForObject("""
                select count(*) from sys_notice where title = ? and created_at::date = current_date
                """, Integer.class, title);
        if (today != null && today > 0) {
            return 0;
        }
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), 8); i++) {
            Map<String, Object> row = rows.get(i);
            texts.add(row.get("workNo") + "（" + row.get("productCode") + "，缺 "
                    + row.get("shortLines") + " 项）");
        }
        String content = "以下工单的待领物料库存不足，请安排采购到货或调整发料："
                + String.join("、", texts)
                + (rows.size() > 8 ? " 等 " + rows.size() + " 张" : "")
                + "。到货入库后系统会自动提醒可以领料。";
        notice.pushToRoles(List.of("WAREHOUSE", "PLANNER"), NoticeMessage.of("ALERT", title, content,
                "WORK_ORDER", String.valueOf(rows.get(0).get("workNo"))));
        return rows.size();
    }

    /** 数量去掉多余小数位，通知文案里别出现 12.0000 这种 */
    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** 领域事件清理：已投递的按保留期删掉，失败的留着排查 */
    private int domainEventCleanup() {
        return jdbcTemplate.update("""
                delete from sys_domain_event
                where status = 'SENT' and created_at < now() - interval '7 days'
                """);
    }

    /** 主机名 + 进程号，作为任务锁的持有者标识 */
    private static String ownerOf() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception error) {
            host = "unknown";
        }
        return host + ":" + ProcessHandle.current().pid();
    }

    /**
     * 抢任务锁：靠 sys_job_lock 主键冲突实现跨节点互斥。
     *
     * <p>以前是进程内 AtomicBoolean，多节点部署时每个节点都会各跑一遍，
     * 交期预警、缺料安灯、审批超时都会被重复触发。现在改成数据库行锁，
     * 顺便用 acquired_at 做僵死接管：进程崩了留下的锁 30 分钟后自动可被抢。</p>
     */
    private boolean tryAcquire(String code) {
        try {
            int rows = jdbcTemplate.update("""
                    insert into sys_job_lock (code, owner, acquired_at)
                    values (?, ?, now())
                    on conflict (code) do update
                       set owner = excluded.owner, acquired_at = now()
                     where sys_job_lock.acquired_at < now() - interval '30 minutes'
                    """, code, owner);
            return rows > 0;
        } catch (RuntimeException error) {
            // 锁表异常时宁可跳过本轮，也不要重复执行
            return false;
        }
    }

    private void release(String code) {
        try {
            jdbcTemplate.update("delete from sys_job_lock where code = ? and owner = ?", code, owner);
        } catch (RuntimeException ignored) {
            // 释放失败等 30 分钟自动过期，不影响正确性
        }
    }

    private boolean isRunning(String code) {
        try {
            Integer rows = jdbcTemplate.queryForObject(
                    "select count(*) from sys_job_lock where code = ?", Integer.class, code);
            return rows != null && rows > 0;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
