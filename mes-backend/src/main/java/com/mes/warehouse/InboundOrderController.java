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
@RequestMapping("/api/inbound-orders")
@PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE','QC')")
public class InboundOrderController {

    private final InboundOrderService service;

    public InboundOrderController(InboundOrderService service) {
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

    @GetMapping("/options")
    public ApiResponse options(@RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.options(keyword, status, limit));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('warehouse:inbound') and hasAuthority('warehouse:inbound_free')")
    public ApiResponse create(@RequestBody InboundOrderService.InboundRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:inbound') and hasAuthority('warehouse:inbound_free')")
    public ApiResponse update(@PathVariable long id, @RequestBody InboundOrderService.InboundRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('warehouse:inbound')")
    public ApiResponse submit(@PathVariable long id) {
        service.submit(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('warehouse:inbound')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:inbound')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
