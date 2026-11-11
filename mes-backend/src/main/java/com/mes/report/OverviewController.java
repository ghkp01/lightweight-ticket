package com.mes.report;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜单分组工作台：一个分组一屏指标 + 预警，支持按月份与预警天数查询。
 */
@RestController
@RequestMapping("/api/overview")
@PreAuthorize("hasAuthority('dashboard')")
public class OverviewController {

    private final OverviewService service;

    public OverviewController(OverviewService service) {
        this.service = service;
    }

    @GetMapping("/{category}")
    public ApiResponse overview(@PathVariable String category,
                                @RequestParam(required = false) String month,
                                @RequestParam(defaultValue = "7") int warningDays) {
        return ApiResponse.ok(service.overview(category, month, warningDays));
    }
}
