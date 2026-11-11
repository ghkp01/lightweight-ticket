package com.mes.org;

import com.mes.common.ApiResponse;
import jakarta.validation.Valid;
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
 * 工作中心接口：排产与产能的抓手。
 */
@RestController
@RequestMapping("/api/work-centers")
public class WorkCenterController {

    private final WorkCenterService service;

    public WorkCenterController(WorkCenterService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) Long orgId,
                            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(service.list(keyword, orgId, enabled));
    }

    @GetMapping("/options")
    public ApiResponse options() {
        return ApiResponse.ok(service.options());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('workcenter:save')")
    public ApiResponse create(@Valid @RequestBody WorkCenterService.WorkCenterRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('workcenter:save')")
    public ApiResponse update(@PathVariable long id,
                              @Valid @RequestBody WorkCenterService.WorkCenterRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('workcenter:save')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
