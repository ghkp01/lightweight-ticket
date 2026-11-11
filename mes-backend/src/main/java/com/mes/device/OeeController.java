package com.mes.device;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备综合效率 OEE：按工作中心统计可用率、性能与质量。
 */
@RestController
@RequestMapping("/api/oee")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
public class OeeController {

    private final OeeService service;

    public OeeController(OeeService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse calc(@RequestParam(required = false) Long workCenterId,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.calc(new OeeService.OeeQuery(workCenterId, from, to)));
    }
}
