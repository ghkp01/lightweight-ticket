package com.mes.planning;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scheduling")
public class SchedulingController {

    private final SchedulingService service;

    public SchedulingController(SchedulingService service) {
        this.service = service;
    }

    @GetMapping("/board")
    @PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
    public ApiResponse board() {
        return ApiResponse.ok(service.board());
    }

    @PostMapping("/auto")
    @PreAuthorize("hasAuthority('production:schedule')")
    public ApiResponse auto(@RequestBody(required = false) SchedulingService.AutoRequest request) {
        return ApiResponse.ok(service.autoSchedule(request));
    }

    @PostMapping("/aps")
    @PreAuthorize("hasAuthority('production:schedule')")
    public ApiResponse aps(@RequestBody(required = false) SchedulingService.ApsRequest request) {
        return ApiResponse.ok(service.apsSchedule(request));
    }

    @PostMapping("/{id}/schedule")
    @PreAuthorize("hasAuthority('production:schedule')")
    public ApiResponse schedule(@PathVariable long id,
                                @RequestBody(required = false) SchedulingService.ScheduleRequest request) {
        return ApiResponse.ok(service.schedule(id, request));
    }

    @PostMapping("/{id}/rush")
    @PreAuthorize("hasAuthority('production:schedule')")
    public ApiResponse rush(@PathVariable long id,
                            @RequestBody(required = false) SchedulingService.RushRequest request) {
        return ApiResponse.ok(service.rush(id, request));
    }

    @PostMapping("/{id}/move")
    @PreAuthorize("hasAuthority('production:schedule')")
    public ApiResponse move(@PathVariable long id,
                            @RequestBody SchedulingService.MoveRequest request) {
        return ApiResponse.ok(service.move(id, request));
    }

    @PostMapping("/{id}/unschedule")
    @PreAuthorize("hasAuthority('production:schedule')")
    public ApiResponse unschedule(@PathVariable long id) {
        return ApiResponse.ok(service.unschedule(id));
    }
}
