package com.mes.platform.config;

import com.mes.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统配置：水印开关的读写与类型校验。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConfigTest {

    private static final String WATERMARK = "security.watermark.enabled";

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("水印开关默认开启")
    void watermarkEnabledByDefault() {
        jdbc.update("update sys_config set config_value = 'true' where config_key = ?", WATERMARK);
        assertTrue(configService.booleanValue(WATERMARK, false));
    }

    @Test
    @DisplayName("关闭水印后前端读到 false")
    void watermarkCanBeDisabled() {
        configService.update(WATERMARK, "false");
        assertFalse(configService.booleanValue(WATERMARK, true));
        Map<String, String> map = configService.map();
        assertEquals("false", map.get(WATERMARK));
    }

    @Test
    @DisplayName("布尔配置只接受 true / false")
    void booleanConfigValidatesValue() {
        BizException ex = assertThrows(BizException.class,
                () -> configService.update(WATERMARK, "maybe"));
        assertEquals(1002, ex.getCode());
    }

    @Test
    @DisplayName("不存在的配置项被拒")
    void unknownKeyRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> configService.update("security.not.exists", "true"));
        assertEquals(2019, ex.getCode());
    }

    @Test
    @DisplayName("配置页返回分组、说明与修改人")
    void listCarriesMeta() {
        configService.update(WATERMARK, "true");
        List<Map<String, Object>> rows = configService.list();
        Map<String, Object> item = rows.stream()
                .filter(row -> WATERMARK.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        assertEquals("页面水印", item.get("title"));
        assertEquals("安全", item.get("group"));
        assertEquals("BOOLEAN", item.get("valueType"));
        assertEquals("true", item.get("value"));
    }
}
