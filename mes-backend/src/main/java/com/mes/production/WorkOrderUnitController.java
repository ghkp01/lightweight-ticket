package com.mes.production;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 单件序列号：件码查询与扫码流转 */
@RestController
@RequestMapping("/api/work-order-units")
public class WorkOrderUnitController {

    private final WorkOrderUnitService service;

    public WorkOrderUnitController(WorkOrderUnitService service) {
        this.service = service;
    }

    /** 工单的件列表、位置分布与汇总；不带 workOrderId 时返回跨工单的件检索结果 */
    @GetMapping
    public ApiResponse list(@RequestParam(required = false) Long workOrderId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Integer operationSeq,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        if (workOrderId != null) {
            return ApiResponse.ok(service.listByWorkOrder(workOrderId));
        }
        return ApiResponse.ok(service.search(keyword, status, operationSeq,
                Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    /** 可筛选的工序下拉（按工单里出现过的工序序号去重） */
    @GetMapping("/operations")
    public ApiResponse operations() {
        return ApiResponse.ok(service.operationOptions());
    }

    /** 扫件码解析：当前工序、可执行动作、流转记录 */
    @GetMapping("/{unitNo}")
    public ApiResponse resolve(@PathVariable String unitNo) {
        return ApiResponse.ok(service.resolve(unitNo));
    }

    @PostMapping("/{unitNo}/start")
    @PreAuthorize("hasAuthority('production:report')")
    public ApiResponse start(@PathVariable String unitNo, @RequestBody(required = false) Map<String, Object> body) {
        String worker = body == null ? null : (body.get("worker") == null ? null : String.valueOf(body.get("worker")));
        return ApiResponse.ok(service.start(unitNo, worker));
    }

    @PostMapping("/{unitNo}/finish")
    @PreAuthorize("hasAuthority('production:report')")
    public ApiResponse finish(@PathVariable String unitNo,
                              @RequestBody(required = false) WorkOrderUnitService.FinishRequest request) {
        return ApiResponse.ok(service.finish(unitNo, request));
    }

    @PostMapping("/{unitNo}/scrap")
    @PreAuthorize("hasAuthority('production:report')")
    public ApiResponse scrap(@PathVariable String unitNo, @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : (body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        String worker = body == null ? null : (body.get("worker") == null ? null : String.valueOf(body.get("worker")));
        return ApiResponse.ok(service.scrap(unitNo, reason, worker));
    }
}
