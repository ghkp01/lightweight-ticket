package com.mes.device;

import com.mes.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeviceTelemetryTest {

    @Autowired
    private DeviceTelemetryService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("网关上报的采集数据可以落库并按区间查回")
    void recordAndList() {
        Long centerId = jdbc.queryForObject("select id from work_center order by id limit 1", Long.class);
        Map<String, Object> created = service.record(new DeviceTelemetryService.TelemetryRequest(
                "T-01", centerId, new BigDecimal("12"), new BigDecimal("30"),
                "2026-11-03T10:00:00+08:00", "GATEWAY"));
        assertTrue(((Number) created.get("id")).longValue() > 0);

        List<Map<String, Object>> rows = service.list("2026-11-03", "2026-11-03", centerId);
        assertTrue(rows.stream().anyMatch(row -> "T-01".equals(row.get("deviceCode"))));
    }

    @Test
    @DisplayName("按保留期清理旧采集数据")
    void purgeOldTelemetry() {
        Long centerId = jdbc.queryForObject("select id from work_center order by id limit 1", Long.class);
        service.record(new DeviceTelemetryService.TelemetryRequest(
                "T-OLD", centerId, BigDecimal.ONE, BigDecimal.ZERO,
                "2020-01-01T00:00:00+08:00", "GATEWAY"));
        int removed = service.purgeBefore(LocalDate.of(2021, 1, 1));
        assertTrue(removed >= 1);
        List<Map<String, Object>> rows = service.list("2020-01-01", "2020-01-02", centerId);
        assertTrue(rows.stream().noneMatch(row -> "T-OLD".equals(row.get("deviceCode"))));
    }

    @Test
    @DisplayName("负产量或负停机被拒绝")
    void negativeRejected() {
        Long centerId = jdbc.queryForObject("select id from work_center order by id limit 1", Long.class);
        assertThrows(BizException.class, () -> service.record(new DeviceTelemetryService.TelemetryRequest(
                "T-02", centerId, new BigDecimal("-1"), BigDecimal.ZERO, null, "MANUAL")));
        assertThrows(BizException.class, () -> service.record(new DeviceTelemetryService.TelemetryRequest(
                "T-02", centerId, BigDecimal.ZERO, new BigDecimal("-5"), null, "MANUAL")));
    }
}
