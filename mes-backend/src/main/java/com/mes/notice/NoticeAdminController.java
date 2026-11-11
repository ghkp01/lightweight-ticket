package com.mes.notice;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内通知管理（管理员）：看所有人的消息、按人/分类/已读/时间筛、删单条、批量删、清理已读。
 * 普通用户只在自己的「消息中心」里看自己的消息，走 /api/notices。
 */
@RestController
@RequestMapping("/api/system/notices")
@PreAuthorize("hasAuthority('system_notice')")
public class NoticeAdminController {

    private final NoticeService service;

    public NoticeAdminController(NoticeService service) {
        this.service = service;
    }

    public record IdsRequest(List<Long> ids) {
    }

    public record PurgeRequest(Integer days) {
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String receiver,
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) String readStatus,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.adminList(keyword, receiver, category, readStatus, from, to, page, size));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.adminSummary());
    }

    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable long id) {
        return ApiResponse.ok(service.delete(id));
    }

    @PostMapping("/batch-delete")
    public ApiResponse batchDelete(@RequestBody IdsRequest request) {
        return ApiResponse.ok(service.deleteBatch(request == null ? null : request.ids()));
    }

    @PostMapping("/purge-read")
    public ApiResponse purgeRead(@RequestBody(required = false) PurgeRequest request) {
        return ApiResponse.ok(service.purgeRead(request == null ? null : request.days()));
    }
}
