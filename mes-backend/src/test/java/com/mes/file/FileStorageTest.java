package com.mes.file;

import com.mes.common.BizException;
import com.mes.platform.config.ConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一附件：本地存储落盘、台账字段、按业务查、删除、以及切换存储后老文件仍可下载；
 * S3 用一个本地假服务验证 Signature V4 请求头与路径拼装。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FileStorageTest {

    private static final Path ROOT = Paths.get(System.getProperty("user.dir"), "target", "test-attachments");

    @Autowired
    private FileService fileService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer s3Server;
    private final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        jdbc.update("update sys_config set config_value = 'LOCAL' where config_key = 'storage.provider'");
        jdbc.update("update sys_config set config_value = ? where config_key = 'storage.local.root'",
                "target/test-attachments");
        jdbc.update("""
                update sys_config set config_value = '' where config_key in
                ('storage.s3.endpoint', 'storage.s3.access-key', 'storage.s3.secret-key')
                """);
        jdbc.update("update sys_config set config_value = 'mes-test' where config_key = 'storage.s3.bucket'");
        jdbc.update("update sys_config set config_value = 'true' where config_key = 'storage.s3.path-style'");
    }

    @AfterEach
    void tearDown() {
        if (s3Server != null) {
            s3Server.stop(0);
            s3Server = null;
        }
        jdbc.update("update sys_config set config_value = 'LOCAL' where config_key = 'storage.provider'");
    }

    @Test
    @DisplayName("本地上传：落盘、台账字段、下载内容一致")
    void localUploadWritesFileAndLedger() throws Exception {
        byte[] content = "检验报告：外观合格".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "检验报告.txt", "text/plain", content);

        Map<String, Object> record = fileService.upload(file, "inspection", 10L, "QI202610220001");

        assertEquals("LOCAL", record.get("storageType"));
        assertEquals("检验报告.txt", record.get("originalName"));
        assertEquals("txt", record.get("ext"));
        assertEquals("INSPECTION", record.get("bizType"));
        assertEquals("QI202610220001", record.get("bizNo"));
        assertEquals(Hashes.sha256Hex(content), record.get("sha256"));
        String fileNo = String.valueOf(record.get("fileNo"));
        assertTrue(fileNo.startsWith("F"), fileNo);

        Path stored = ROOT.resolve(String.valueOf(record.get("storageKey")));
        assertTrue(Files.exists(stored), "本地文件应已写入 " + stored);

        FileService.Download download = fileService.download(((Number) record.get("id")).longValue());
        assertArrayEquals(content, download.data());
        assertEquals("检验报告.txt", download.fileName());
    }

    @Test
    @DisplayName("按业务查台账：只返回未删除的对应单据附件")
    void listFiltersByBusiness() {
        upload("现场照片.jpg", "image/jpeg", "DEFECT", 7L, "D202610220001");
        upload("返修说明.txt", "text/plain", "DEFECT", 7L, "D202610220001");
        upload("检验记录.txt", "text/plain", "INSPECTION", 8L, "QI202610220002");

        List<Map<String, Object>> defects = fileService.list("DEFECT", 7L, null, null, null, null, 50);
        assertEquals(2, defects.size());
        assertEquals("D202610220001", defects.get(0).get("bizNo"));
        assertTrue(((String) defects.get(0).get("url")).startsWith("/api/files/"));

        List<Map<String, Object>> inspections = fileService.list("INSPECTION", null, null, null, null, null, 50);
        assertEquals(1, inspections.size());
    }

    @Test
    @DisplayName("删除：台账标记删除、文件移除、再下载报 4040")
    void deleteMarksLedgerAndRemovesFile() {
        Map<String, Object> record = upload("待删除.txt", "text/plain", "DEFECT", 9L, "D202610220009");
        long id = ((Number) record.get("id")).longValue();
        Path stored = ROOT.resolve(String.valueOf(record.get("storageKey")));

        fileService.delete(id);

        assertFalse(Files.exists(stored));
        BizException error = assertThrows(BizException.class, () -> fileService.download(id));
        assertEquals(4040, error.getCode());
        assertTrue(fileService.list("DEFECT", 9L, null, null, null, null, 50).isEmpty());
    }

    @Test
    @DisplayName("非法上传：空文件与可执行脚本被拒")
    void rejectEmptyAndBlockedExtension() {
        MockMultipartFile empty = new MockMultipartFile("file", "空.txt", "text/plain", new byte[0]);
        BizException emptyError = assertThrows(BizException.class,
                () -> fileService.upload(empty, "DEFECT", 1L, null));
        assertEquals(1002, emptyError.getCode());

        MockMultipartFile script = new MockMultipartFile("file", "report.exe",
                "application/octet-stream", "MZ".getBytes(StandardCharsets.UTF_8));
        BizException scriptError = assertThrows(BizException.class,
                () -> fileService.upload(script, "DEFECT", 1L, null));
        assertEquals(1002, scriptError.getCode());
        assertTrue(scriptError.getMessage().contains("exe"));
    }

    @Test
    @DisplayName("切换存储方式：老文件仍按原位置读取，新上传走新存储且未配置时明确报错")
    void providerSwitchKeepsOldFileReadable() {
        Map<String, Object> record = upload("切换前.txt", "text/plain", "DEFECT", 11L, null);
        long id = ((Number) record.get("id")).longValue();

        jdbc.update("update sys_config set config_value = 'S3' where config_key = 'storage.provider'");
        assertEquals("S3", storageService.provider());

        FileService.Download download = fileService.download(id);
        assertEquals("切换前.txt", download.fileName());

        BizException error = assertThrows(BizException.class,
                () -> upload("切换后.txt", "text/plain", "DEFECT", 11L, null));
        assertEquals(5011, error.getCode());
        assertTrue(error.getMessage().contains("storage.s3.endpoint"));
    }

    @Test
    @DisplayName("S3 上传：路径按桶拼装、带 Signature V4 头，下载与删除走同一对象 key")
    void s3UploadSignsRequestAndRoundTrips() throws Exception {
        startFakeS3();
        int port = s3Server.getAddress().getPort();
        jdbc.update("update sys_config set config_value = 'S3' where config_key = 'storage.provider'");
        jdbc.update("update sys_config set config_value = ? where config_key = 'storage.s3.endpoint'",
                "http://127.0.0.1:" + port);
        jdbc.update("update sys_config set config_value = 'AKID-TEST' where config_key = 'storage.s3.access-key'");
        jdbc.update("update sys_config set config_value = 'SECRET-TEST' where config_key = 'storage.s3.secret-key'");

        byte[] content = "S3 附件内容".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "s3-evidence.txt", "text/plain", content);
        Map<String, Object> record = fileService.upload(file, "DEFECT", 12L, "D202610220012");

        assertEquals("S3", record.get("storageType"));
        assertEquals("mes-test", record.get("bucket"));
        String edgeKey = String.valueOf(record.get("storageKey"));
        assertEquals("S3", storageService.forType("S3").type());

        Map<String, Object> put = requestOf("PUT");
        assertTrue(String.valueOf(put.get("path")).startsWith("/mes-test/"), String.valueOf(put.get("path")));
        assertTrue(String.valueOf(put.get("auth")).startsWith("AWS4-HMAC-SHA256 Credential=AKID-TEST/"));
        assertTrue(String.valueOf(put.get("auth")).contains("SignedHeaders="));
        assertEquals(Hashes.sha256Hex(content), put.get("hash"));
        assertEquals("S3 附件内容", put.get("body"));
        assertTrue(objects.containsKey("/mes-test/" + edgeKey));

        FileService.Download download = fileService.download(((Number) record.get("id")).longValue());
        assertArrayEquals(content, download.data());
        assertEquals("GET", requests.get(requests.size() - 1).get("method"));

        fileService.delete(((Number) record.get("id")).longValue());
        assertEquals("DELETE", requests.get(requests.size() - 1).get("method"));
        assertFalse(objects.containsKey("/mes-test/" + edgeKey));
    }

    @Test
    @DisplayName("密文配置只写不读：列表显示掩码，回传掩码不覆盖原值")
    void sensitiveConfigIsMasked() {
        configService.update("storage.s3.secret-key", "real-secret");
        Map<String, Object> row = configService.list().stream()
                .filter(item -> "storage.s3.secret-key".equals(item.get("key")))
                .findFirst().orElseThrow();
        assertEquals(ConfigService.MASK, row.get("value"));
        assertEquals(true, row.get("hasValue"));

        configService.update("storage.s3.secret-key", ConfigService.MASK);
        assertEquals("real-secret", configService.stringValue("storage.s3.secret-key", ""));
        assertFalse(configService.map().containsKey("storage.s3.secret-key"));
    }

    private Map<String, Object> upload(String name, String contentType, String bizType, Long bizId, String bizNo) {
        MockMultipartFile file = new MockMultipartFile("file", name, contentType,
                ("内容-" + name).getBytes(StandardCharsets.UTF_8));
        return fileService.upload(file, bizType, bizId, bizNo);
    }

    private Map<String, Object> requestOf(String method) {
        return requests.stream().filter(item -> method.equals(item.get("method"))).findFirst()
                .orElseThrow(() -> new AssertionError("没有捕获到 " + method + " 请求"));
    }

    private void startFakeS3() throws Exception {
        s3Server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s3Server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getRawPath();
            Map<String, Object> captured = new ConcurrentHashMap<>();
            captured.put("method", exchange.getRequestMethod());
            captured.put("path", path);
            captured.put("auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            captured.put("hash", String.valueOf(exchange.getRequestHeaders().getFirst("x-amz-content-sha256")));
            captured.put("amzDate", String.valueOf(exchange.getRequestHeaders().getFirst("x-amz-date")));
            captured.put("body", new String(body, StandardCharsets.UTF_8));
            requests.add(captured);

            int status = 200;
            byte[] response = new byte[0];
            switch (exchange.getRequestMethod()) {
                case "PUT" -> objects.put(path, body);
                case "GET" -> {
                    byte[] data = objects.get(path);
                    if (data == null) {
                        status = 404;
                    } else {
                        response = data;
                    }
                }
                case "DELETE" -> objects.remove(path);
                default -> status = 405;
            }
            exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
            if (response.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            }
            exchange.close();
        });
        s3Server.start();
    }
}
