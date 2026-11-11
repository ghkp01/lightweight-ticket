package com.mes.sales;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/sales-orders")
public class SalesController {

    private final SalesService service;

    public SalesController(SalesService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String customFieldKey,
                            @RequestParam(required = false) String customFieldValue,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size,
                customFieldKey, customFieldValue));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sales:create')")
    public ApiResponse create(@RequestBody SalesService.OrderRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sales:update')")
    public ApiResponse update(@PathVariable long id, @RequestBody SalesService.OrderRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('sales:confirm')")
    public ApiResponse confirm(@PathVariable long id) {
        service.confirm(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('sales:cancel')")
    public ApiResponse cancel(@PathVariable long id,
                              @RequestParam(required = false) String reason) {
        service.cancel(id, reason);
        return ApiResponse.ok();
    }

    /** 明细级撤单预检：能不能撤、卡在哪、还要退哪些料；传 qty 时按部分取消算 */
    @GetMapping("/{id}/items/{itemId}/cancel-preview")
    @PreAuthorize("hasAuthority('sales:cancel')")
    public ApiResponse cancelItemPreview(@PathVariable long id, @PathVariable long itemId,
                                         @RequestParam(required = false) BigDecimal qty) {
        return ApiResponse.ok(service.cancelItemPreview(id, itemId, qty));
    }

    /** 明细级撤单：整行取消连带作废工单；只砍一部分时原行减量、工单缩量 */
    @PostMapping("/{id}/items/{itemId}/cancel")
    @PreAuthorize("hasAuthority('sales:cancel')")
    public ApiResponse cancelItem(@PathVariable long id, @PathVariable long itemId,
                                  @RequestParam(required = false) BigDecimal qty,
                                  @RequestParam(required = false) String reason) {
        return ApiResponse.ok(service.cancelItem(id, itemId, qty, reason));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('sales:delete')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
