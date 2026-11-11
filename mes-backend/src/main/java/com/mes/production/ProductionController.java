package com.mes.production;

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

import java.util.Map;

@RestController
@RequestMapping("/api/work-orders")
public class ProductionController {

    private final ProductionService service;

    public ProductionController(ProductionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size,
                            @RequestParam(defaultValue = "false") boolean onlyShortage,
                            @RequestParam(required = false) String customFieldKey,
                            @RequestParam(required = false) String customFieldValue) {
        return ApiResponse.ok(service.list(keyword, status, page, size, onlyShortage,
                customFieldKey, customFieldValue));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/by-no/{workNo}")
    public ApiResponse detailByNo(@PathVariable String workNo) {
        return ApiResponse.ok(service.detailByNo(workNo));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('production:create')")
    public ApiResponse create(@RequestBody ProductionService.WorkOrderRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('production:release')")
    public ApiResponse release(@PathVariable long id) {
        service.release(id);
        return ApiResponse.ok();
    }

    /** 改追溯方式：只能在下达前改，下达时按它生成件码 */
    @PostMapping("/{id}/trace-mode")
    @PreAuthorize("hasAuthority('production:create')")
    public ApiResponse setTraceMode(@PathVariable long id,
                                    @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.setTraceMode(id, body == null ? null : body.get("traceMode")));
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAuthority('production:issue')")
    public ApiResponse issue(@PathVariable long id) {
        service.issue(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/issue-item")
    @PreAuthorize("hasAuthority('production:issue')")
    public ApiResponse issueItem(@PathVariable long id,
                                 @RequestBody ProductionService.IssueItemRequest request) {
        return ApiResponse.ok(service.issueItem(id, request));
    }

    /** 批量领料：一次提交多行，生成一张领料单，失败整单回滚 */
    @PostMapping("/{id}/issue-batch")
    @PreAuthorize("hasAuthority('production:issue')")
    public ApiResponse issueBatch(@PathVariable long id,
                                  @RequestBody com.mes.production.WorkOrderIssueService.IssueBatchRequest request) {
        return ApiResponse.ok(service.issueBatch(id, request));
    }

    /** 工单的领料单列表 */
    @GetMapping("/{id}/issues")
    public ApiResponse issues(@PathVariable long id) {
        return ApiResponse.ok(service.issues(id));
    }

    /** 补生成单件件码（历史工单或下达时漏生成） */
    @PostMapping("/{id}/units/generate")
    @PreAuthorize("hasAuthority('production:report')")
    public ApiResponse generateUnits(@PathVariable long id) {
        return ApiResponse.ok(service.generateUnits(id));
    }

    @PostMapping("/{id}/report")
    @PreAuthorize("hasAuthority('production:report')")
    public ApiResponse report(@PathVariable long id, @RequestBody ProductionService.ReportRequest request) {
        return ApiResponse.ok(service.report(id, request));
    }

    /** 手工给工单加一道工序：产品没维护工艺路线时用 */
    @PostMapping("/{id}/operations")
    @PreAuthorize("hasAuthority('production:create')")
    public ApiResponse addOperation(@PathVariable long id,
                                    @RequestBody ProductionService.AddOperationRequest request) {
        return ApiResponse.ok(service.addOperation(id, request));
    }

    @DeleteMapping("/{id}/operations/{seq}")
    @PreAuthorize("hasAuthority('production:create')")
    public ApiResponse deleteOperation(@PathVariable long id, @PathVariable int seq) {
        service.deleteOperation(id, seq);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/operations/{operationId}/assign")
    @PreAuthorize("hasAuthority('production:assign')")
    public ApiResponse assign(@PathVariable long id, @PathVariable long operationId,
                              @RequestBody ProductionService.AssignRequest request) {
        return ApiResponse.ok(service.assignOperation(id, operationId, request.userId(), request.worker(),
                request.reportPolicy()));
    }

    /** 工序多人分配：整体替换分配名单，每人一份数量，报工时按人卡配额 */
    @PostMapping("/{id}/operations/{operationId}/assignments")
    @PreAuthorize("hasAuthority('production:assign')")
    public ApiResponse assignOperations(@PathVariable long id, @PathVariable long operationId,
                                        @RequestBody ProductionService.AssignmentsRequest request) {
        return ApiResponse.ok(service.assignOperations(id, operationId, request.items(), request.reportPolicy()));
    }

    @PostMapping("/{id}/return-material")
    @PreAuthorize("hasAuthority('production:return')")
    public ApiResponse returnMaterial(@PathVariable long id,
                                      @RequestBody WorkOrderIssueService.ReturnRequest request) {
        return ApiResponse.ok(service.returnMaterial(id, request));
    }

    /** 可退料明细：已领 / 已退 / 可退 / 建议退料量 */
    @GetMapping("/{id}/returnable")
    public ApiResponse returnable(@PathVariable long id) {
        return ApiResponse.ok(service.returnableLines(id));
    }

    /** 工单的退料单列表 */
    @GetMapping("/{id}/returns")
    public ApiResponse returns(@PathVariable long id) {
        return ApiResponse.ok(service.returns(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('production:close')")
    public ApiResponse close(@PathVariable long id) {
        service.close(id);
        return ApiResponse.ok();
    }

    /**
     * 作废工单：客户取消、下单错误时撤掉还没开工的工单。
     * 原因用 query 参数传，前端弹窗里填一句，便于事后追溯是谁为什么撤的。
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('production:cancel')")
    public ApiResponse cancel(@PathVariable long id,
                              @RequestParam(required = false) String reason) {
        return ApiResponse.ok(service.cancelWorkOrder(id, reason));
    }

    @PostMapping("/{id}/bom")
    @PreAuthorize("hasAuthority('production:bom')")
    public ApiResponse saveBomLine(@PathVariable long id,
                                   @RequestBody ProductionService.BomLineRequest request) {
        return ApiResponse.ok(service.saveBomLine(id, request));
    }

    @DeleteMapping("/{id}/bom/{bomId}")
    @PreAuthorize("hasAuthority('production:bom')")
    public ApiResponse deleteBomLine(@PathVariable long id, @PathVariable long bomId,
                                     @RequestParam(required = false) String reason) {
        service.deleteBomLine(id, bomId, reason);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/bom/reset")
    @PreAuthorize("hasAuthority('production:bom')")
    public ApiResponse resetBom(@PathVariable long id) {
        return ApiResponse.ok(service.resetBom(id));
    }

    @GetMapping("/{id}/bom-changes")
    public ApiResponse bomChanges(@PathVariable long id) {
        return ApiResponse.ok(service.bomChanges(id));
    }

    @GetMapping("/{id}/bom-diff")
    public ApiResponse bomDiff(@PathVariable long id) {
        return ApiResponse.ok(service.bomDiff(id));
    }

    @PostMapping("/{id}/issue-operation")
    @PreAuthorize("hasAuthority('production:issue')")
    public ApiResponse issueOperation(@PathVariable long id,
                                      @RequestBody java.util.Map<String, Object> body) {
        Object seq = body.get("operationSeq");
        if (seq == null) {
            return ApiResponse.error(1002, "请提供工序顺序");
        }
        return ApiResponse.ok(service.issueByOperation(id, Integer.parseInt(String.valueOf(seq))));
    }
}
