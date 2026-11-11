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

/**
 * 询价比价：计划与仓库可见，发起询价、录入报价、选定报价按权限控制。
 */
@RestController
@RequestMapping("/api/purchase-rfqs")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER', 'WAREHOUSE')")
public class RfqController {

    private final RfqService service;

    public RfqController(RfqService service) {
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

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/from-requisition/{requisitionId}")
    @PreAuthorize("hasAuthority('purchase:rfq')")
    public ApiResponse fromRequisition(@PathVariable long requisitionId,
                                       @RequestBody(required = false) RfqService.CreateRequest request) {
        return ApiResponse.ok(service.createFromRequisition(requisitionId, request));
    }

    @PostMapping("/{id}/quotes")
    @PreAuthorize("hasAuthority('purchase:rfq')")
    public ApiResponse quote(@PathVariable long id, @RequestBody RfqService.QuoteRequest request) {
        return ApiResponse.ok(service.upsertQuote(id, request));
    }

    @DeleteMapping("/{id}/quotes/{quoteId}")
    @PreAuthorize("hasAuthority('purchase:rfq')")
    public ApiResponse deleteQuote(@PathVariable long id, @PathVariable long quoteId) {
        service.deleteQuote(id, quoteId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/select")
    @PreAuthorize("hasAuthority('purchase:select_quote')")
    public ApiResponse select(@PathVariable long id, @RequestBody RfqService.SelectRequest request) {
        return ApiResponse.ok(service.selectQuote(id, request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchase:rfq')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }
}
