package com.mes.job;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台定时任务：任务清单、执行记录、手工执行。
 */
@RestController
@RequestMapping("/api/system/jobs")
@PreAuthorize("hasAnyRole('ADMIN')")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse jobs() {
        return ApiResponse.ok(service.jobs());
    }

    @GetMapping("/logs")
    public ApiResponse logs(@RequestParam(required = false) String code,
                            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.logs(code, limit));
    }

    @PostMapping("/{code}/run")
    public ApiResponse run(@PathVariable String code) {
        return ApiResponse.ok(service.run(code, "MANUAL"));
    }
}
