package com.mes.system;

import com.mes.common.ApiResponse;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/system")
public class SystemRoleController {

    private final SystemRoleService service;

    public SystemRoleController(SystemRoleService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse roles(@RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.page(page, size == null ? 20 : size));
        }
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse create(@Valid @RequestBody SystemRoleService.RoleRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse update(@PathVariable long id,
                              @Valid @RequestBody SystemRoleService.RoleRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse permissions() {
        return ApiResponse.ok(service.permissionCatalog());
    }
}
