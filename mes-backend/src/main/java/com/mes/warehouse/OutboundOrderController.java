package com.mes.warehouse;

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

@RestController
@RequestMapping("/api/outbound-orders")
@PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE')")
public class OutboundOrderController {

    private final OutboundOrderService service;

    public OutboundOrderController(OutboundOrderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String type,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, type, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('warehouse:outbound')")
    public ApiResponse create(@RequestBody OutboundOrderService.OutboundRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:outbound')")
    public ApiResponse update(@PathVariable long id, @RequestBody OutboundOrderService.OutboundRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('warehouse:outbound')")
    public ApiResponse complete(@PathVariable long id) {
        service.complete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('warehouse:outbound')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:outbound')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
