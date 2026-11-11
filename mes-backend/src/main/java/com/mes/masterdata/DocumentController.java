package com.mes.masterdata;

import com.mes.common.ApiResponse;
import jakarta.validation.Valid;
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
 * 产品文档接口：SOP / SIP / 图纸。
 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/products/{productId}/documents")
    public ApiResponse list(@PathVariable long productId,
                            @RequestParam(required = false) String docType) {
        return ApiResponse.ok(service.list(productId, docType));
    }

    @PostMapping("/products/{productId}/documents")
    @PreAuthorize("hasAuthority('document:save')")
    public ApiResponse create(@PathVariable long productId,
                              @Valid @RequestBody DocumentService.DocumentRequest request) {
        return ApiResponse.ok(service.create(productId, request));
    }

    @PutMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('document:save')")
    public ApiResponse update(@PathVariable long id,
                              @Valid @RequestBody DocumentService.DocumentRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('document:save')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** 工单视角：产品级文档 + 每道工序的指导书，扫码报工与质检都能看 */
    @GetMapping("/work-orders/{id}/documents")
    public ApiResponse forWorkOrder(@PathVariable long id) {
        return ApiResponse.ok(service.forWorkOrder(id));
    }
}
