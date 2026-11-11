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
 * 采购订单：管理员、计划与仓库可用；确认与编辑走独立权限，收货仓库也能做。
 */
@RestController
@RequestMapping("/api/purchase-orders")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER', 'WAREHOUSE')")
public class PurchaseOrderController {

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Long supplierId,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, supplierId, page, size));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/last-price")
    public ApiResponse lastPrice(@RequestParam long supplierId, @RequestParam long productId) {
        return ApiResponse.ok(service.lastPrice(supplierId, productId));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:create')")
    public ApiResponse create(@RequestBody PurchaseOrderService.OrderRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:edit')")
    public ApiResponse update(@PathVariable long id, @RequestBody PurchaseOrderService.OrderRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('purchase:confirm')")
    public ApiResponse confirm(@PathVariable long id) {
        service.confirm(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchase:cancel')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('purchase:receive')")
    public ApiResponse receive(@PathVariable long id,
                               @RequestBody(required = false) PurchaseOrderService.ReceiveRequest request) {
        return ApiResponse.ok(service.receive(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:delete')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
