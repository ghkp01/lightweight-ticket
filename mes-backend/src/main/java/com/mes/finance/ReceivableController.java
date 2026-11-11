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

import java.util.List;

/**
 * 应收：销售发票、发货单开票、收款核销、账龄。
 */
@RestController
@RequestMapping("/api/finance/receivables")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public class ReceivableController {

    private final ReceivableService service;

    public ReceivableController(ReceivableService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/invoices")
    public ApiResponse invoices(@RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) Long customerId,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.invoices(keyword, status, customerId, page, size));
    }

    @GetMapping("/invoices/{id}")
    public ApiResponse invoiceDetail(@PathVariable long id) {
        return ApiResponse.ok(service.invoiceDetail(id));
    }

    @PostMapping("/invoices")
    @PreAuthorize("hasAuthority('finance:invoice')")
    public ApiResponse createInvoice(@RequestBody ReceivableService.InvoiceRequest request) {
        return ApiResponse.ok(service.createInvoice(request));
    }

    @PostMapping("/invoices/from-shipment/{shipmentId}")
    @PreAuthorize("hasAuthority('finance:invoice')")
    public ApiResponse invoiceFromShipment(@PathVariable long shipmentId,
                                           @RequestBody(required = false)
                                           ReceivableService.ShipmentInvoiceRequest request) {
        return ApiResponse.ok(service.invoiceFromShipment(shipmentId, request));
    }

    @PostMapping("/invoices/{id}/cancel")
    @PreAuthorize("hasAuthority('finance:invoice')")
    public ApiResponse cancelInvoice(@PathVariable long id) {
        service.cancelInvoice(id);
        return ApiResponse.ok();
    }

    @GetMapping("/invoiceable-shipments")
    public ApiResponse invoiceableShipments(@RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.invoiceableShipments(keyword, page, size));
    }

    @GetMapping("/receipts")
    public ApiResponse receipts(@RequestParam(required = false) String keyword,
                                @RequestParam(required = false) Long customerId,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.receipts(keyword, customerId, page, size));
    }

    @PostMapping("/receipts")
    @PreAuthorize("hasAuthority('finance:receipt')")
    public ApiResponse createReceipt(@RequestBody ReceivableService.ReceiptRequest request) {
        return ApiResponse.ok(service.createReceipt(request));
    }

    @PostMapping("/receipts/{id}/cancel")
    @PreAuthorize("hasAuthority('finance:receipt')")
    public ApiResponse cancelReceipt(@PathVariable long id) {
        service.cancelReceipt(id);
        return ApiResponse.ok();
    }

    public record AdvanceApplyRequest(List<ReceivableService.ApplyLine> applies) {
    }

    @PostMapping("/receipts/{id}/apply")
    @PreAuthorize("hasAuthority('finance:receipt')")
    public ApiResponse applyAdvance(@PathVariable long id,
                                    @RequestBody AdvanceApplyRequest request) {
        return ApiResponse.ok(service.applyAdvance(id, request.applies()));
    }

    @GetMapping("/aging")
    public ApiResponse aging() {
        return ApiResponse.ok(service.aging());
    }
}
