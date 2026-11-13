package com.mes.org;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.org.api.WorkCenterPort;
import com.mes.process.api.ProcessUsagePort;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工作中心：车间里真正占用产能的执行单元（设备线或手工工位）。
 * 有限产能排产按工作中心日产能占用，工序通过 work_center_id 决定去哪里做。
 *
 * <p>对外只暴露 {@link WorkCenterPort} 的只读视图，计划模块不再 join {@code work_center}。</p>
 */
@Service
public class WorkCenterService implements WorkCenterPort {

    private static final Set<String> TYPES = Set.of("MACHINE", "HAND", "OUTSOURCE");

    private final JdbcTemplate jdbcTemplate;
    private final ProcessUsagePort processUsage;
    private final CodeGenerator codeGenerator;

    public WorkCenterService(JdbcTemplate jdbcTemplate, ProcessUsagePort processUsage,
                             CodeGenerator codeGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.processUsage = processUsage;
        this.codeGenerator = codeGenerator;
    }

    public record WorkCenterRequest(String code,
                                    @NotBlank String name,
                                    Long orgId,
                                    String type,
                                    Integer dailyCapacityMinutes,
                                    Boolean enabled,
                                    String remark) {
    }

    @Override
    public List<WorkCenterInfo> centers(boolean onlyEnabled) {
        List<WorkCenterInfo> list = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, code, name, type, daily_capacity_minutes, enabled
                from work_center
                """ + (onlyEnabled ? " where enabled " : "") + " order by code")) {
            list.add(centerOf(row));
        }
        return list;
    }

    @Override
    public Map<Long, WorkCenterInfo> centersByIds(Collection<Long> centerIds) {
        Map<Long, WorkCenterInfo> map = new LinkedHashMap<>();
        if (centerIds == null) {
            return map;
        }
        List<Long> ids = centerIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, code, name, type, daily_capacity_minutes, enabled from work_center"
                        + " where id in (" + marks + ")", ids.toArray())) {
            WorkCenterInfo info = centerOf(row);
            map.put(info.id(), info);
        }
        return map;
    }

    @Override
    public Map<Long, DeviceCapacity> deviceCapacity() {
        Map<Long, DeviceCapacity> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select work_center_id as "centerId", count(*) as cnt, coalesce(sum(capacity_minutes), 0) as cap
                from work_center_device
                where enabled = true and status in ('RUNNING', 'IDLE')
                group by work_center_id
                """)) {
            map.put(((Number) row.get("centerId")).longValue(),
                    new DeviceCapacity(((Number) row.get("cnt")).intValue(),
                            ((Number) row.get("cap")).intValue()));
        }
        return map;
    }

    private static WorkCenterInfo centerOf(Map<String, Object> row) {
        return new WorkCenterInfo(((Number) row.get("id")).longValue(),
                row.get("code") == null ? null : String.valueOf(row.get("code")),
                row.get("name") == null ? null : String.valueOf(row.get("name")),
                row.get("type") == null ? null : String.valueOf(row.get("type")),
                row.get("daily_capacity_minutes") == null
                        ? 0 : ((Number) row.get("daily_capacity_minutes")).intValue(),
                Boolean.TRUE.equals(row.get("enabled")));
    }

    public List<Map<String, Object>> list(String keyword, Long orgId, Boolean enabled) {
        StringBuilder sql = new StringBuilder("""
                select w.id, w.code, w.name, w.org_id as "orgId", o.name as "orgName", o.path as "orgPath",
                       w.type, w.daily_capacity_minutes as "dailyCapacityMinutes", w.enabled, w.remark,
                       (select count(*) from work_center_device d where d.work_center_id = w.id) as "deviceCount",
                       (select count(*) from work_center_device d where d.work_center_id = w.id
                         and d.enabled = true and d.status in ('RUNNING', 'IDLE')) as "activeDeviceCount",
                       (select coalesce(sum(d.capacity_minutes), 0) from work_center_device d
                         where d.work_center_id = w.id
                           and d.enabled = true and d.status in ('RUNNING', 'IDLE')) as "deviceCapacityMinutes"
                from work_center w
                left join sys_org o on o.id = w.org_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, orgId, enabled);
        sql.append(" order by w.id");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        fillProcessUsage(rows);
        return rows;
    }

    /** 工作中心列表分页；不传 page 时仍走 list() 返回全量 */
    public PageResult page(String keyword, Long orgId, Boolean enabled, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String base = """
                from work_center w
                left join sys_org o on o.id = w.org_id
                where 1 = 1
                """;
        StringBuilder where = new StringBuilder(base);
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, keyword, orgId, enabled);
        Long total = jdbcTemplate.queryForObject("select count(*) " + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select w.id, w.code, w.name, w.org_id as "orgId", o.name as "orgName", o.path as "orgPath",
                       w.type, w.daily_capacity_minutes as "dailyCapacityMinutes", w.enabled, w.remark,
                       (select count(*) from work_center_device d where d.work_center_id = w.id) as "deviceCount",
                       (select count(*) from work_center_device d where d.work_center_id = w.id
                         and d.enabled = true and d.status in ('RUNNING', 'IDLE')) as "activeDeviceCount",
                       (select coalesce(sum(d.capacity_minutes), 0) from work_center_device d
                         where d.work_center_id = w.id
                           and d.enabled = true and d.status in ('RUNNING', 'IDLE')) as "deviceCapacityMinutes"
                """ + where + " order by w.id limit ? offset ?", pageArgs.toArray());
        fillProcessUsage(rows);
        return new PageResult(rows, total == null ? 0 : total, safePage, safeSize);
    }

    private static void appendFilters(StringBuilder sql, List<Object> args,
                                      String keyword, Long orgId, Boolean enabled) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (w.code ilike ? or w.name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (orgId != null) {
            sql.append(" and (w.org_id = ? or o.path like (select path || '%' from sys_org where id = ?)) ");
            args.add(orgId);
            args.add(orgId);
        }
        if (enabled != null) {
            sql.append(" and w.enabled = ? ");
            args.add(enabled);
        }
    }

    private void fillProcessUsage(List<Map<String, Object>> rows) {
        // 工序数与工序名归 process：一次汇总取回来再按中心补，别在 SQL 里 join 别人的表
        Map<Long, ProcessUsagePort.ProcessUsage> usage = processUsage.usageByWorkCenter();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            ProcessUsagePort.ProcessUsage item = usage.get(id);
            row.put("processCount", item == null ? 0 : item.processCount());
            row.put("processNames", item == null ? null : item.processNames());
        }
    }

    public List<Map<String, Object>> options() {
        return options(null, null);
    }

    /** 引用选择专用：关键字过滤 + 条数上限；limit 为空时保持原行为（返回全部启用工作中心） */
    public List<Map<String, Object>> options(String keyword, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                select w.id, w.code, w.name, w.org_id as "orgId", o.name as "orgName",
                       w.daily_capacity_minutes as "dailyCapacityMinutes"
                from work_center w left join sys_org o on o.id = w.org_id
                where w.enabled = true
                """);
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (w.code ilike ? or w.name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" order by w.id ");
        if (limit != null && limit > 0) {
            sql.append(" limit ? ");
            args.add(Math.min(limit, 50));
        }
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    @Transactional
    public long create(WorkCenterRequest request) {
        String code = codeOrGenerate(request.code());
        String name = requireName(request.name());
        assertCodeUnique(code, null);
        checkOrg(request.orgId());
        int capacity = normalizeCapacity(request.dailyCapacityMinutes());
        Long id = jdbcTemplate.queryForObject("""
                insert into work_center (code, name, org_id, type, daily_capacity_minutes, enabled, remark)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, code, name, request.orgId(), normalizeType(request.type()), capacity,
                request.enabled() == null || request.enabled(), trim(request.remark()));
        return id == null ? 0 : id;
    }

    @Transactional
    public void update(long id, WorkCenterRequest request) {
        Map<String, Object> exists = load(id);
        String code = normalizeCode(request.code());
        String name = requireName(request.name());
        assertCodeUnique(code, id);
        checkOrg(request.orgId());
        int capacity = normalizeCapacity(request.dailyCapacityMinutes());
        jdbcTemplate.update("""
                update work_center set code = ?, name = ?, org_id = ?, type = ?, daily_capacity_minutes = ?,
                       enabled = ?, remark = ? where id = ?
                """, code, name, request.orgId(), normalizeType(request.type()), capacity,
                request.enabled() == null ? Boolean.TRUE.equals(exists.get("enabled")) : request.enabled(),
                trim(request.remark()), id);
    }

    @Transactional
    public void delete(long id) {
        load(id);
        Set<Long> processIds = processUsage.processIdsOf(id);
        if (!processIds.isEmpty()) {
            // 工单工序一定是挂在某道工序上的，工序还在就删不了中心，所以这里一并兜住
            throw new BizException(2004, "该工作中心已被 " + processIds.size() + " 道工序引用，不能删除，请改为停用");
        }
        jdbcTemplate.update("delete from work_center where id = ?", id);
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, name, enabled from work_center where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(2004, "工作中心不存在");
        }
        return rows.get(0);
    }

    private void checkOrg(Long orgId) {
        if (orgId == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_org where id = ?", Integer.class, orgId);
        if (count == null || count == 0) {
            throw new BizException(2004, "所属组织不存在");
        }
    }

    private void assertCodeUnique(String code, Long excludeId) {
        Long count = excludeId == null
                ? jdbcTemplate.queryForObject("select count(*) from work_center where code = ?", Long.class, code)
                : jdbcTemplate.queryForObject(
                        "select count(*) from work_center where code = ? and id <> ?", Long.class, code, excludeId);
        if (count != null && count > 0) {
            throw new BizException(2004, "工作中心编码 " + code + " 已存在");
        }
    }

    private int normalizeCapacity(Integer value) {
        int capacity = value == null ? 480 : value;
        if (capacity <= 0 || capacity > 1440) {
            throw new BizException(1002, "日产能需在 1 到 1440 分钟之间");
        }
        return capacity;
    }

    /** 编码留空时按「编码规则」自动生成，填了就沿用用户输入 */
    private String codeOrGenerate(String value) {
        if (value == null || value.isBlank()) {
            return codeGenerator.next("WC");
        }
        return normalizeCode(value);
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "请填写工作中心编码");
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (code.length() > 32) {
            throw new BizException(1002, "工作中心编码不能超过 32 个字符");
        }
        return code;
    }

    private String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "请填写工作中心名称");
        }
        return value.trim();
    }

    private String normalizeType(String value) {
        String type = value == null || value.isBlank() ? "MACHINE" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new BizException(1002, "工作中心类型只能是设备线、手工工位或外协");
        }
        return type;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
