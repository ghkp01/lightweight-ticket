package com.mes.shipping;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipments")
public class ShippingController {

    private final ShippingService service;

    public ShippingController(ShippingService service) {
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
    @PreAuthorize("hasAuthority('shipping:create')")
    public ApiResponse create(@RequestBody ShippingService.ShipmentRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAuthority('shipping:ship')")
    public ApiResponse ship(@PathVariable long id) {
        service.ship(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize("hasAuthority('shipping:sign')")
    public ApiResponse sign(@PathVariable long id) {
        service.sign(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('shipping:delete')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
