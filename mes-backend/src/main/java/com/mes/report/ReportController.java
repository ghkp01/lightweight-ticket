package com.mes.report;

import com.mes.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产报表接口：产量、计件工资、交期预警，登录后即可查询。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/production")
    public ApiResponse production(@RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.production(from, to));
    }

    @GetMapping("/payroll")
    public ApiResponse payroll(@RequestParam(required = false) String month) {
        return ApiResponse.ok(service.payroll(month));
    }

    @GetMapping("/delivery")
    public ApiResponse delivery() {
        return ApiResponse.ok(service.delivery());
    }
}
