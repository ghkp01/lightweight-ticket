package com.mes.notice;

import com.mes.common.ApiResponse;
import com.mes.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的消息：顶栏铃铛与消息抽屉。
 */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    private final NoticeService service;

    public NoticeController(NoticeService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse mine(@RequestParam(required = false) String category,
                            @RequestParam(required = false) Boolean unreadOnly,
                            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.mine(CurrentUser.name(), category, unreadOnly, limit));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary(CurrentUser.name()));
    }

    /** 桌面通知轮询：只取 since 之后的未读消息，前端用来弹系统通知 */
    @GetMapping("/pending")
    public ApiResponse pending(@RequestParam(defaultValue = "0") long since,
                               @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(service.pending(CurrentUser.name(), since, limit));
    }

    @PutMapping("/{id}/read")
    public ApiResponse read(@PathVariable long id) {
        service.markRead(id, CurrentUser.name());
        return ApiResponse.ok();
    }

    @PutMapping("/read-all")
    public ApiResponse readAll() {
        return ApiResponse.ok(service.markAllRead(CurrentUser.name()));
    }
}
