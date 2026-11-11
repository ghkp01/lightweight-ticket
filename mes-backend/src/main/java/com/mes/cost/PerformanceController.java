package com.mes.cost;

import com.mes.common.ApiResponse;
import com.mes.common.PageResult;
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
 * 月度绩效核算：生成、录工时、提交、审核。
 * 工资属于经营数据，只对管理员与计划开放（与成本核算同一档权限）。
 */
@RestController
@RequestMapping("/api/performance")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
public class PerformanceController {

    private final PerformanceService service;
    private final WorkHourService workHours;

    public PerformanceController(PerformanceService service, WorkHourService workHours) {
        this.service = service;
        this.workHours = workHours;
    }

    @GetMapping("/sheets")
    public ApiResponse list(@RequestParam(required = false) String period,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        PageResult result = service.list(period, status, keyword, page, size);
        return ApiResponse.ok(result);
    }

    @GetMapping("/sheets/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/summary")
    public ApiResponse summary(@RequestParam(required = false) String period) {
        return ApiResponse.ok(service.summary(period));
    }

    @PostMapping("/generate")
    public ApiResponse generate(@RequestBody(required = false) PeriodRequest request) {
        return ApiResponse.ok(service.generate(request == null ? null : request.period()));
    }

    @PutMapping("/sheets/{id}/minutes")
    public ApiResponse updateMinutes(@PathVariable long id,
                                     @RequestBody PerformanceService.MinutesRequest request) {
        return ApiResponse.ok(service.updateMinutes(id, request));
    }

    @PostMapping("/sheets/{id}/submit")
    public ApiResponse submit(@PathVariable long id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/sheets/{id}/approve")
    public ApiResponse approve(@PathVariable long id) {
        return ApiResponse.ok(service.approve(id));
    }

    @PostMapping("/sheets/{id}/reject")
    public ApiResponse reject(@PathVariable long id,
                              @RequestBody PerformanceService.RejectRequest request) {
        return ApiResponse.ok(service.reject(id, request));
    }

    /** 撤回重算：退回草稿，改完可以重新提交。算错工资不是小事，只给管理员 */
    @PostMapping("/sheets/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse reopen(@PathVariable long id,
                              @RequestBody(required = false) PerformanceService.ReopenRequest request) {
        return ApiResponse.ok(service.reopen(id, request));
    }

    /** 生成绩效单的入参：period 为空时按当月 */
    public record PeriodRequest(String period) {
    }

    // ---------------------------------------------------------------- 工时填报

    /** 某期间的工时明细 */
    @GetMapping("/hours")
    public ApiResponse hours(@RequestParam(required = false) String period,
                             @RequestParam(required = false) String worker) {
        return ApiResponse.ok(workHours.list(period, worker));
    }

    /** 按人汇总：谁填了、谁还没填 */
    @GetMapping("/hours/summary")
    public ApiResponse hoursSummary(@RequestParam(required = false) String period) {
        return ApiResponse.ok(workHours.summary(period));
    }

    @PostMapping("/hours")
    public ApiResponse createHour(@RequestBody WorkHourService.HourRequest request) {
        return ApiResponse.ok(java.util.Map.of("id", workHours.create(request)));
    }

    /** 批量填报：同一天、同样时长，一次给一个班组各记一条（任何一条不合法整批回滚） */
    @PostMapping("/hours/batch")
    public ApiResponse createHoursBatch(@RequestBody WorkHourService.BatchHourRequest request) {
        return ApiResponse.ok(java.util.Map.of("count", workHours.createBatch(request)));
    }

    @PutMapping("/hours/{id}")
    public ApiResponse updateHour(@PathVariable long id,
                                  @RequestBody WorkHourService.HourRequest request) {
        workHours.update(id, request);
        return ApiResponse.ok(java.util.Map.of("id", id));
    }

    @DeleteMapping("/hours/{id}")
    public ApiResponse deleteHour(@PathVariable long id) {
        workHours.delete(id);
        return ApiResponse.ok(java.util.Map.of("id", id));
    }
}
