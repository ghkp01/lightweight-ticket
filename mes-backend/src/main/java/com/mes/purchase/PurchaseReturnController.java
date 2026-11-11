package com.mes.purchase;

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
@RequestMapping("/api/purchase-returns")
@PreAuthorize("hasAnyRole('ADMIN','PLANNER','WAREHOUSE')")
public class PurchaseReturnController {

    private final PurchaseReturnService service;

    public PurchaseReturnController(PurchaseReturnService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @GetMapping("/returnable-inbounds")
    public ApiResponse returnableInbounds(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.returnableInbounds(keyword));
    }

    @GetMapping("/inbound-orders/{inboundOrderId}/returnable")
    public ApiResponse returnable(@PathVariable long inboundOrderId) {
        return ApiResponse.ok(service.returnable(inboundOrderId));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:return')")
    public ApiResponse create(@RequestBody PurchaseReturnService.ReturnRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('purchase:return')")
    public ApiResponse complete(@PathVariable long id) {
        return ApiResponse.ok(service.complete(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchase:return')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:return')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
