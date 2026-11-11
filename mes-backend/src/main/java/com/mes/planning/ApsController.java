package com.mes.planning;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * APS：排产甘特与插单影响模拟。
 */
@RestController
@RequestMapping("/api/aps")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
public class ApsController {

    private final ApsService service;

    public ApsController(ApsService service) {
        this.service = service;
    }

    @GetMapping("/gantt")
    public ApiResponse gantt(@RequestParam(required = false) String from,
                             @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.gantt(from, to));
    }

    @PostMapping("/simulate")
    public ApiResponse simulate(@RequestBody ApsService.SimulateRequest request) {
        return ApiResponse.ok(service.simulate(request));
    }

    /** 甘特图拖动模拟：把工单移到目标日期，只算不写库 */
    @PostMapping("/simulate-move")
    public ApiResponse simulateMove(@RequestBody ApsService.SimulateMoveRequest request) {
        return ApiResponse.ok(service.simulateMove(request));
    }
}
