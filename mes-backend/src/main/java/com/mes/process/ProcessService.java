package com.mes.process;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.org.api.WorkCenterPort;
import com.mes.process.api.ProcessPort;
import com.mes.production.api.WorkOrderOperationPort;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工序主数据：车间可执行的工序目录，工艺路线与工单工序都引用这里。
 *
 * <p>对外只暴露 {@link ProcessPort} 的只读取名，别的模块不再 join {@code process}。</p>
 */
@Service
public class ProcessService implements ProcessPort {

    private static final Set<String> CATEGORIES = Set.of(
            "CUTTING", "MACHINING", "BENDING", "ASSEMBLY", "SPRAYING", "INSPECTION", "PACKING", "OUTSOURCE");

    private final JdbcTemplate jdbcTemplate;
    private final WorkCenterPort workCenters;
    private final WorkOrderOperationPort workOrderOperations;
    private final CodeGenerator codeGenerator;

    public ProcessService(JdbcTemplate jdbcTemplate, WorkCenterPort workCenters,
                          WorkOrderOperationPort workOrderOperations, CodeGenerator codeGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.workCenters = workCenters;
        this.workOrderOperations = workOrderOperations;
        this.codeGenerator = codeGenerator;
    }

    public record ProcessRequest(String code,
                                 @NotBlank String name,
                                 @NotBlank String category,
                                 BigDecimal defaultMinutes,
                                 BigDecimal defaultPrice,
                                 Boolean needInspection,
                                 String inspectionMode,
                                 Boolean enabled,
                                 Long workCenterId,
                                 String remark) {
    }

    public List<Map<String, Object>> list(String keyword, Boolean enabled) {
        return query(keyword, enabled, null, null);
    }

    /** 工序列表分页：工序会随工艺细化持续增加，列表页改为后端分页 */
    public PageResult page(String keyword, Boolean enabled, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        List<Object> countArgs = new ArrayList<>();
        StringBuilder where = where(keyword, enabled, countArgs);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from process p " + where, Long.class, countArgs.toArray());
        List<Map<String, Object>> records = query(keyword, enabled, safeSize, (safePage - 1) * safeSize);
        return new PageResult(records, total == null ? 0 : total, safePage, safeSize);
    }

