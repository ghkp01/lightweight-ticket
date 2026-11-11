package com.mes.device;

import com.mes.common.BizException;
import com.mes.device.api.DeviceTelemetryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设备采集数据：产量与停机分钟。
 *
 * <p>生产现场常见的两种来源：</p>
 * <ol>
 *   <li>边缘网关按固定周期 POST 到这里（source=GATEWAY）；网关负责对接 OPC UA / Modbus / MQTT，
 *       本系统不直接连设备，避免把驱动和网络异常带进主进程；</li>
 *   <li>没有网关时，计划/设备管理员在「设备 OEE」页面手工补录（source=MANUAL）。</li>
 * </ol>
 *
 * <p>OEE 的停机分钟会同时统计故障单与这里上报的停机，采集没接上时仍按人工数据计算。</p>
 */
@Service
public class DeviceTelemetryService implements DeviceTelemetryPort {

    private final JdbcTemplate jdbcTemplate;

    public DeviceTelemetryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TelemetryRequest(String deviceCode, Long workCenterId, BigDecimal outputQty,
                                   BigDecimal downtimeMinutes, String recordedAt, String source) {
    }

    @Transactional
    public Map<String, Object> record(TelemetryRequest request) {
        if (request == null || request.deviceCode() == null || request.deviceCode().isBlank()) {
            throw new BizException(1002, "请填写设备编号");
        }
        if (request.workCenterId() == null) {
            throw new BizException(1002, "请选择工作中心，OEE 按工作中心统计");
        }
        BigDecimal output = request.outputQty() == null ? BigDecimal.ZERO : request.outputQty();
        BigDecimal downtime = request.downtimeMinutes() == null ? BigDecimal.ZERO : request.downtimeMinutes();
        if (output.signum() < 0 || downtime.signum() < 0) {
            throw new BizException(1002, "产量与停机分钟不能为负数");
        }
        String source = request.source() == null || request.source().isBlank()
                ? "MANUAL" : request.source().trim().toUpperCase(Locale.ROOT);
        if (!List.of("MANUAL", "GATEWAY").contains(source)) {
            throw new BizException(1002, "来源只能是 MANUAL 或 GATEWAY");
        }
        OffsetDateTime recordedAt = parseTime(request.recordedAt());
        Long id = jdbcTemplate.queryForObject("""
                insert into device_telemetry (device_code, work_center_id, output_qty,
                                              downtime_minutes, source, recorded_at)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, request.deviceCode().trim(), request.workCenterId(), output,
                downtime, source, Timestamp.from(recordedAt.toInstant()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("deviceCode", request.deviceCode().trim());
        result.put("source", source);
        result.put("recordedAt", recordedAt.toString());
        return result;
    }

    /** 最近采集记录：默认看最近 7 天，按时间倒序 */
    public List<Map<String, Object>> list(String from, String to, Long workCenterId) {
        Date end = to == null || to.isBlank() ? Date.valueOf(LocalDate.now()) : Date.valueOf(to);
        Date start = from == null || from.isBlank() ? Date.valueOf(end.toLocalDate().minusDays(6)) : Date.valueOf(from);
        if (start.after(end)) {
            throw new BizException(1002, "开始日期不能晚于结束日期");
        }
        StringBuilder sql = new StringBuilder("""
                select t.id, t.device_code as "deviceCode", t.work_center_id as "workCenterId",
                       c.name as "workCenterName", t.output_qty as "outputQty",
                       t.downtime_minutes as "downtimeMinutes", t.source,
                       to_char(t.recorded_at, 'YYYY-MM-DD HH24:MI') as "recordedAt"
                from device_telemetry t
                left join rm_work_center c on c.id = t.work_center_id
                where t.recorded_at::date between ? and ?
                """);
        List<Object> args = new ArrayList<>(List.of(start, end));
        if (workCenterId != null) {
            sql.append(" and t.work_center_id = ? ");
            args.add(workCenterId);
        }
        sql.append(" order by t.recorded_at desc, t.id desc limit 200 ");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    @Override
    public int purgeBefore(LocalDate date) {
        return jdbcTemplate.update("delete from device_telemetry where recorded_at::date < ?", date);
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return OffsetDateTime.now();
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // 网关常见的是不带时区的 "yyyy-MM-dd HH:mm:ss"，按服务器时区补上
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException error) {
            throw new BizException(1002, "采集时间格式应为 ISO 时间，例如 2026-11-03T10:30:00+08:00");
        }
    }
}
