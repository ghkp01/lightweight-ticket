package com.mes.finance;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固定资产：卡片、折旧计提、处置。
 */
@RestController
@RequestMapping("/api/finance/fixed-assets")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public class FixedAssetController {

    private final FixedAssetService service;

    public FixedAssetController(FixedAssetService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('finance:asset')")
    public ApiResponse create(@RequestBody FixedAssetService.AssetRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/depreciate")
    @PreAuthorize("hasAuthority('finance:asset:depreciate')")
    public ApiResponse depreciate(@RequestBody FixedAssetService.DepreciateRequest request) {
        return ApiResponse.ok(service.depreciate(request == null ? null : request.period()));
    }

    @GetMapping("/depreciations")
    public ApiResponse depreciations(@RequestParam(required = false) String period,
                                     @RequestParam(required = false) Long assetId,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.depreciations(period, assetId, page, size));
    }

    @PostMapping("/{id}/dispose")
    @PreAuthorize("hasAuthority('finance:asset')")
    public ApiResponse dispose(@PathVariable long id,
                               @RequestBody(required = false) FixedAssetService.DisposeRequest request) {
        return ApiResponse.ok(service.dispose(id, request));
    }
}
