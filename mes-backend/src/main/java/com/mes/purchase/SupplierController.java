package com.mes.purchase;

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
 * 供应商档案：读只要登录，写要 supplier:save。
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) String enabled,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.page(keyword, category, enabled, page, size));
        }
        return ApiResponse.ok(service.list(keyword, category, enabled));
    }

    @GetMapping("/options")
    public ApiResponse options(@RequestParam(required = false) String keyword,
                               @RequestParam(defaultValue = "0") int limit) {
        return ApiResponse.ok(service.options(keyword, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('supplier:save')")
    public ApiResponse create(@RequestBody SupplierService.SupplierRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('supplier:save')")
    public ApiResponse update(@PathVariable long id, @RequestBody SupplierService.SupplierRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('supplier:save')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