    private StringBuilder where(String keyword, Boolean enabled, List<Object> args) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder where = new StringBuilder(" where (p.code ilike ? or p.name ilike ?) ");
        args.add(like);
        args.add(like);
        if (enabled != null) {
            where.append(" and p.enabled = ? ");
            args.add(enabled);
        }
        return where;
    }

    private List<Map<String, Object>> query(String keyword, Boolean enabled, Integer limit, Integer offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select p.id, p.code, p.name, p.category, p.default_minutes as "defaultMinutes",
                       p.default_price as "defaultPrice", p.need_inspection as "needInspection",
                       p.enabled, p.remark, p.work_center_id as "workCenterId", p.inspection_mode as "inspectionMode",
                       (select count(*) from product_routing r where r.process_id = p.id) as "routingCount"
                from process p
                """);
        sql.append(where(keyword, enabled, args)).append(" order by p.id ");
        if (limit != null) {
            sql.append(" limit ? offset ?");
            args.add(limit);
            args.add(offset);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        // 工作中心名称/日产能归 org，工单工序数归 production：各要一份批量结果补上，别 join 别人的表
        List<Long> centerIds = rows.stream().map(row -> row.get("workCenterId"))
                .filter(Number.class::isInstance).map(value -> ((Number) value).longValue()).distinct().toList();
        var centers = workCenters.centersByIds(centerIds);
        // 只统计当前页的工序，避免每次翻页都全表 group by
        List<Long> processIds = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        Map<Long, Integer> operationCounts = workOrderOperations.operationCounts(processIds);
        for (Map<String, Object> row : rows) {
            WorkCenterPort.WorkCenterInfo center = row.get("workCenterId") instanceof Number id
                    ? centers.get(id.longValue()) : null;
            row.put("workCenterName", center == null ? null : center.name());
            row.put("workCenterCapacity", center == null ? null : center.dailyCapacityMinutes());
            long processId = ((Number) row.get("id")).longValue();
            row.put("operationCount", operationCounts.getOrDefault(processId, 0));
        }
        return rows;
    }

    /** 引用选择专用：工序下拉只带选择与默认值需要的字段 */
    public List<Map<String, Object>> options(String keyword, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return jdbcTemplate.queryForList("""
                select id, code, name, category, default_minutes as "defaultMinutes",
                       default_price as "defaultPrice", need_inspection as "needInspection",
                       inspection_mode as "inspectionMode"
                from process
                where enabled and (code ilike ? or name ilike ?)
                order by code
                limit ?
                """, like, like, size);
    }

    public long create(ProcessRequest request) {
        String code = codeOrGenerate(request.code());
        String name = requireName(request.name());
        String category = normalizeCategory(request.category());
        String inspectionMode = normalizeInspectionMode(request.inspectionMode(), request.needInspection());
        assertDuplicate(code, name, null);
        checkWorkCenter(request.workCenterId());
        return jdbcTemplate.queryForObject("""
                insert into process (code, name, category, default_minutes, default_price,
                                     need_inspection, inspection_mode, enabled, work_center_id, remark)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, code, name, category,
                decimal(request.defaultMinutes()), decimal(request.defaultPrice()),
                !"NONE".equals(inspectionMode), inspectionMode,
                request.enabled() == null || request.enabled(),
                request.workCenterId(), trim(request.remark()));
    }

    public void update(long id, ProcessRequest request) {
        Map<String, Object> exists = load(id);
        String code = normalizeCode(request.code());
        String name = requireName(request.name());
        String category = normalizeCategory(request.category());
        String inspectionMode = normalizeInspectionMode(request.inspectionMode(), request.needInspection());
        assertDuplicate(code, name, id);
        checkWorkCenter(request.workCenterId());
        jdbcTemplate.update("""
                update process set code = ?, name = ?, category = ?, default_minutes = ?, default_price = ?,
                       need_inspection = ?, inspection_mode = ?, enabled = ?, work_center_id = ?, remark = ?
                where id = ?
                """, code, name, category, decimal(request.defaultMinutes()), decimal(request.defaultPrice()),
                !"NONE".equals(inspectionMode), inspectionMode,
                request.enabled() == null ? Boolean.TRUE.equals(exists.get("enabled")) : request.enabled(),
                request.workCenterId(), trim(request.remark()), id);
        // 工序名称变更时同步已生成的工单工序名称，保证历史单据与主数据一致（写工单归生产）
        workOrderOperations.renameOperationsOfProcess(id, name);
        // 工艺路线上的检验模式是工序主数据的镜像，改工序就同步；历史工单工序保持下单时的快照不动
        jdbcTemplate.update("""
                update product_routing set inspection_mode = ?, need_inspection = ? where process_id = ?
                """, inspectionMode, !"NONE".equals(inspectionMode), id);
    }

    /** 检验模式三态；老客户端只传 needInspection 时按「独立检验工序」处理 */
    private String normalizeInspectionMode(String mode, Boolean needInspection) {
        String value = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return Boolean.TRUE.equals(needInspection) ? "ROUTING" : "NONE";
        }
        if (!value.equals("NONE") && !value.equals("ATTACHED") && !value.equals("ROUTING")) {
            throw new BizException(1002, "检验模式只能是不检验、工序内检验点或独立检验工序");
        }
        return value;
    }

    @Override
    public String name(long processId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select name from process where id = ?", String.class, processId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Map<Long, String> names(Collection<Long> processIds) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (processIds == null) {
            return map;
        }
        List<Long> ids = processIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "select id, name from process where id in (" + marks + ")", ids.toArray())) {
            map.put(((Number) row.get("id")).longValue(), String.valueOf(row.get("name")));
        }
        return map;
    }

    @Override
    public ProcessInfo process(long processId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, code, name, enabled, daily_capacity_minutes, work_center_id
                from process where id = ?
                """, processId);
        return rows.isEmpty() ? null : processOf(rows.get(0));
    }

    @Override
    public Map<Long, ProcessInfo> processes() {
        Map<Long, ProcessInfo> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, code, name, enabled, daily_capacity_minutes, work_center_id
                from process order by id
                """)) {
            ProcessInfo info = processOf(row);
            map.put(info.id(), info);
        }
        return map;
    }

    private static ProcessInfo processOf(Map<String, Object> row) {
        return new ProcessInfo(((Number) row.get("id")).longValue(),
                row.get("code") == null ? null : String.valueOf(row.get("code")),
                row.get("name") == null ? null : String.valueOf(row.get("name")),
                Boolean.TRUE.equals(row.get("enabled")),
                row.get("daily_capacity_minutes") == null
                        ? null : ((Number) row.get("daily_capacity_minutes")).intValue(),
                row.get("work_center_id") == null
                        ? null : ((Number) row.get("work_center_id")).longValue());
    }

    public void delete(long id) {
        load(id);
        Integer routing = jdbcTemplate.queryForObject(
                "select count(*) from product_routing where process_id = ?", Integer.class, id);
        if (routing != null && routing > 0) {
            throw new BizException(1409, "该工序已被 " + routing + " 条工艺路线引用，不能删除，请改为停用");
        }
        Integer ops = workOrderOperations.operationCounts(List.of(id)).get(id);
        if (ops != null && ops > 0) {
            throw new BizException(1409, "该工序已用于 " + ops + " 道工单工序，不能删除，请改为停用");
        }
        jdbcTemplate.update("delete from process where id = ?", id);
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, name, enabled from process where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException("工序不存在");
        }
        return rows.get(0);
    }

    private void assertDuplicate(String code, String name, Long excludeId) {
        String codeSql = "select count(*) from process where code = ?"
                + (excludeId == null ? "" : " and id <> ?");
        Integer codeCount = excludeId == null
                ? jdbcTemplate.queryForObject(codeSql, Integer.class, code)
                : jdbcTemplate.queryForObject(codeSql, Integer.class, code, excludeId);
        if (codeCount != null && codeCount > 0) {
            throw new BizException(1409, "工序编码 " + code + " 已存在");
        }
        String nameSql = "select count(*) from process where name = ?"
                + (excludeId == null ? "" : " and id <> ?");
        Integer nameCount = excludeId == null
                ? jdbcTemplate.queryForObject(nameSql, Integer.class, name)
                : jdbcTemplate.queryForObject(nameSql, Integer.class, name, excludeId);
        if (nameCount != null && nameCount > 0) {
            throw new BizException(1409, "工序名称 " + name + " 已存在");
        }
    }

    private void checkWorkCenter(Long workCenterId) {
        if (workCenterId == null) {
            return;
        }
        if (!workCenters.centersByIds(java.util.List.of(workCenterId)).containsKey(workCenterId)) {
            throw new BizException(1002, "所选工作中心不存在");
        }
    }

    /** 编码留空时按「编码规则」自动生成，填了就沿用用户输入 */
    private String codeOrGenerate(String value) {
        if (value == null || value.isBlank()) {
            return codeGenerator.next("OP");
        }
        return normalizeCode(value);
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "请填写工序编码");
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (code.length() > 32) {
            throw new BizException(1002, "工序编码不能超过 32 个字符");
        }
        return code;
    }

    private String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "请填写工序名称");
        }
        String name = value.trim();
        if (name.length() > 64) {
            throw new BizException(1002, "工序名称不能超过 64 个字符");
        }
        return name;
    }

    private String normalizeCategory(String value) {
        String category = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new BizException(1002, "工序类型只能是 " + String.join("、", CATEGORIES));
        }
        return category;
    }

    private BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
