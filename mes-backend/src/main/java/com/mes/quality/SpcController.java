package com.mes.quality;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPC 与过程能力：控制图、不良率 P 图、Cpk 汇总。
 */
@RestController
@RequestMapping("/api/spc")
@PreAuthorize("hasAnyRole('ADMIN', 'QC', 'PLANNER')")
public class SpcController {

    private final SpcService service;

    public SpcController(SpcService service) {
        this.service = service;
    }

    @GetMapping("/items")
    public ApiResponse items(@RequestParam(required = false) Long productId) {
        return ApiResponse.ok(service.items(productId));
    }

    @GetMapping("/chart")
    public ApiResponse chart(@RequestParam(required = false) Long itemId,
                             @RequestParam(required = false) Long productId,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.chart(itemId, productId, from, to));
    }

    @GetMapping("/p-chart")
    public ApiResponse pChart(@RequestParam(required = false) Long productId,
                              @RequestParam(required = false) String from,
                              @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.pChart(productId, from, to));
    }

    @GetMapping("/capability")
    public ApiResponse capability(@RequestParam(required = false) Long productId) {
        return ApiResponse.ok(service.capability(productId));
    }
}
