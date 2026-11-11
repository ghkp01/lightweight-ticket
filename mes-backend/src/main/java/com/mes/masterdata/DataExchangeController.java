package com.mes.masterdata;

import com.mes.common.ApiResponse;
import com.mes.common.BizException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 基础数据导入导出：模板下载、CSV 导入（可先预览校验）、当前数据导出。
 * 用 CSV 而不是 xlsx，避免额外依赖，Excel 直接能打开。
 */
@RestController
@RequestMapping("/api/data-exchange")
@PreAuthorize("hasAuthority('system_data')")
public class DataExchangeController {

    private final DataExchangeService service;

    public DataExchangeController(DataExchangeService service) {
        this.service = service;
    }

    @GetMapping("/templates/{type}")
    public ResponseEntity<byte[]> template(@PathVariable String type) {
        return csvResponse(service.template(type), fileName(type, "导入模板"));
    }

    @GetMapping("/export/{type}")
    public ResponseEntity<byte[]> export(@PathVariable String type) {
        return csvResponse(service.export(type), fileName(type, "导出"));
    }

    @PostMapping("/import/{type}")
    public ApiResponse importFile(@PathVariable String type,
                                  @RequestParam("file") MultipartFile file,
                                  @RequestParam(defaultValue = "false") boolean preview) {
        if (file == null || file.isEmpty()) {
            throw new BizException(1002, "请选择要导入的 CSV 文件");
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(1002, "读取文件失败：" + e.getMessage());
        }
        return ApiResponse.ok(service.importCsv(type, content, preview));
    }

    /** 行业模板一键初始化：electronics / auto / food */
    @PostMapping("/apply-template/{template}")
    public ApiResponse applyTemplate(@PathVariable String template) {
        return ApiResponse.ok(service.applyTemplate(template));
    }

    private ResponseEntity<byte[]> csvResponse(String content, String fileName) {
        // 带 BOM，Excel 打开不会乱码
        byte[] body = ("\uFEFF" + content).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private String fileName(String type, String action) {
        String label = switch (type) {
            case "product" -> "产品";
            case "customer" -> "客户";
            case "supplier" -> "供应商";
            case "bom" -> "BOM";
            case "routing" -> "工艺路线";
            case "inventory" -> "期初库存";
            default -> type;
        };
        return label + action + ".csv";
    }
}
