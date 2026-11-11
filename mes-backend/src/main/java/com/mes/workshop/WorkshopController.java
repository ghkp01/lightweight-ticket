package com.mes.workshop;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workshop")
public class WorkshopController {

    private final WorkshopService service;

    public WorkshopController(WorkshopService service) {
        this.service = service;
    }

    @GetMapping("/board")
    @PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
    public ApiResponse board(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Long orgId) {
        return ApiResponse.ok(service.board(keyword, orgId));
    }
}
