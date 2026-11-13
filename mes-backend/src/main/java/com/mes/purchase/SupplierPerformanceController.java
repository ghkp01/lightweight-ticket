package com.mes.purchase;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商绩效：排行与单供应商明细（准时率 / 合格率 / 退货率 / 价格趋势）。
 */
@RestController
@RequestMapping("/api/supplier-performance")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER', 'WAREHOUSE', 'QC')")
public class SupplierPerformanceController {

    private final SupplierPerformanceService service;

    public SupplierPerformanceController(SupplierPerformanceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse ranking(@RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               @RequestParam(required = false) Long supplierId,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Boolean onlyRisk,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.rankingPage(from, to, supplierId, keyword, onlyRisk, page, size));
        }
        return ApiResponse.ok(service.ranking(from, to, supplierId));
    }

    @GetMapping("/{supplierId}")
    public ApiResponse detail(@PathVariable long supplierId,
                              @RequestParam(required = false) String from,
                              @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.detail(supplierId, from, to));
    }
}
