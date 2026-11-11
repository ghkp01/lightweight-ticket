package com.mes.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportPushTest {

    @Autowired
    private ReportPushService service;

    @Test
    @DisplayName("推送状态读取配置，日报文本包含经营口径")
    void statusAndText() {
        Map<String, Object> status = service.status();
        assertEquals("FEISHU", status.get("type"));
        assertEquals(false, status.get("enabled"));

        String text = service.buildText();
        assertTrue(text.contains("经营日报"));
        assertTrue(text.contains("销售"));
        assertTrue(text.contains("生产"));
        assertTrue(text.contains("质量"));
        assertTrue(text.contains("交付"));
    }
}
