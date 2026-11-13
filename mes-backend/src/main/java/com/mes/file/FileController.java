package com.mes.file;

import com.mes.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 统一附件接口：上传、台账查询、下载、删除都在这里，业务单据只传 bizType / bizId / bizNo。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService service;

    public FileController(FileService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('file:upload')")
    public ApiResponse upload(@RequestParam(value = "file", required = false) MultipartFile file,
                              @RequestParam(required = false) String bizType,
                              @RequestParam(required = false) Long bizId,
                              @RequestParam(required = false) String bizNo) {
        return ApiResponse.ok(service.upload(file, bizType, bizId, bizNo));
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String bizType,
                            @RequestParam(required = false) Long bizId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String storageType,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) Integer limit,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.page(bizType, bizId, keyword, storageType, from, to, page, size));
        }
        return ApiResponse.ok(service.list(bizType, bizId, keyword, storageType, from, to, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        FileService.Download download = service.download(id);
        String encoded = URLEncoder.encode(download.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.contentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0")
                .contentType(mediaType)
                .contentLength(download.data().length)
                .body(download.data());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('file:delete')")
    public ApiResponse delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/storage")
    public ApiResponse storage() {
        return ApiResponse.ok(service.storageStatus());
    }

    @PostMapping("/storage/test")
    @PreAuthorize("hasAuthority('config:save')")
    public ApiResponse testStorage() {
        return ApiResponse.ok(service.testStorage());
    }
}
