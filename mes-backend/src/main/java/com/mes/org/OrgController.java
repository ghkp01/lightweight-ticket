package com.mes.org;

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

/**
 * 组织架构接口：工厂 / 车间 / 班组 / 职能部门。
 */
@RestController
@RequestMapping("/api/orgs")
public class OrgController {

    private final OrgService service;

    public OrgController(OrgService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(service.list(keyword, type, enabled));
    }

    @GetMapping("/tree")
    public ApiResponse tree() {
        return ApiResponse.ok(service.tree());
    }

    @GetMapping("/options")
    public ApiResponse options() {
        return ApiResponse.ok(service.options());
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/{id}/members")
    public ApiResponse members(@PathVariable long id,
                               @RequestParam(defaultValue = "false") boolean includeChildren) {
        return ApiResponse.ok(service.members(id, includeChildren));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('org:save')")
    public ApiResponse create(@Valid @RequestBody OrgService.OrgRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('org:save')")
    public ApiResponse update(@PathVariable long id,
                              @Valid @RequestBody OrgService.OrgRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('org:save')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
