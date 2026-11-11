package com.mes.system;

import com.mes.common.ApiResponse;
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
 * 表格视图接口：登录用户维护自己的列设置与默认筛选，不涉及他人数据。
 */
@RestController
@RequestMapping("/api/user-views")
public class UserViewController {

    private final UserViewService service;

    public UserViewController(UserViewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam String pageKey) {
        return ApiResponse.ok(service.list(pageKey));
    }

    @PostMapping
    public ApiResponse create(@RequestBody UserViewService.ViewRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable long id, @RequestBody UserViewService.ViewRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/default")
    public ApiResponse setDefault(@PathVariable long id) {
        service.setDefault(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
