package com.mes.report;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 月结报表：库存月结与往来月结。
 */
@RestController
@RequestMapping("/api/month-close")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
public class MonthCloseController {

    private final MonthCloseService service;

    public MonthCloseController(MonthCloseService service) {
        this.service = service;
    }

    @GetMapping("/months")
    public ApiResponse months(@RequestParam(defaultValue = "12") int limit) {
        return ApiResponse.ok(service.monthOptions(limit));
    }

    @GetMapping("/stock")
    public ApiResponse stock(@RequestParam(required = false) String month) {
        return ApiResponse.ok(service.stockClose(month));
    }

    @GetMapping("/settlement")
    public ApiResponse settlement(@RequestParam(required = false) String month) {
        return ApiResponse.ok(service.settlement(month));
    }
}
