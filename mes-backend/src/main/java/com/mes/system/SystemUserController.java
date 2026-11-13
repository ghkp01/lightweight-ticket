package com.mes.system;

import com.mes.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemUserController {

    private final SystemUserService service;
    private final UserSkillService userSkillService;

    public SystemUserController(SystemUserService service, UserSkillService userSkillService) {
        this.service = service;
        this.userSkillService = userSkillService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse users(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Long orgId,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, orgId, page, size));
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse create(@Valid @RequestBody SystemUserService.CreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    /** 派工选人用：只要登录即可取启用用户，带组织与岗位，便于按班组挑人 */
    @GetMapping("/users/options")
    public ApiResponse userOptions(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) Long orgId,
                                   @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.options(keyword, orgId, limit));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse update(@PathVariable long id,
                              @Valid @RequestBody SystemUserService.UpdateRequest request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse resetPassword(@PathVariable long id,
                                     @Valid @RequestBody SystemUserService.ResetPasswordRequest request) {
        service.resetPassword(id, request);
        return ApiResponse.ok();
    }

    /** 解锁被登录失败锁定的账号 */
    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse unlock(@PathVariable long id) {
        service.unlock(id);
        return ApiResponse.ok();
    }

    @GetMapping("/users/{id}/skills")
    public ApiResponse skills(@PathVariable long id) {
        return ApiResponse.ok(userSkillService.list(id));
    }

    /** 全量覆盖某人的工序技能：传空数组即清空 */
    @PutMapping("/users/{id}/skills")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse saveSkills(@PathVariable long id,
                                  @RequestBody UserSkillService.SkillRequest request) {
        userSkillService.save(id, request);
        return ApiResponse.ok();
    }
}
