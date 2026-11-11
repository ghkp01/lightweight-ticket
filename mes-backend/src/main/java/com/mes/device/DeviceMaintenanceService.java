package com.mes.device;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.device.api.MaintenancePort;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备点检与保养：点检项模板、每日点检任务、保养计划与记录、故障记录。
 *
 * 设计取舍：
 * - 点检任务每台设备每天一张（设备 + 日期唯一），定时任务与手工生成都不会重复建；
 * - 点检项挂在设备上，device_id 为空表示通用项，生成任务时按「设备项 + 通用项」合并；
 * - 点检有 NG 就推预警给管理员与计划，并可在故障记录里继续跟踪停机；
 * - 保养计划按固定周期滚动：执行一次就按周期把下次日期往后推，不建复杂的日历表达式。
 */
@Service
public class DeviceMaintenanceService implements MaintenancePort {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final NoticePort notice;

    public DeviceMaintenanceService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                                    NoticePort notice) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.notice = notice;
    }

    public record CheckItemRequest(Long id, Long deviceId, String name, String method, String standard,
                                   Integer sort, Boolean enabled) {
    }

    public record CheckResultLine(Long itemId, String itemName, String method, String standard,
                                  String result, String remark) {
    }

    public record SubmitCheckRequest(List<CheckResultLine> results, String remark) {
    }

    public record PlanRequest(Long id, Long deviceId, String name, Integer cycleDays, LocalDate lastDate,
                             LocalDate nextDate, String standard, Boolean enabled) {
    }

    public record ExecutePlanRequest(LocalDate doneDate, String worker, BigDecimal cost, String remark) {
    }

    public record FaultRequest(Long deviceId, String symptom, String reporter) {
    }

    public record CloseFaultRequest(String cause, String action, java.time.LocalDateTime recoveredAt,
                                    String handler) {
    }

    // ---------------------------------------------------------------- 概览

    public Map<String, Object> summary() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select (select count(*) from device_check_task where plan_date = current_date) as "todayTasks",
                       (select count(*) from device_check_task
                         where plan_date = current_date and status = 'DONE') as "todayDone",
                       (select count(*) from device_check_result r
                          join device_check_task t on t.id = r.task_id
                         where t.plan_date = current_date and r.result = 'NG') as "todayNg",
                       (select count(*) from device_maintain_plan
                         where enabled and next_date <= current_date + cast(? as integer)) as "maintainDue",
                       (select count(*) from device_maintain_plan
                         where enabled and next_date < current_date) as "maintainOverdue",
                       (select count(*) from device_fault where status = 'OPEN') as "openFaults",
                       (select count(*) from work_center_device where enabled) as "devices"
                """, noticeDays());
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("maintainNoticeDays", noticeDays());
        return result;
    }

    // ---------------------------------------------------------------- 点检

    public List<Map<String, Object>> checkTasks(LocalDate date, Long deviceId, String status) {
        StringBuilder where = new StringBuilder(" where t.plan_date = ? ");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(date == null ? LocalDate.now() : date));
        if (deviceId != null) {
            where.append(" and t.device_id = ? ");
            args.add(deviceId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and t.status = ? ");
            args.add(status);
        }
        return jdbcTemplate.queryForList("""
                select t.id, t.task_no as "taskNo", t.plan_date as "planDate", t.status,
                       t.device_id as "deviceId", d.code as "deviceCode", d.name as "deviceName",
                       d.status as "deviceStatus", wc.name as "workCenterName",
                       t.checker, t.remark,
                       to_char(t.checked_at, 'YYYY-MM-DD HH24:MI') as "checkedAt",
                       (select count(*) from device_check_result r where r.task_id = t.id) as "itemCount",
                       (select count(*) from device_check_result r
                         where r.task_id = t.id and r.result = 'NG') as "ngCount"
                from device_check_task t
                join work_center_device d on d.id = t.device_id
                join rm_work_center wc on wc.id = d.work_center_id
                """ + where + " order by t.status desc, d.code", args.toArray());
    }

    public Map<String, Object> checkDetail(long taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select t.id, t.task_no as "taskNo", t.plan_date as "planDate", t.status,
                       t.device_id as "deviceId", d.code as "deviceCode", d.name as "deviceName",
                       d.status as "deviceStatus", wc.name as "workCenterName",
                       t.checker, t.remark,
                       to_char(t.checked_at, 'YYYY-MM-DD HH24:MI') as "checkedAt"
                from device_check_task t
                join work_center_device d on d.id = t.device_id
                join rm_work_center wc on wc.id = d.work_center_id
                where t.id = ?
                """, taskId);
        if (rows.isEmpty()) {
            throw new BizException(4001, "点检任务不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        long deviceId = ((Number) detail.get("deviceId")).longValue();
        List<Map<String, Object>> results = jdbcTemplate.queryForList("""
                select id, item_id as "itemId", item_name as "itemName", method, standard, result, remark
                from device_check_result where task_id = ? order by id
                """, taskId);
        detail.put("results", results);
        // 还没点检时给出待点检清单（设备项 + 通用项）
        detail.put("items", results.isEmpty() ? checkItems(deviceId) : List.of());
        return detail;
    }

    /** 按设备生成点检任务，已存在的跳过；返回新生成的条数 */
    @Transactional
    public int generateTasks(LocalDate date) {
        LocalDate planDate = date == null ? LocalDate.now() : date;
        List<Long> deviceIds = jdbcTemplate.queryForList(
                "select id from work_center_device where enabled order by id", Long.class);
        int created = 0;
        for (Long deviceId : deviceIds) {
            Integer exists = jdbcTemplate.queryForObject("""
                    select count(*) from device_check_task where device_id = ? and plan_date = ?
                    """, Integer.class, deviceId, Date.valueOf(planDate));
            if (exists != null && exists > 0) {
                continue;
            }
            jdbcTemplate.update("""
                    insert into device_check_task (task_no, device_id, plan_date, status)
                    values (?, ?, ?, 'PENDING')
                    on conflict (device_id, plan_date) do nothing
                    """, codeGenerator.next("DC"), deviceId, Date.valueOf(planDate));
            created++;
        }
        return created;
    }

    @Transactional
    public Map<String, Object> submitCheck(long taskId, SubmitCheckRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select t.id, t.task_no as "taskNo", t.status, t.plan_date as "planDate",
                       t.device_id as "deviceId", d.code as "deviceCode", d.name as "deviceName"
                from device_check_task t
                join work_center_device d on d.id = t.device_id
                where t.id = ? for update
                """, taskId);
        if (rows.isEmpty()) {
            throw new BizException(4001, "点检任务不存在");
        }
        Map<String, Object> task = rows.get(0);
        if ("DONE".equals(String.valueOf(task.get("status")))) {
            throw new BizException(4021, "该设备今天的点检已完成，如需修改请先撤销");
        }
        List<CheckResultLine> lines = request == null || request.results() == null
                ? List.of() : request.results();
        if (lines.isEmpty()) {
            throw new BizException(1002, "请至少提交一条点检结果");
        }
        jdbcTemplate.update("delete from device_check_result where task_id = ?", taskId);
        int ng = 0;
        for (CheckResultLine line : lines) {
            String result = "NG".equalsIgnoreCase(String.valueOf(line.result())) ? "NG" : "OK";
            if ("NG".equals(result)) {
                ng++;
            }
            String itemName = line.itemName() == null || line.itemName().isBlank()
                    ? "未命名点检项" : line.itemName().trim();
            jdbcTemplate.update("""
                    insert into device_check_result (task_id, item_id, item_name, method, standard,
                                                     result, remark)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, taskId, line.itemId(), itemName, line.method(), line.standard(), result,
                    line.remark());
        }
        jdbcTemplate.update("""
                update device_check_task set status = 'DONE', checker = ?, checked_at = now(), remark = ?
                where id = ?
                """, CurrentUser.name(), request == null ? null : request.remark(), taskId);
        if (ng > 0) {
            String deviceName = task.get("deviceName") + "（" + task.get("deviceCode") + "）";
            notice.pushToRoles(List.of("ADMIN", "PLANNER"), NoticeMessage.of("ALERT",
                    "设备 " + task.get("deviceCode") + " 点检发现 " + ng + " 项异常",
                    deviceName + " 在 " + task.get("planDate") + " 的点检中有 " + ng
                            + " 项不合格，请确认是否需要停机检修，并在故障记录里登记。",
                    "DEVICE_CHECK", String.valueOf(task.get("taskNo"))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("items", lines.size());
        result.put("ng", ng);
        return result;
    }

    /** 撤销点检（点错了重来），只允许当天任务 */
    @Transactional
    public void resetCheck(long taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select status from device_check_task where id = ? for update", taskId);
        if (rows.isEmpty()) {
            throw new BizException(4001, "点检任务不存在");
        }
        jdbcTemplate.update("delete from device_check_result where task_id = ?", taskId);
        jdbcTemplate.update("""
                update device_check_task set status = 'PENDING', checker = null, checked_at = null
                where id = ?
                """, taskId);
    }

    public List<Map<String, Object>> checkItems(Long deviceId) {
        if (deviceId == null) {
            return jdbcTemplate.queryForList("""
                    select i.id, i.device_id as "deviceId", d.name as "deviceName", i.name, i.method,
                           i.standard, i.sort, i.enabled
                    from device_check_item i
                    left join work_center_device d on d.id = i.device_id
                    order by i.device_id nulls first, i.sort, i.id
                    """);
        }
        return jdbcTemplate.queryForList("""
                select i.id, i.device_id as "deviceId", d.name as "deviceName", i.name, i.method,
                       i.standard, i.sort, i.enabled
                from device_check_item i
                left join work_center_device d on d.id = i.device_id
                where i.enabled and (i.device_id is null or i.device_id = ?)
                order by i.device_id nulls last, i.sort, i.id
                """, deviceId);
    }

    @Transactional
    public long saveCheckItem(CheckItemRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BizException(1002, "点检项名称不能为空");
        }
        if (request.deviceId() != null) {
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from work_center_device where id = ?", Integer.class, request.deviceId());
            if (exists == null || exists == 0) {
                throw new BizException(1002, "设备不存在");
            }
        }
        String method = request.method() == null || request.method().isBlank()
                ? "目视" : request.method().trim();
        if (request.id() != null) {
            int updated = jdbcTemplate.update("""
                    update device_check_item set device_id = ?, name = ?, method = ?, standard = ?,
                           sort = ?, enabled = ?
                    where id = ?
                    """, request.deviceId(), request.name().trim(), method, request.standard(),
                    request.sort() == null ? 0 : request.sort(),
                    request.enabled() == null || request.enabled(), request.id());
            if (updated == 0) {
                throw new BizException(4001, "点检项不存在");
            }
            return request.id();
        }
        return jdbcTemplate.queryForObject("""
                insert into device_check_item (device_id, name, method, standard, sort, enabled)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, request.deviceId(), request.name().trim(), method, request.standard(),
                request.sort() == null ? 0 : request.sort(), request.enabled() == null || request.enabled());
    }

    @Transactional
    public void deleteCheckItem(long id) {
        jdbcTemplate.update("delete from device_check_item where id = ?", id);
    }

    // ---------------------------------------------------------------- 保养

    public List<Map<String, Object>> plans(boolean dueOnly) {
        StringBuilder sql = new StringBuilder("""
                select p.id, p.device_id as "deviceId", d.code as "deviceCode", d.name as "deviceName",
                       wc.name as "workCenterName", p.name, p.cycle_days as "cycleDays",
                       to_char(p.last_date, 'YYYY-MM-DD') as "lastDate",
                       to_char(p.next_date, 'YYYY-MM-DD') as "nextDate",
                       (p.next_date - current_date) as "daysLeft",
                       (p.next_date < current_date) as "overdue",
                       p.standard, p.enabled
                from device_maintain_plan p
                join work_center_device d on d.id = p.device_id
                join rm_work_center wc on wc.id = d.work_center_id
                where p.enabled
                """);
        if (dueOnly) {
            sql.append(" and p.next_date <= current_date + ").append(noticeDays());
        }
        sql.append(" order by p.next_date, d.code");
        return jdbcTemplate.queryForList(sql.toString());
    }

    @Transactional
    public long savePlan(PlanRequest request) {
        if (request.deviceId() == null) {
            throw new BizException(1002, "请选择设备");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BizException(1002, "保养项目名称不能为空");
        }
        int cycle = request.cycleDays() == null ? 30 : request.cycleDays();
        if (cycle <= 0) {
            throw new BizException(1002, "保养周期必须大于 0 天");
        }
        Integer device = jdbcTemplate.queryForObject(
                "select count(*) from work_center_device where id = ?", Integer.class, request.deviceId());
        if (device == null || device == 0) {
            throw new BizException(1002, "设备不存在");
        }
        LocalDate next = request.nextDate();
        if (next == null) {
            LocalDate base = request.lastDate() == null ? LocalDate.now() : request.lastDate();
            next = base.plusDays(cycle);
        }
        if (request.id() != null) {
            int updated = jdbcTemplate.update("""
                    update device_maintain_plan
                    set device_id = ?, name = ?, cycle_days = ?, last_date = ?, next_date = ?,
                        standard = ?, enabled = ?
                    where id = ?
                    """, request.deviceId(), request.name().trim(), cycle, request.lastDate(),
                    Date.valueOf(next), request.standard(),
                    request.enabled() == null || request.enabled(), request.id());
            if (updated == 0) {
                throw new BizException(4001, "保养计划不存在");
            }
            return request.id();
        }
        return jdbcTemplate.queryForObject("""
                insert into device_maintain_plan (device_id, name, cycle_days, last_date, next_date,
                                                 standard, enabled)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, request.deviceId(), request.name().trim(), cycle, request.lastDate(),
                Date.valueOf(next), request.standard(), request.enabled() == null || request.enabled());
    }

    @Transactional
    public void deletePlan(long id) {
        jdbcTemplate.update("delete from device_maintain_plan where id = ?", id);
    }

    /** 执行保养：写记录、推下次日期，计划停用时只留记录 */
    @Transactional
    public Map<String, Object> executePlan(long planId, ExecutePlanRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, device_id, name, cycle_days, enabled from device_maintain_plan where id = ?
                """, planId);
        if (rows.isEmpty()) {
            throw new BizException(4001, "保养计划不存在");
        }
        Map<String, Object> plan = rows.get(0);
        LocalDate doneDate = request == null || request.doneDate() == null
                ? LocalDate.now() : request.doneDate();
        LocalDate next = doneDate.plusDays(((Number) plan.get("cycle_days")).longValue());
        jdbcTemplate.update("""
                insert into device_maintain_log (plan_id, device_id, done_date, worker, result, cost, remark)
                values (?, ?, ?, ?, 'DONE', ?, ?)
                """, planId, plan.get("device_id"), Date.valueOf(doneDate),
                request == null || request.worker() == null || request.worker().isBlank()
                        ? CurrentUser.name() : request.worker(),
                request == null || request.cost() == null ? BigDecimal.ZERO : request.cost(),
                request == null ? null : request.remark());
        jdbcTemplate.update("""
                update device_maintain_plan set last_date = ?, next_date = ? where id = ?
                """, Date.valueOf(doneDate), Date.valueOf(next), planId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("doneDate", String.valueOf(doneDate));
        result.put("nextDate", String.valueOf(next));
        return result;
    }

    public List<Map<String, Object>> planLogs(Long planId, Long deviceId) {
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (planId != null) {
            where.append(" and l.plan_id = ? ");
            args.add(planId);
        }
        if (deviceId != null) {
            where.append(" and l.device_id = ? ");
            args.add(deviceId);
        }
        return jdbcTemplate.queryForList("""
                select l.id, l.plan_id as "planId", l.device_id as "deviceId",
                       d.code as "deviceCode", d.name as "deviceName",
                       to_char(l.done_date, 'YYYY-MM-DD') as "doneDate", l.worker, l.result,
                       l.cost, l.remark
                from device_maintain_log l
                join work_center_device d on d.id = l.device_id
                """ + where + " order by l.done_date desc, l.id desc limit 200", args.toArray());
    }

    // ---------------------------------------------------------------- 故障

    public List<Map<String, Object>> faults(String status, Long deviceId) {
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and f.status = ? ");
            args.add(status);
        }
        if (deviceId != null) {
            where.append(" and f.device_id = ? ");
            args.add(deviceId);
        }
        return jdbcTemplate.queryForList("""
                select f.id, f.fault_no as "faultNo", f.device_id as "deviceId",
                       d.code as "deviceCode", d.name as "deviceName",
                       wc.name as "workCenterName", f.symptom, f.cause, f.action,
                       f.downtime_minutes as "downtimeMinutes", f.reporter, f.handler, f.status,
                       to_char(f.occurred_at, 'YYYY-MM-DD HH24:MI') as "occurredAt",
                       to_char(f.recovered_at, 'YYYY-MM-DD HH24:MI') as "recoveredAt"
                from device_fault f
                join work_center_device d on d.id = f.device_id
                join rm_work_center wc on wc.id = d.work_center_id
                """ + where + " order by f.status desc, f.occurred_at desc limit 200", args.toArray());
    }

    /** 报修：建故障单并把设备置为停机，避免排产继续往这台设备上压活 */
    @Transactional
    public long openFault(FaultRequest request) {
        if (request.deviceId() == null) {
            throw new BizException(1002, "请选择设备");
        }
        if (request.symptom() == null || request.symptom().isBlank()) {
            throw new BizException(1002, "请填写故障现象");
        }
        List<Map<String, Object>> devices = jdbcTemplate.queryForList("""
                select id, code, name, status from work_center_device where id = ?
                """, request.deviceId());
        if (devices.isEmpty()) {
            throw new BizException(1002, "设备不存在");
        }
        Map<String, Object> device = devices.get(0);
        String faultNo = codeGenerator.next("DF");
        Long id = jdbcTemplate.queryForObject("""
                insert into device_fault (fault_no, device_id, symptom, reporter, status)
                values (?, ?, ?, ?, 'OPEN') returning id
                """, Long.class, faultNo, request.deviceId(), request.symptom().trim(),
                request.reporter() == null || request.reporter().isBlank()
                        ? CurrentUser.name() : request.reporter().trim());
        jdbcTemplate.update("update work_center_device set status = 'OFFLINE' where id = ?",
                request.deviceId());
        notice.pushToRoles(List.of("ADMIN", "PLANNER"), NoticeMessage.of("ALERT",
                "设备 " + device.get("code") + " 报修停机",
                device.get("name") + "（" + device.get("code") + "）报修：" + request.symptom().trim()
                        + "。设备已置为停机，修复后请关闭故障单。",
                "DEVICE_FAULT", faultNo));
        return id;
    }

    /** 关闭故障：算停机时长并把设备恢复为待机（由现场再决定是否开工） */
    @Transactional
    public Map<String, Object> closeFault(long id, CloseFaultRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, device_id, occurred_at, status from device_fault where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(4001, "故障记录不存在");
        }
        Map<String, Object> fault = rows.get(0);
        if ("CLOSED".equals(String.valueOf(fault.get("status")))) {
            throw new BizException(4021, "该故障已关闭");
        }
        java.time.LocalDateTime recoveredAt = request == null || request.recoveredAt() == null
                ? java.time.LocalDateTime.now() : request.recoveredAt();
        jdbcTemplate.update("""
                update device_fault
                set cause = ?, action = ?, handler = ?, recovered_at = ?, status = 'CLOSED',
                    downtime_minutes = greatest(0, round(extract(epoch from
                        (cast(? as timestamptz) - occurred_at)) / 60))
                where id = ?
                """, request == null ? null : request.cause(),
                request == null ? null : request.action(),
                request == null || request.handler() == null || request.handler().isBlank()
                        ? CurrentUser.name() : request.handler(),
                java.sql.Timestamp.valueOf(recoveredAt), java.sql.Timestamp.valueOf(recoveredAt), id);
        jdbcTemplate.update("""
                update work_center_device set status = 'IDLE'
                where id = ? and status = 'OFFLINE'
                """, fault.get("device_id"));
        Map<String, Object> result = jdbcTemplate.queryForMap("""
                select f.id, f.fault_no as "faultNo", f.status, f.downtime_minutes as "downtimeMinutes",
                       to_char(f.recovered_at, 'YYYY-MM-DD HH24:MI') as "recoveredAt"
                from device_fault f where f.id = ?
                """, id);
        return result;
    }

    /** 定时任务用：生成当天点检任务 + 推保养到期提醒，返回处理条数 */
    @Transactional
    @Override
    public int dailyUpkeep() {
        int created = generateTasks(LocalDate.now());
        List<Map<String, Object>> due = jdbcTemplate.queryForList("""
                select p.id, p.name, d.code as "deviceCode", d.name as "deviceName",
                       (p.next_date - current_date) as "daysLeft"
                from device_maintain_plan p
                join work_center_device d on d.id = p.device_id
                where p.enabled and p.next_date <= current_date + cast(? as integer)
                order by p.next_date
                """, noticeDays());
        if (due.isEmpty()) {
            return created;
        }
        Integer today = jdbcTemplate.queryForObject("""
                select count(*) from sys_notice where ref_type = 'DEVICE_MAINTAIN'
                  and created_at::date = current_date
                """, Integer.class);
        if (today != null && today > 0) {
            return created;
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(due.size(), 10); i++) {
            Map<String, Object> row = due.get(i);
            int days = ((Number) row.get("daysLeft")).intValue();
            lines.add(row.get("deviceCode") + " " + row.get("name")
                    + (days < 0 ? "（逾期 " + (-days) + " 天）" : "（还有 " + days + " 天）"));
        }
        notice.pushToRoles(List.of("ADMIN", "PLANNER"), NoticeMessage.of("TASK",
                due.size() + " 项设备保养到期",
                "以下保养需要安排：" + String.join("、", lines)
                        + (due.size() > 10 ? " 等 " + due.size() + " 项" : "")
                        + "。完成后在「设备保养」里登记，下次日期会自动往后推。",
                "DEVICE_MAINTAIN", String.valueOf(due.get(0).get("id"))));
        return created + due.size();
    }

    private int noticeDays() {
        List<String> value = jdbcTemplate.queryForList(
                "select config_value from sys_config where config_key = 'device.maintain.notice-days'",
                String.class);
        if (value.isEmpty() || value.get(0) == null || value.get(0).isBlank()) {
            return 7;
        }
        try {
            return Math.max(0, Integer.parseInt(value.get(0).trim()));
        } catch (NumberFormatException e) {
            return 7;
        }
    }
}
