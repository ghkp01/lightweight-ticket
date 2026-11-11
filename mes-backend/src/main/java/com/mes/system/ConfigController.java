package com.mes.system;

import com.mes.common.ApiResponse;
import com.mes.platform.config.ConfigService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统配置接口：读只要登录（水印渲染要用），写要 config:save。
 */
@RestController
@RequestMapping("/api/system")
public class ConfigController {

    private final ConfigService service;

    public ConfigController(ConfigService service) {
        this.service = service;
    }

    @GetMapping("/configs")
    public ApiResponse list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/configs/map")
    public ApiResponse map() {
        return ApiResponse.ok(service.map());
    }

    @PutMapping("/configs/{key}")
    @PreAuthorize("hasAuthority('config:save')")
    public ApiResponse update(@PathVariable String key,
                              @RequestBody ConfigService.ConfigRequest request) {
        service.update(key, request.value());
        return ApiResponse.ok();
    }
}
