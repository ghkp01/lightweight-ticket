package com.mes.masterdata;

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

import java.util.List;
import java.util.Map;

/**
 * BOM 管理：版本化的 BOM 头 + 行明细。
 * 读只要登录，写与启用要管理员。
 */
@RestController
@RequestMapping("/api/boms")
public class BomController {

    private final BomService service;

    public BomController(BomService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(keyword, status));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse create(@RequestParam long productId,
                              @RequestBody BomService.BomHeaderRequest request) {
        return ApiResponse.ok(service.create(productId, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse update(@PathVariable long id,
                              @RequestBody BomService.BomHeaderRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse submit(@PathVariable long id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse withdraw(@PathVariable long id) {
        service.withdraw(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/copy")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse copy(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        String version = body == null || body.get("version") == null
                ? null : String.valueOf(body.get("version"));
        return ApiResponse.ok(service.copyVersion(id, version));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/lines")
    public ApiResponse lines(@PathVariable long id) {
        return ApiResponse.ok(service.lines(id));
    }

    @PutMapping("/{id}/lines")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse saveLines(@PathVariable long id,
                                 @RequestBody List<BomService.BomLineRequest> request) {
        service.saveLines(id, request);
        return ApiResponse.ok();
    }
}
