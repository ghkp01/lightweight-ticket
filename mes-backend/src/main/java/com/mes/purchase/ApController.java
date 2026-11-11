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
 * 发票与应付：管理员与计划使用，发票登记、三单匹配、付款分开授权。
 */
@RestController
@RequestMapping("/api/ap")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
public class ApController {

    private final ApService service;

    public ApController(ApService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/accruals")
    public ApiResponse accruals(@RequestParam(required = false) Long supplierId,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.accruals(supplierId, status, keyword, page, size));
    }

    @GetMapping("/invoiceable-lines")
    public ApiResponse invoiceableLines(@RequestParam Long supplierId) {
        return ApiResponse.ok(service.invoiceableLines(supplierId));
    }

    @GetMapping("/invoices")
    public ApiResponse invoices(@RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) Long supplierId,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.invoices(keyword, status, supplierId, page, size));
    }

    @GetMapping("/invoices/{id}")
    public ApiResponse invoiceDetail(@PathVariable long id) {
        return ApiResponse.ok(service.invoiceDetail(id));
    }

    @PostMapping("/invoices")
    @PreAuthorize("hasAuthority('ap:invoice')")
    public ApiResponse createInvoice(@RequestBody ApService.InvoiceRequest request) {
        return ApiResponse.ok(service.createInvoice(request));
    }

    @PutMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('ap:invoice')")
    public ApiResponse updateInvoice(@PathVariable long id, @RequestBody ApService.InvoiceRequest request) {
        service.updateInvoice(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('ap:invoice')")
    public ApiResponse deleteInvoice(@PathVariable long id) {
        service.deleteInvoice(id);
        return ApiResponse.ok();
    }

    @PostMapping("/invoices/{id}/match")
    @PreAuthorize("hasAuthority('ap:match')")
    public ApiResponse match(@PathVariable long id,
                             @RequestBody(required = false) ApService.MatchRequest request) {
        return ApiResponse.ok(service.match(id, request));
    }

    @PostMapping("/invoices/{id}/cancel")
    @PreAuthorize("hasAuthority('ap:invoice')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    @PostMapping("/invoices/{id}/payments")
    @PreAuthorize("hasAuthority('ap:payment')")
    public ApiResponse pay(@PathVariable long id, @RequestBody ApService.PaymentRequest request) {
        return ApiResponse.ok(service.pay(id, request));
    }

    @PostMapping("/credit-invoices")
    @PreAuthorize("hasAuthority('ap:credit')")
    public ApiResponse createCredit(@RequestBody ApService.CreditRequest request) {
        return ApiResponse.ok(service.createCredit(request));
    }

    @GetMapping("/credit-candidates")
    public ApiResponse creditCandidates(@RequestParam(required = false) Long supplierId) {
        return ApiResponse.ok(service.creditCandidates(supplierId));
    }

    @PostMapping("/credit-invoices/{id}/match")
    @PreAuthorize("hasAuthority('ap:credit')")
    public ApiResponse matchCredit(@PathVariable long id) {
        return ApiResponse.ok(service.matchCredit(id));
    }

    @PostMapping("/invoices/{id}/refunds")
    @PreAuthorize("hasAuthority('ap:refund')")
    public ApiResponse refund(@PathVariable long id, @RequestBody ApService.RefundRequest request) {
        return ApiResponse.ok(service.refund(id, request));
    }

    @GetMapping("/reconciliation")
    public ApiResponse reconciliation(@RequestParam(required = false) Long supplierId) {
        return ApiResponse.ok(service.reconciliation(supplierId));
    }

    @GetMapping("/aging")
    public ApiResponse aging() {
        return ApiResponse.ok(service.aging());
    }
}
