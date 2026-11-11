package com.mes.andon;

import com.mes.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车间异常安灯接口，登录后即可呼叫与处理。
 */
@RestController
@RequestMapping("/api/andon")
public class AndonController {

    private final AndonService service;

    public AndonController(AndonService service) {
        this.service = service;
    }

    @PostMapping("/calls")
    public ApiResponse create(@RequestBody AndonService.CallRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping("/calls")
    public ApiResponse calls(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.list(status, keyword));
    }

    @PostMapping("/calls/{id}/ack")
    public ApiResponse ack(@PathVariable long id) {
        return ApiResponse.ok(service.ack(id));
    }

    @PostMapping("/calls/{id}/resolve")
    public ApiResponse resolve(@PathVariable long id) {
        return ApiResponse.ok(service.resolve(id));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }
}
