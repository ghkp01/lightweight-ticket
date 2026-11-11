package com.mes.trace;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次追溯：批次 / 工单 / 发货单三个入口，扫码枪扫到批次码也走这里。
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final TraceService service;

    public TraceController(TraceService service) {
        this.service = service;
    }

    /** 统一入口：WO 开头当工单，SH 开头当发货单，其余当批次号 */
    @GetMapping("/query")
    @PreAuthorize("hasAuthority('trace')")
    public ApiResponse query(@RequestParam String code) {
        return ApiResponse.ok(service.query(code));
    }

    @GetMapping("/batch/{batchNo}")
    @PreAuthorize("hasAuthority('trace')")
    public ApiResponse batch(@PathVariable String batchNo) {
        return ApiResponse.ok(service.traceBatch(batchNo));
    }

    @GetMapping("/work-order/{workNo}")
    @PreAuthorize("hasAuthority('trace')")
    public ApiResponse workOrder(@PathVariable String workNo) {
        return ApiResponse.ok(service.traceWorkOrder(workNo));
    }

    @GetMapping("/shipment/{shipmentNo}")
    @PreAuthorize("hasAuthority('trace')")
    public ApiResponse shipment(@PathVariable String shipmentNo) {
        return ApiResponse.ok(service.traceShipment(shipmentNo));
    }
}
