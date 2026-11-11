package com.mes.returns;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    private final ReturnService service;

    public ReturnController(ReturnService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('returns:create')")
    public ApiResponse create(@RequestBody ReturnService.ReturnRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/process")
    @PreAuthorize("hasAuthority('returns:process')")
    public ApiResponse process(@PathVariable long id) {
        service.process(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('returns:close')")
    public ApiResponse close(@PathVariable long id) {
        service.close(id);
        return ApiResponse.ok();
    }
}
