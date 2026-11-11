package com.mes.purchase;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商价格库：询价选定后沉淀，采购下单时可参考。
 */
@RestController
@RequestMapping("/api/supplier-prices")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER', 'WAREHOUSE', 'QC')")
public class SupplierPriceController {

    private final RfqService service;

    public SupplierPriceController(RfqService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) Long supplierId,
                            @RequestParam(required = false) Long productId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "false") boolean validOnly,
                            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.prices(supplierId, productId, keyword, validOnly, limit));
    }
}
