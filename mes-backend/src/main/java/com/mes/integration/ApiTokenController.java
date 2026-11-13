package com.mes.integration;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对外接口令牌管理：只有管理员能发令牌与停用。
 */
@RestController
@RequestMapping("/api/system/api-tokens")
@PreAuthorize("hasAnyRole('ADMIN')")
public class ApiTokenController {

    private final ApiTokenService service;

    public ApiTokenController(ApiTokenService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.page(page, size == null ? 20 : size));
        }
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/scopes")
    public ApiResponse scopes() {
        return ApiResponse.ok(ApiTokenService.SCOPES);
    }

    @PostMapping
    public ApiResponse create(@RequestBody ApiTokenService.CreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse toggle(@PathVariable long id, @RequestParam boolean enabled) {
        service.toggle(id, enabled);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok(Map.of("deleted", true));
    }
}
