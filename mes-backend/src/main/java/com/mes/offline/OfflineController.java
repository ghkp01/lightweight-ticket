package com.mes.offline;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 离线队列辅助接口：
 * - /status 移动端启动时读一次，判断要不要本地排队；
 * - /recent 运维排查「这条报工到底传上来没有」。
 */
@RestController
@RequestMapping("/api/offline")
public class OfflineController {

    private final OfflineSyncService service;

    public OfflineController(OfflineSyncService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ApiResponse status() {
        return ApiResponse.ok(service.status());
    }

    @GetMapping("/recent")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ApiResponse recent(@RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.recent(limit == null ? 50 : limit));
    }
}
