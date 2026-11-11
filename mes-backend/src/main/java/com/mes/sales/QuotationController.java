package com.mes.sales;

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
 * 报价单：报价 → 技术评审 → 转销售订单。
 */
@RestController
@RequestMapping("/api/sales-quotes")
public class QuotationController {

    private final QuotationService service;

    public QuotationController(QuotationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/reviewers")
    @PreAuthorize("hasAuthority('quotation:submit') or hasAuthority('quotation:review')")
    public ApiResponse reviewers() {
        return ApiResponse.ok(service.reviewers());
    }

    public record ReviewerRequest(long reviewerId) {
    }

    public record TransferRequest(long toReviewerId) {
    }

    @PostMapping("/{id}/reviewers")
    @PreAuthorize("hasAuthority('quotation:submit') or hasAuthority('quotation:review')")
    public ApiResponse addReviewer(@PathVariable long id,
                                   @RequestBody ReviewerRequest request) {
        service.addReviewer(id, request.reviewerId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/reviewers/{reviewerId}")
    @PreAuthorize("hasAuthority('quotation:submit') or hasAuthority('quotation:review')")
    public ApiResponse removeReviewer(@PathVariable long id, @PathVariable long reviewerId) {
        service.removeReviewer(id, reviewerId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reviewers/{reviewerId}/transfer")
    @PreAuthorize("hasAuthority('quotation:submit') or hasAuthority('quotation:review')")
    public ApiResponse transferReviewer(@PathVariable long id, @PathVariable long reviewerId,
                                        @RequestBody TransferRequest request) {
        service.transferReviewer(id, reviewerId, request.toReviewerId());
        return ApiResponse.ok();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('quotation:create')")
    public ApiResponse create(@RequestBody QuotationService.QuoteRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('quotation:update')")
    public ApiResponse update(@PathVariable long id,
                              @RequestBody QuotationService.QuoteRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('quotation:submit')")
    public ApiResponse submit(@PathVariable long id,
                              @RequestBody QuotationService.SubmitRequest request) {
        service.submit(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('quotation:review')")
    public ApiResponse review(@PathVariable long id,
                              @RequestBody QuotationService.ReviewRequest request) {
        service.review(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/convert")
    @PreAuthorize("hasAuthority('quotation:convert')")
    public ApiResponse convert(@PathVariable long id) {
        return ApiResponse.ok(service.convert(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('quotation:cancel')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('quotation:delete')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
