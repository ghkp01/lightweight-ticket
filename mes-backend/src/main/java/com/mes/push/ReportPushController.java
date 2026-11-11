package com.mes.push;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 经营日报推送设置：大屏右上角「配置」里维护，也可以直接走接口。
 */
@RestController
@RequestMapping("/api/report-push")
public class ReportPushController {

    private final ReportPushService service;

    public ReportPushController(ReportPushService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('dashboard')")
    public ApiResponse status() {
        return ApiResponse.ok(service.status());
    }

    @PutMapping("/config")
    @PreAuthorize("hasAuthority('system_config')")
    public ApiResponse save(@RequestBody ReportPushService.PushConfigRequest request) {
        service.save(request);
        return ApiResponse.ok(service.status());
    }

    @PostMapping("/send")
    @PreAuthorize("hasAuthority('dashboard')")
    public ApiResponse send() {
        return ApiResponse.ok(service.sendNow());
    }
}
