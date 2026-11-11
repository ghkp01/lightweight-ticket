package com.mes.device;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备采集入口：网关或人工补录都走这里。
 * 网关用集成令牌调用时，权限由令牌对应的账号决定；页面人工补录用当前登录人。
 */
@RestController
@RequestMapping("/api/device/telemetry")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER', 'WAREHOUSE')")
public class DeviceTelemetryController {

    private final DeviceTelemetryService service;

    public DeviceTelemetryController(DeviceTelemetryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) Long workCenterId) {
        return ApiResponse.ok(service.list(from, to, workCenterId));
    }

    @PostMapping
    public ApiResponse record(@RequestBody DeviceTelemetryService.TelemetryRequest request) {
        return ApiResponse.ok(service.record(request));
    }
}
