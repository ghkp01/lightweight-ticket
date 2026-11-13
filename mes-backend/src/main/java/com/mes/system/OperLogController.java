package com.mes.system;

import com.mes.common.ApiResponse;
import com.mes.platform.audit.OperLogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询：只读，入口在系统管理下的「操作日志」。
 */
@RestController
@RequestMapping("/api/system/logs")
public class OperLogController {

    private final OperLogService service;

    public OperLogController(OperLogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system_log')")
    public ApiResponse list(@RequestParam(required = false) String module,
                            @RequestParam(required = false) String operator,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String success,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) Integer limit,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.page(module, operator, keyword, success, from, to, page, size));
        }
        return ApiResponse.ok(service.list(module, operator, keyword, success, from, to, limit));
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('system_log')")
    public ApiResponse options() {
        return ApiResponse.ok(service.options());
    }
}
