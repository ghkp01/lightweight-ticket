package com.mes.finance;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业所得税季度预缴：生成、申报（计提）、缴款。
 */
@RestController
@RequestMapping("/api/finance/tax-prepayments")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public class TaxPrepaymentController {

    private final TaxPrepaymentService service;

    public TaxPrepaymentController(TaxPrepaymentService service) {
        this.service = service;
    }

    public record PayRequest(String remark) {
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) Integer year,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(year, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse generate(@RequestBody TaxPrepaymentService.PrepayRequest request) {
        return ApiResponse.ok(service.generate(request));
    }

    @PostMapping("/{id}/file")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse file(@PathVariable long id) {
        return ApiResponse.ok(service.file(id));
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse pay(@PathVariable long id,
                           @RequestBody(required = false) PayRequest request) {
        return ApiResponse.ok(service.pay(id, request == null ? null : request.remark()));
    }
}
