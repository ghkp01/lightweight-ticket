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
 * 税务申报：生成申报表、申报结转、登记缴款。
 */
@RestController
@RequestMapping("/api/finance/tax-filings")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public class TaxFilingController {

    private final TaxFilingService service;

    public TaxFilingController(TaxFilingService service) {
        this.service = service;
    }

    public record PayRequest(String remark) {
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String period,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(period, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse generate(@RequestBody TaxFilingService.GenerateRequest request) {
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
