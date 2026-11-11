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

import java.util.Map;

/**
 * 企业所得税年度汇算清缴：生成、申报（计提）、补税/退税。
 */
@RestController
@RequestMapping("/api/finance/income-tax")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public class IncomeTaxController {

    private final IncomeTaxService service;
    private final TaxAdjustmentService adjustmentService;
    private final TaxBranchService branchService;

    public IncomeTaxController(IncomeTaxService service, TaxAdjustmentService adjustmentService,
                               TaxBranchService branchService) {
        this.service = service;
        this.adjustmentService = adjustmentService;
        this.branchService = branchService;
    }

    public record PayRequest(String remark) {
    }

    @GetMapping("/adjustment-preview")
    public ApiResponse adjustmentPreview(@RequestParam int year) {
        return ApiResponse.ok(adjustmentService.preview(year));
    }

    @GetMapping("/adjustment-ledger")
    public ApiResponse adjustmentLedger(@RequestParam(required = false) String ruleCode,
                                        @RequestParam(required = false) Integer yearFrom,
                                        @RequestParam(required = false) Integer yearTo,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.adjustmentLedger(ruleCode, yearFrom, yearTo, page, size));
    }

    @GetMapping("/branches")
    public ApiResponse branches() {
        return ApiResponse.ok(branchService.list());
    }

    @PostMapping("/branches")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse saveBranch(@RequestBody TaxBranchService.BranchRequest request) {
        return ApiResponse.ok(branchService.save(request));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/branches/{id}")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse deleteBranch(@PathVariable long id) {
        branchService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) Integer year,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(year, status, page, size));
    }

    @GetMapping("/loss-carryforward")
    public ApiResponse lossCarryforward(@RequestParam int year) {
        return ApiResponse.ok(service.lossCarryforward(year));
    }

    /** 本年季度预缴已申报金额，用于汇算表自动带出「已预缴税额」 */
    @GetMapping("/prepaid-tax")
    public ApiResponse prepaidTax(@RequestParam int year) {
        return ApiResponse.ok(Map.of("year", year, "prepaidTax", service.autoPrepaid(year)));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse generate(@RequestBody IncomeTaxService.SettleRequest request) {
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
