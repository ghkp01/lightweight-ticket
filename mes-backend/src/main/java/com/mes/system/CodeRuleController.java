package com.mes.system;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class CodeRuleController {

    private final CodeRuleService service;

    public CodeRuleController(CodeRuleService service) {
        this.service = service;
    }

    @GetMapping("/code-rules")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse list() {
        return ApiResponse.ok(service.list());
    }

    /** 新建主数据时取一个未占用的编码（工作中心 / 工序 / 仓库 / 产品 / 客户 / 供应商） */
    @GetMapping("/code-rules/next")
    public ApiResponse next(@RequestParam String bizType) {
        return ApiResponse.ok(Map.of("code", service.next(bizType)));
    }

    @PutMapping("/code-rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse update(@PathVariable long id, @RequestBody CodeRuleService.RuleRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }
}
