package com.mes.purchase;

import com.mes.common.ApiResponse;
import com.mes.purchase.api.RequisitionDraft;
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
 * 采购申请：计划、仓库、质检可以提，审批与转采购单按权限区分。
 */
@RestController
@RequestMapping("/api/purchase-requisitions")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER', 'WAREHOUSE', 'QC')")
public class RequisitionController {

    private final RequisitionService service;

    public RequisitionController(RequisitionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String sourceType,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, sourceType, page, size));
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
    @PreAuthorize("hasAuthority('purchase:request')")
    public ApiResponse create(@RequestBody RequisitionDraft request) {
        return ApiResponse.ok(service.create(request).id());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:request')")
    public ApiResponse update(@PathVariable long id, @RequestBody RequisitionDraft request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('purchase:request')")
    public ApiResponse submit(@PathVariable long id) {
        service.submit(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('purchase:approve')")
    public ApiResponse approve(@PathVariable long id) {
        service.approve(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('purchase:approve')")
    public ApiResponse reject(@PathVariable long id,
                              @RequestBody(required = false) RequisitionService.RejectRequest request) {
        service.reject(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchase:request')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:request')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/to-order")
    @PreAuthorize("hasAuthority('purchase:create')")
    public ApiResponse toOrder(@PathVariable long id,
                               @RequestBody RequisitionService.ConvertRequest request) {
        return ApiResponse.ok(service.toOrder(id, request));
    }
}
