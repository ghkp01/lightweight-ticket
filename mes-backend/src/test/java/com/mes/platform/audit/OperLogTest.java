package com.mes.platform.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作日志：模块归类、失败也能查、关键字与操作人过滤、下拉选项统计。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OperLogTest {

    @Autowired
    private OperLogService operLogService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from oper_log where summary like '测试日志%'");
    }

    @Test
    @DisplayName("按模块、关键字、结果过滤日志")
    void listFiltersByModuleKeywordAndResult() {
        write("附件", "上传", "测试日志：上传附件检验报告.pdf", true, "tester-a");
        write("附件", "删除", "测试日志：删除附件旧照片.jpg", true, "tester-b");
        write("质量检验", "新增", "测试日志：检验单 QI202610220001 判定 FAIL", false, "tester-a");

        List<Map<String, Object>> attachments = operLogService.list("附件", null, null, null, null, null, 20);
        assertEquals(2, attachments.size());
        assertTrue(attachments.stream().anyMatch(row -> "上传".equals(row.get("action"))));
        assertTrue(attachments.stream().anyMatch(row -> "删除".equals(row.get("action"))));

        List<Map<String, Object>> keyword = operLogService.list(null, null, "判定 FAIL", null, null, null, 20);
        assertEquals(1, keyword.size());
        assertEquals("质量检验", keyword.get(0).get("module"));

        List<Map<String, Object>> failed = operLogService.list(null, null, "测试日志", "false", null, null, 20);
        assertEquals(1, failed.size());
        assertEquals(false, failed.get(0).get("success"));

        List<Map<String, Object>> byOperator = operLogService.list(null, "tester-a", "测试日志", null, null, null, 20);
        assertEquals(2, byOperator.size());
    }

    @Test
    @DisplayName("下拉选项带模块、操作人与失败统计")
    void optionsSummarizeLogs() {
        write("附件", "上传", "测试日志：上传附件 a.png", true, "tester-a");
        write("附件", "删除", "测试日志：删除附件 b.png", false, "tester-a");

        Map<String, Object> options = operLogService.options();
        assertTrue(((List<?>) options.get("modules")).contains("附件"));
        assertTrue(((List<?>) options.get("operators")).contains("tester-a"));
        assertTrue(((Number) options.get("total")).longValue() >= 2);
        assertTrue(((Number) options.get("failed")).longValue() >= 1);
    }

    @Test
    @DisplayName("没有请求上下文时 touch 静默跳过，不影响业务")
    void touchWithoutRequestIsNoop() {
        assertDoesNotThrow(() -> operLogService.touch("FILE", 1L, "F1", "测试日志：无请求上下文"));
        assertDoesNotThrow(() -> operLogService.action("上传"));
        assertTrue(operLogService.list(null, null, "无请求上下文", null, null, null, 10).isEmpty());
    }

    @Test
    @DisplayName("路径归类：系统配置、附件下载、检验单分别落在对应模块")
    void moduleMappingIsReadable() {
        assertEquals("系统配置", OperLogInterceptor.moduleOf("/api/system/configs/storage.provider"));
        assertEquals("操作日志", OperLogInterceptor.moduleOf("/api/system/logs"));
        assertEquals("附件", OperLogInterceptor.moduleOf("/api/files/12/download"));
        assertEquals("质量检验", OperLogInterceptor.moduleOf("/api/inspections/3"));
        assertEquals("不良处置", OperLogInterceptor.moduleOf("/api/defects/5/dispose"));
        assertEquals("其他", OperLogInterceptor.moduleOf("/api/unknown"));
        assertEquals("新增", OperLogInterceptor.actionOf("POST"));
        assertEquals("修改", OperLogInterceptor.actionOf("PUT"));
        assertEquals("删除", OperLogInterceptor.actionOf("DELETE"));
    }

    private void write(String module, String action, String summary, boolean success, String operator) {
        operLogService.write(new OperLogService.Entry(module, action, "FILE", 1L, "F202610220001",
                summary, null, operator, "ADMIN", "127.0.0.1", "JUnit", "POST",
                "/api/files", success, success ? null : "测试失败原因", 12));
    }
}
