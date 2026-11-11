package com.mes.push;

import com.mes.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 移动端推送设备登记：App 启动拿到厂商 registrationId 后调这里登记，
 * 之后的站内通知会同时尝试推到手机（是否真的推取决于 mes.push.provider）。
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService service;

    public PushController(PushService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public ApiResponse config() {
        return ApiResponse.ok(service.config());
    }

    @GetMapping("/devices")
    public ApiResponse devices() {
        return ApiResponse.ok(service.myDevices());
    }

    @PostMapping("/devices")
    public ApiResponse register(@RequestBody PushService.RegisterRequest request) {
        return ApiResponse.ok(service.register(request));
    }

    @DeleteMapping("/devices")
    public ApiResponse unregister(@RequestParam String token) {
        service.unregister(token);
        return ApiResponse.ok();
    }
}
