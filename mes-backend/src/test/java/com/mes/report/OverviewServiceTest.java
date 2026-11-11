package com.mes.report;

import com.mes.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OverviewServiceTest {

    @Autowired
    private OverviewService service;

    @Test
    @DisplayName("九个分组都能返回指标，SQL 与读模型可用")
    void allCategoriesReturnKpis() {
        for (String category : List.of("sales", "planning", "production", "purchase", "warehouse",
                "quality", "report", "base", "system")) {
            Map<String, Object> data = service.overview(category, "2026-11", 7);
            assertEquals(category, data.get("category"));
            assertEquals("2026-11", data.get("month"));
            assertEquals(7, data.get("warningDays"));
            assertFalse(((List<?>) data.get("kpis")).isEmpty(), category + " 应该有指标");
            assertFalse(data.get("alerts") == null, category + " 应有预警列表（可为空）");
        }
    }

    @Test
    @DisplayName("未知分组与非法月份被拒绝")
    void invalidInputRejected() {
        assertThrows(BizException.class, () -> service.overview("unknown", null, 7));
        assertThrows(BizException.class, () -> service.overview("sales", "2026/11", 7));
    }
}
