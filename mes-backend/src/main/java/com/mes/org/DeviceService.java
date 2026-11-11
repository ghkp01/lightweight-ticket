package com.mes.org;

import com.mes.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工作中心内的设备台账。
 * 设备是产能的细粒度载体：运行 / 待机计入可用产能，维修 / 停机不计，排产时按可用设备折算中心容量。
 */
@Service
public class DeviceService {

    private static final Set<String> STATUSES = Set.of("RUNNING", "IDLE", "MAINTENANCE", "OFFLINE");

    private final JdbcTemplate jdbcTemplate;

    public DeviceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record DeviceRequest(String code, String name, Integer capacityMinutes,
                                String status, Boolean enabled, String remark) {
    }

    public List<Map<String, Object>> list(Long centerId) {
        StringBuilder sql = new StringBuilder("""
                select d.id, d.code, d.name, d.work_center_id as "workCenterId",
                       w.name as "workCenterName", d.capacity_minutes as "capacityMinutes",
                       d.status, d.enabled, d.remark,
                       to_char(d.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from work_center_device d
                join work_center w on w.id = d.work_center_id
                where 1 = 1
                """);
        if (centerId != null) {
            sql.append(" and d.work_center_id = ? ");
            return jdbcTemplate.queryForList(sql.append(" order by d.id").toString(), centerId);
        }
        return jdbcTemplate.queryForList(sql.append(" order by d.work_center_id, d.id").toString());
    }

    /** 引用选择专用：设备下拉只带编码/名称/所属工作中心 */
    public List<Map<String, Object>> options(String keyword, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return jdbcTemplate.queryForList("""
                select d.id, d.code, d.name, d.work_center_id as "workCenterId",
                       w.name as "workCenterName", d.status
                from work_center_device d
                join work_center w on w.id = d.work_center_id
                where d.code ilike ? or d.name ilike ?
                order by d.code
                limit ?
                """, like, like, size);
    }

    @Transactional
    public long create(long centerId, DeviceRequest request) {
        assertCenter(centerId);
        String code = required(request.code(), "设备编码不能为空");
        String name = required(request.name(), "设备名称不能为空");
        assertCodeUnique(code, null);
        int capacity = capacity(request.capacityMinutes());
        String status = status(request.status());
        return jdbcTemplate.queryForObject("""
                insert into work_center_device (code, name, work_center_id, capacity_minutes,
                                                status, enabled, remark)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, code, name, centerId, capacity, status,
                request.enabled() == null || request.enabled(), trim(request.remark()));
    }

    @Transactional
    public void update(long id, DeviceRequest request) {
        Map<String, Object> exists = load(id);
        String code = required(request.code(), "设备编码不能为空");
        String name = required(request.name(), "设备名称不能为空");
        assertCodeUnique(code, id);
        jdbcTemplate.update("""
                update work_center_device set code = ?, name = ?, capacity_minutes = ?,
                       status = ?, enabled = ?, remark = ?
                where id = ?
                """, code, name, capacity(request.capacityMinutes()), status(request.status()),
                request.enabled() == null ? Boolean.TRUE.equals(exists.get("enabled")) : request.enabled(),
                trim(request.remark()), id);
    }

    @Transactional
    public void delete(long id) {
        load(id);
        jdbcTemplate.update("delete from work_center_device where id = ?", id);
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, enabled from work_center_device where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(2019, "设备不存在");
        }
        return rows.get(0);
    }

    private void assertCenter(long centerId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from work_center where id = ?", Integer.class, centerId);
        if (count == null || count == 0) {
            throw new BizException(2019, "工作中心不存在");
        }
    }

    private void assertCodeUnique(String code, Long excludeId) {
        Integer count = excludeId == null
                ? jdbcTemplate.queryForObject(
                "select count(*) from work_center_device where code = ?", Integer.class, code)
                : jdbcTemplate.queryForObject(
                "select count(*) from work_center_device where code = ? and id <> ?",
                Integer.class, code, excludeId);
        if (count != null && count > 0) {
            throw new BizException(2019, "设备编码 " + code + " 已存在");
        }
    }

    private int capacity(Integer value) {
        int minutes = value == null ? 480 : value;
        if (minutes < 0 || minutes > 1440) {
            throw new BizException(1002, "设备日产能必须在 0 到 1440 分钟之间");
        }
        return minutes;
    }

    private String status(String value) {
        String target = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (target.isEmpty()) {
            return "RUNNING";
        }
        if (!STATUSES.contains(target)) {
            throw new BizException(1002, "设备状态只能是运行、待机、维修或停机");
        }
        return target;
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, message);
        }
        return value.trim();
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
