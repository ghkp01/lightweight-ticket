package com.mes.process;

import com.mes.common.ApiResponse;
import com.mes.system.api.UserSkillPort;
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
 * 工序主数据接口。
 */
@RestController
@RequestMapping("/api/processes")
public class ProcessController {

    private final ProcessService service;
    private final UserSkillPort userSkillPort;

    public ProcessController(ProcessService service, UserSkillPort userSkillPort) {
        this.service = service;
        this.userSkillPort = userSkillPort;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) Boolean enabled,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.page(keyword, enabled, page, size));
        }
        return ApiResponse.ok(service.list(keyword, enabled));
    }

    @GetMapping("/options")
    public ApiResponse options(@RequestParam(required = false) String keyword,
                               @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.options(keyword, limit));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('process:save')")
    public ApiResponse create(@Valid @RequestBody ProcessService.ProcessRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('process:save')")
    public ApiResponse update(@PathVariable long id,
                              @Valid @RequestBody ProcessService.ProcessRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('process:save')")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** 派工候选：该工序的持证人员；返回空数组表示这道工序没有启用技能管控 */
    @GetMapping("/{id}/skill-users")
    public ApiResponse skillUsers(@PathVariable long id,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(userSkillPort.candidates(id, keyword, limit));
    }
}
