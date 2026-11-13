package com.mes.approval;

import com.mes.common.ApiResponse;
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

import java.util.List;
import java.util.Map;

/**
 * 审批中心：流程配置（管理员）+ 我的待办 + 我发起的。
 * 待办任何人都能看，但只有节点对应角色能处理（管理员可代办）。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public ApiResponse roles() {
        return ApiResponse.ok(ApprovalService.ROLES);
    }

    @GetMapping("/flows")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse flows(@RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.flowsPage(page, size == null ? 20 : size));
        }
        return ApiResponse.ok(service.flows());
    }

    /** 菜单级审批开关：哪个业务走审批流、哪个只按金额阈值、哪个直接关掉 */
    @GetMapping("/biz-types")
    public ApiResponse bizTypes() {
        return ApiResponse.ok(service.bizList());
    }

    /** 适用条件可用的字段与运算符，前端配置面板按它渲染 */
    @GetMapping("/condition-fields")
    public ApiResponse conditionFields() {
        return ApiResponse.ok(Map.of(
                "fields", ApprovalService.CONDITION_FIELDS,
                "ops", ApprovalService.MATCH_OPS));
    }

    /** 试算：拿一组假想变量看会命中哪条链，只读不落库 */
    @PostMapping("/flows/simulate")
    public ApiResponse simulate(@RequestBody Map<String, Object> body) {
        Object bizType = body == null ? null : body.get("bizType");
        Object variables = body == null ? null : body.get("variables");
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = variables instanceof Map ? (Map<String, Object>) variables : Map.of();
        return ApiResponse.ok(service.simulate(bizType == null ? null : String.valueOf(bizType), vars));
    }

    @PutMapping("/biz-types/{bizType}")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse saveBiz(@PathVariable String bizType,
                               @RequestBody ApprovalService.BizRequest request) {
        return ApiResponse.ok(service.saveBiz(new ApprovalService.BizRequest(
                bizType, request == null ? null : request.name(),
                request == null ? null : request.menuPath(),
                request == null ? null : request.mode(),
                request == null ? null : request.remark())));
    }

    @GetMapping("/flows/{id}")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse flow(@PathVariable long id) {
        return ApiResponse.ok(service.flow(id));
    }

    @PostMapping("/flows")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse createFlow(@RequestBody ApprovalService.FlowRequest request) {
        return ApiResponse.ok(Map.of("id", service.saveFlow(null, request)));
    }

    /** 只改适用条件与优先级：不会重新部署 BPMN，改完对下一张单生效 */
    @PutMapping("/flows/{id}/match")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse saveMatch(@PathVariable long id, @RequestBody MatchRequest request) {
        return ApiResponse.ok(service.saveMatch(id,
                request == null ? null : request.priority(),
                request == null ? null : request.note(),
                request == null ? null : request.rules()));
    }

    public record MatchRequest(Integer priority, String note, List<ApprovalService.MatchRule> rules) {
    }

    @PutMapping("/flows/{id}")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse updateFlow(@PathVariable long id, @RequestBody ApprovalService.FlowRequest request) {
        return ApiResponse.ok(Map.of("id", service.saveFlow(id, request)));
    }

    @PostMapping("/flows/{id}/toggle")
    public ApiResponse toggleFlow(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        service.toggleFlow(id, Boolean.TRUE.equals(body.get("enabled")));
        return ApiResponse.ok();
    }

    /**
     * 灰度发布：version 为空即关闭。
     * percent 管「名单外的人按比例抽」，factoryOrgIds / usernames 是定向名单（命中就全量走新版）。
     */
    @PostMapping("/flows/{id}/canary")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse canary(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Integer percent = body.get("percent") == null ? null : ((Number) body.get("percent")).intValue();
        Integer version = body.get("version") == null ? null : ((Number) body.get("version")).intValue();
        List<Long> factoryOrgIds = longs(body.get("factoryOrgIds"));
        List<String> usernames = strings(body.get("usernames"));
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        return ApiResponse.ok(service.configureCanary(id,
                new ApprovalService.CanaryRequest(version, percent, factoryOrgIds, usernames, note)));
    }

    /** 灰度现状：当前规则 + 各版本实际跑了多少单 */
    @GetMapping("/flows/{id}/canary")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse canaryStatus(@PathVariable long id) {
        return ApiResponse.ok(service.canaryStatus(id));
    }

    /** 节点策略：超时、允许转交 / 加签、抄送人（节点清单从 BPMN 读） */
    @GetMapping("/flows/{id}/node-policies")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse nodePolicies(@PathVariable long id) {
        return ApiResponse.ok(service.nodePolicies(id));
    }

    @PostMapping("/flows/{id}/node-policies")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse saveNodePolicies(@PathVariable long id,
                                        @RequestBody List<ApprovalService.NodePolicyRequest> requests) {
        return ApiResponse.ok(java.util.Map.of("saved", service.saveNodePolicies(id, requests)));
    }

    private List<Long> longs(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        List<Long> result = new java.util.ArrayList<>();
        for (Object item : items) {
            if (item instanceof Number number) {
                result.add(number.longValue());
            } else if (item != null && !String.valueOf(item).isBlank()) {
                try {
                    result.add(Long.valueOf(String.valueOf(item).trim()));
                } catch (NumberFormatException ignored) {
                    // 前端传了脏值就当没选
                }
            }
        }
        return result;
    }

    private List<String> strings(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (Object item : items) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item).trim());
            }
        }
        return result;
    }

    /** 是否要求手写签名 */
    @PostMapping("/flows/{id}/signature-required")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse signatureRequired(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        service.setSignatureRequired(id, Boolean.TRUE.equals(body.get("required")));
        return ApiResponse.ok();
    }

    @GetMapping("/flows/{id}/field-rules")
    public ApiResponse fieldRules(@PathVariable long id) {
        return ApiResponse.ok(service.fieldRules(id));
    }

    /** 可配字段权限的用户任务 */
    @GetMapping("/flows/{id}/user-tasks")
    public ApiResponse userTasks(@PathVariable long id) {
        return ApiResponse.ok(service.userTasks(id));
    }

    @PutMapping("/flows/{id}/field-rules")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse saveFieldRules(@PathVariable long id,
                                      @RequestBody List<Map<String, String>> rules) {
        service.saveFieldRules(id, rules);
        return ApiResponse.ok();
    }

    @DeleteMapping("/flows/{id}")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse deleteFlow(@PathVariable long id) {
        service.deleteFlow(id);
        return ApiResponse.ok();
    }

    @GetMapping("/assignees")
    public ApiResponse assignees(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.assigneeOptions(keyword));
    }

    @GetMapping("/tasks")
    public ApiResponse tasks(@RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.myTasksPage(page, size == null ? 20 : size));
        }
        return ApiResponse.ok(service.myTasks());
    }

    @PostMapping("/extra-tasks/{id}/approve")
    public ApiResponse approveExtra(@PathVariable long id,
                                    @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(service.approveExtra(id, body == null ? null : body.get("comment")));
    }

    @PostMapping("/extra-tasks/{id}/reject")
    public ApiResponse rejectExtra(@PathVariable long id,
                                   @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(service.rejectExtra(id, body == null ? null : body.get("comment")));
    }

    @PostMapping("/tasks/{id}/approve")
    public ApiResponse approve(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(service.approve(id,
                body == null ? null : body.get("comment"),
                body == null ? null : body.get("signature")));
    }

    /** 回退到之前走过的节点 */
    @PostMapping("/tasks/{id}/rollback")
    public ApiResponse rollback(@PathVariable String id,
                                @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(service.rollback(id,
                body == null ? null : body.get("targetNodeKey"),
                body == null ? null : body.get("comment")));
    }

    @GetMapping("/tasks/{id}/rollback-targets")
    public ApiResponse rollbackTargets(@PathVariable String id) {
        return ApiResponse.ok(service.rollbackTargets(id));
    }

    /** 当前节点看到的表单（按字段级权限过滤） */
    @GetMapping("/tasks/{id}/form")
    public ApiResponse form(@PathVariable String id) {
        return ApiResponse.ok(service.taskForm(id));
    }

    @PutMapping("/tasks/{id}/form")
    public ApiResponse saveForm(@PathVariable String id,
                                @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.saveTaskForm(id, body));
    }

    @PostMapping("/tasks/{id}/reject")
    public ApiResponse reject(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(service.reject(id, body == null ? null : body.get("comment")));
    }

    @PostMapping("/tasks/{id}/transfer")
    public ApiResponse transfer(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(service.transferTask(id,
                body == null ? null : body.get("targetUser"),
                body == null ? null : body.get("comment")));
    }

    @PostMapping("/tasks/{id}/add-sign")
    public ApiResponse addSign(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(service.addSign(id,
                body == null ? null : body.get("targetUser"),
                body == null ? null : body.get("comment")));
    }

    @PostMapping("/instances/{id}/urge")
    public ApiResponse urge(@PathVariable long id) {
        return ApiResponse.ok(service.urge(id));
    }

    /** 超时扫描：配置了超时的节点到期后提醒或自动通过，也挂在定时任务里 */
    @PostMapping("/timeout-scan")
    @PreAuthorize("hasAuthority('system_approval')")
    public ApiResponse timeoutScan() {
        return ApiResponse.ok(service.scanTimeouts());
    }

    @GetMapping("/instances")
    public ApiResponse instances(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Boolean mine,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.instances(keyword, status, mine, page, size));
    }

    @GetMapping("/instances/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/instances/{id}/cancel")
    public ApiResponse cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }
}
