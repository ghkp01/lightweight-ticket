package com.mes.quality;

import com.mes.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 不良品处置接口。
 */
@RestController
@RequestMapping("/api/defects")
public class DefectController {

    private final DefectService service;

    public DefectController(DefectService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String status,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "100") int limit,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.page(status, keyword, page, size));
        }
        return ApiResponse.ok(service.list(status, keyword, limit));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    @PostMapping("/{id}/dispose")
    @PreAuthorize("hasAuthority('defect:handle')")
    public ApiResponse dispose(@PathVariable long id,
                               @Valid @RequestBody DefectService.DisposeRequest request) {
        return ApiResponse.ok(service.dispose(id, request));
    }
}
