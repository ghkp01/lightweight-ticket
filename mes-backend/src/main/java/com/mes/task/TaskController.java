package com.mes.task;

import com.mes.common.ApiResponse;
import com.mes.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的任务：全流程每个交接点落在谁头上、做没做、什么时候到期。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final BizTaskService service;

    public TaskController(BizTaskService service) {
        this.service = service;
    }

    /** 我的任务：点名给我的 + 我所在角色池里没人认领的 */
    @GetMapping("/my")
    public ApiResponse my(@RequestParam(required = false) String status,
                          @RequestParam(required = false) String type,
                          @RequestParam(required = false) Integer page,
                          @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.minePage(CurrentUser.name(), status, type, page, size));
        }
        return ApiResponse.ok(service.mine(CurrentUser.name(), status, type));
    }

    /** 全部任务：管理岗排查没人认领与逾期 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
    public ApiResponse all(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String type,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Integer page,
                           @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.allPage(status, type, keyword, page, size));
        }
        return ApiResponse.ok(service.all(status, type, keyword));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary(CurrentUser.name()));
    }

    /** 改派：把角色池里的任务指到具体某个人；用户名为空则退回角色池 */
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('task:assign')")
    public ApiResponse assign(@PathVariable long id, @RequestBody AssignRequest request) {
        return ApiResponse.ok(service.assign(id, request == null ? null : request.username()));
    }

    @PostMapping("/{id}/done")
    public ApiResponse done(@PathVariable long id, @RequestBody(required = false) DoneRequest request) {
        return ApiResponse.ok(service.done(id, request == null ? null : request.note()));
    }

    public record AssignRequest(String username) {
    }

    public record DoneRequest(String note) {
    }
}
