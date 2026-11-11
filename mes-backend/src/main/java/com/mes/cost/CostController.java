package com.mes.cost;

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
 * 成本核算：工单成本、成本构成、产品库存成本。
 * 成本属于经营数据，只对管理员与计划开放。
 */
@RestController
@RequestMapping("/api/cost")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
public class CostController {

    private final CostService service;

    public CostController(CostService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse summary(@RequestParam(required = false) String from,
                               @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.summary(from, to));
    }

    @GetMapping("/work-orders")
    public ApiResponse workOrders(@RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.workOrders(keyword, status, from, to, page, size));
    }

    @GetMapping("/work-orders/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/work-orders/{id}/recalc")
    public ApiResponse recalc(@PathVariable long id) {
        return ApiResponse.ok(service.recalc(id));
    }

    @PostMapping("/recalc")
    public ApiResponse recalcAll(@RequestBody(required = false) RecalcRequest request) {
        String status = request == null ? null : request.status();
        return ApiResponse.ok(java.util.Map.of("count", service.recalcAll(status)));
    }

    /** 批量重算的条件：status 为空时只补算还没有成本快照的工单 */
    public record RecalcRequest(String status) {
    }

    @GetMapping("/products")
    public ApiResponse products(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.products(keyword));
    }
}
