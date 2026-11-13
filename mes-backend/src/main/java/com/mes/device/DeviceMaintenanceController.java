package com.mes.device;

import com.mes.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 设备点检与保养：点检项、点检任务、保养计划与记录、故障记录。
 */
@RestController
@RequestMapping("/api/device-maintenance")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER', 'WAREHOUSE')")
public class DeviceMaintenanceController {

    private final DeviceMaintenanceService service;

    public DeviceMaintenanceController(DeviceMaintenanceService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    // ---------------- 点检

    @GetMapping("/checks")
    public ApiResponse checks(@RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @RequestParam(required = false) Long deviceId,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) Integer page,
                              @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.checkPage(date, deviceId, status, page, size));
        }
        return ApiResponse.ok(service.checkTasks(date, deviceId, status));
    }

    @GetMapping("/checks/{id}")
    public ApiResponse checkDetail(@PathVariable long id) {
        return ApiResponse.ok(service.checkDetail(id));
    }

    @PostMapping("/checks/generate")
    public ApiResponse generate(@RequestParam(required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(java.util.Map.of("created", service.generateTasks(date)));
    }

    @PostMapping("/checks/{id}/submit")
    public ApiResponse submit(@PathVariable long id,
                              @RequestBody DeviceMaintenanceService.SubmitCheckRequest request) {
        return ApiResponse.ok(service.submitCheck(id, request));
    }

    @PostMapping("/checks/{id}/reset")
    public ApiResponse reset(@PathVariable long id) {
        service.resetCheck(id);
        return ApiResponse.ok();
    }

    @GetMapping("/check-items")
    public ApiResponse checkItems(@RequestParam(required = false) Long deviceId) {
        return ApiResponse.ok(service.checkItems(deviceId));
    }

    @PostMapping("/check-items")
    public ApiResponse saveCheckItem(@RequestBody DeviceMaintenanceService.CheckItemRequest request) {
        return ApiResponse.ok(java.util.Map.of("id", service.saveCheckItem(request)));
    }

    @DeleteMapping("/check-items/{id}")
    public ApiResponse deleteCheckItem(@PathVariable long id) {
        service.deleteCheckItem(id);
        return ApiResponse.ok();
    }

    // ---------------- 保养

    @GetMapping("/plans")
    public ApiResponse plans(@RequestParam(defaultValue = "false") boolean dueOnly) {
        return ApiResponse.ok(service.plans(dueOnly));
    }

    @PostMapping("/plans")
    public ApiResponse savePlan(@RequestBody DeviceMaintenanceService.PlanRequest request) {
        return ApiResponse.ok(java.util.Map.of("id", service.savePlan(request)));
    }

    @DeleteMapping("/plans/{id}")
    public ApiResponse deletePlan(@PathVariable long id) {
        service.deletePlan(id);
        return ApiResponse.ok();
    }

    @PostMapping("/plans/{id}/execute")
    public ApiResponse executePlan(@PathVariable long id,
                                   @RequestBody(required = false)
                                   DeviceMaintenanceService.ExecutePlanRequest request) {
        return ApiResponse.ok(service.executePlan(id, request));
    }

    @GetMapping("/plan-logs")
    public ApiResponse planLogs(@RequestParam(required = false) Long planId,
                                @RequestParam(required = false) Long deviceId) {
        return ApiResponse.ok(service.planLogs(planId, deviceId));
    }

    // ---------------- 故障

    @GetMapping("/faults")
    public ApiResponse faults(@RequestParam(required = false) String status,
                              @RequestParam(required = false) Long deviceId,
                              @RequestParam(required = false) Integer page,
                              @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.faultPage(status, deviceId, page, size));
        }
        return ApiResponse.ok(service.faults(status, deviceId));
    }

    @PostMapping("/faults")
    public ApiResponse openFault(@RequestBody DeviceMaintenanceService.FaultRequest request) {
        return ApiResponse.ok(java.util.Map.of("id", service.openFault(request)));
    }

    @PostMapping("/faults/{id}/close")
    public ApiResponse closeFault(@PathVariable long id,
                                  @RequestBody(required = false)
                                  DeviceMaintenanceService.CloseFaultRequest request) {
        return ApiResponse.ok(service.closeFault(id, request));
    }
}
