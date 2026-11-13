package com.mes.platform.customfield;

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
 * 低代码自定义字段：字段定义维护 + 各业务表单读取可用的字段。
 */
@RestController
@RequestMapping("/api/custom-fields")
public class CustomFieldController {

    private final CustomFieldService service;

    public CustomFieldController(CustomFieldService service) {
        this.service = service;
    }

    /** 支持自定义字段的实体清单 */
    @GetMapping("/entities")
    public ApiResponse entities() {
        return ApiResponse.ok(service.entities());
    }

    /** 作用工厂候选（只列启用中的工厂） */
    @GetMapping("/factories")
    public ApiResponse factories() {
        return ApiResponse.ok(service.factories());
    }

    /**
     * 业务表单用：只拿当前工厂、当前角色可见且标了 editable 的字段。
     * 设计器要看全部定义请走 {@link #definitions(String)}，那里有权限校验。
     */
    @GetMapping
    public ApiResponse list(@RequestParam String entityType) {
        return ApiResponse.ok(service.formDefinitions(entityType));
    }

    /** 设计器用：全部字段（含停用、所有工厂），需要字段管理权限 */
    @GetMapping("/definitions")
    @PreAuthorize("hasAuthority('system_custom_field')")
    public ApiResponse definitions(@RequestParam String entityType,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.definitionsPage(entityType, true, page, size == null ? 20 : size));
        }
        return ApiResponse.ok(service.definitions(entityType, true));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system_custom_field')")
    public ApiResponse create(@RequestBody CustomFieldService.FieldRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system_custom_field')")
    public ApiResponse update(@PathVariable long id,
                              @RequestBody CustomFieldService.FieldRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** 停用字段，历史值保留 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system_custom_field')")
    public ApiResponse disable(@PathVariable long id) {
        service.disable(id);
        return ApiResponse.ok();
    }
}
