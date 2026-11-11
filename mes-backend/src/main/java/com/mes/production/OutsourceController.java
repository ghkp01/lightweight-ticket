package com.mes.production;

import com.mes.common.ApiResponse;
import com.mes.common.PageResult;
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

/**
 * 工序外协：转外协、发出、收回与外协单查询。
 *
 * <p>发出要扣本厂库存，权限与领料同一档（{@code production:outsource}）；
 * 收回要出检验结论，另给 {@code production:outsource-receive}。</p>
 */
@RestController
@RequestMapping("/api/outsource")
public class OutsourceController {

    private final OutsourceService service;

    public OutsourceController(OutsourceService service) {
        this.service = service;
    }

    @GetMapping("/orders")
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Long supplierId,
                            @RequestParam(required = false) Boolean overdueOnly,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        PageResult result = service.list(keyword, status, supplierId, overdueOnly, page, size);
        return ApiResponse.ok(result);
    }

    @GetMapping("/orders/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    /** 工单详情用：这张工单的全部外协记录 */
    @GetMapping("/work-orders/{workOrderId}/orders")
    public ApiResponse byWorkOrder(@PathVariable long workOrderId) {
        return ApiResponse.ok(service.listByWorkOrder(workOrderId));
    }

    /** 转外协：把某道工序标成外协，还没发料 */
    @PostMapping("/work-orders/{workOrderId}/operations/{seq}/plan")
    @PreAuthorize("hasAuthority('production:outsource')")
    public ApiResponse plan(@PathVariable long workOrderId, @PathVariable int seq,
                            @RequestBody(required = false) OutsourceService.PlanRequest request) {
        return ApiResponse.ok(service.plan(workOrderId, seq, request));
    }

    /** 撤销转外协：只在还没发出过的时候允许 */
    @DeleteMapping("/work-orders/{workOrderId}/operations/{seq}/plan")
    @PreAuthorize("hasAuthority('production:outsource')")
    public ApiResponse cancelPlan(@PathVariable long workOrderId, @PathVariable int seq) {
        service.cancelPlan(workOrderId, seq);
        return ApiResponse.ok(Map.of("operationSeq", seq));
    }

    /** 待发料清单：这道工序还要多少料、仓库还有多少 */
    @GetMapping("/work-orders/{workOrderId}/operations/{seq}/materials")
    public ApiResponse materials(@PathVariable long workOrderId, @PathVariable int seq) {
        return ApiResponse.ok(service.materials(workOrderId, seq));
    }

    /** 发出：生成外协单并把这工序的料发给外协厂 */
    @PostMapping("/work-orders/{workOrderId}/operations/{seq}/send")
    @PreAuthorize("hasAuthority('production:outsource')")
    public ApiResponse send(@PathVariable long workOrderId, @PathVariable int seq,
                            @RequestBody OutsourceService.SendRequest request) {
        return ApiResponse.ok(service.send(workOrderId, seq, request));
    }

    /** 收回：登记数量与不良，推进工序与件码 */
    @PostMapping("/orders/{id}/receive")
    @PreAuthorize("hasAuthority('production:outsource-receive')")
    public ApiResponse receive(@PathVariable long id,
                               @RequestBody OutsourceService.ReceiveRequest request) {
        return ApiResponse.ok(service.receive(id, request));
    }
}
