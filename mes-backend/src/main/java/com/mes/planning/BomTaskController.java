package com.mes.planning;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单 BOM 任务：计划员派工、技术员完成。
 */
@RestController
@RequestMapping("/api/bom-tasks")
public class BomTaskController {

    private final BomTaskService service;

    public BomTaskController(BomTaskService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('bom_task')")
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "false") boolean onlyMine,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, onlyMine, page, size));
    }

    /** 派工弹窗：订单明细 + BOM 就绪状态 + 已有任务 */
    @GetMapping("/orders/{orderId}/items")
    @PreAuthorize("hasAuthority('bom_task')")
    public ApiResponse orderItems(@PathVariable long orderId) {
        return ApiResponse.ok(service.orderItems(orderId));
    }

    @GetMapping("/assignees")
    @PreAuthorize("hasAuthority('bom_task')")
    public ApiResponse assignees() {
        return ApiResponse.ok(service.assignees());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('bom_task:assign')")
    public ApiResponse assign(@RequestBody BomTaskService.AssignRequest request) {
        return ApiResponse.ok(Map.of("created", service.assign(request)));
    }

    @PutMapping("/{id}/assignee")
    @PreAuthorize("hasAuthority('bom_task:assign')")
    public ApiResponse reassign(@PathVariable long id,
                                @RequestBody BomTaskService.ReassignRequest request) {
        service.reassign(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('bom_task:complete')")
    public ApiResponse complete(@PathVariable long id) {
        service.complete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('bom_task:cancel')")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }
}
