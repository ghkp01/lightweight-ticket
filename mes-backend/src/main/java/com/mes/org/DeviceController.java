package com.mes.org;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备台账接口：设备挂在工作中心下，状态影响可用产能。
 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping("/devices")
    public ApiResponse list(@RequestParam(required = false) Long workCenterId) {
        return ApiResponse.ok(service.list(workCenterId));
    }

    @GetMapping("/devices/options")
    public ApiResponse options(@RequestParam(required = false) String keyword,
                               @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.options(keyword, limit));
    }

    @GetMapping("/work-centers/{centerId}/devices")
    public ApiResponse listByCenter(@PathVariable long centerId) {
        return ApiResponse.ok(service.list(centerId));
    }

    @PostMapping("/work-centers/{centerId}/devices")
    @PreAuthorize("hasAuthority('device:save')")
    public ApiResponse create(@PathVariable long centerId,
                              @RequestBody DeviceService.DeviceRequest request) {
        return ApiResponse.ok(service.create(centerId, request));
    }

    @PutMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('device:save')")
    public ApiResponse update(@PathVariable long id,
                              @RequestBody DeviceService.DeviceRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('device:save')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
