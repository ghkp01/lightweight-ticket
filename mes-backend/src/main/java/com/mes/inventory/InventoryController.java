package com.mes.inventory;

import com.mes.common.ApiResponse;
import com.mes.common.PageResult;
import com.mes.warehouse.api.LocationPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api")
public class InventoryController {

    private final InventoryService service;
    private final LocationPort locationService;

    public InventoryController(InventoryService service, LocationPort locationService) {
        this.service = service;
        this.locationService = locationService;
    }

    /**
     * 手工出入库。locationCode 只有入库时用得上：填了就把这一批直接上架到对应库位，
     * 不填就留在「未指定库位」，后续可以在库位页移库。
     */
    public record StockRequest(@NotNull Long productId,
                               @NotNull Long warehouseId,
                               @NotNull @Positive BigDecimal qty,
                               String remark,
                               String batchNo,
                               String locationCode) {
    }

    @GetMapping("/inventory")
    public ApiResponse list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) Long warehouseId,
                            @RequestParam(defaultValue = "false") boolean onlyWarning) {
        return ApiResponse.ok(service.list(keyword, warehouseId, onlyWarning));
    }

    /** 某产品在某仓的批次库存，先进先出排序 */
    @GetMapping("/inventory/{productId}/batches")
    public ApiResponse batches(@PathVariable long productId, @RequestParam long warehouseId) {
        return ApiResponse.ok(service.batchStocks(productId, warehouseId));
    }

    @GetMapping("/inventory/txns")
    public ApiResponse txns(@RequestParam(required = false) Long productId,
                            @RequestParam(required = false) String txnType,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.txns(productId, txnType, page, size));
    }

    @PostMapping("/inventory/inbound")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE')")
    public ApiResponse inbound(@Valid @RequestBody StockRequest request) {
        String batchNo = service.inboundWithBatch(request.productId(), request.warehouseId(), request.qty(),
                "MANUAL", null,
                request.remark() == null ? "手工入库" : request.remark(),
                request.batchNo(), "ADJUST", null);
        // 指定了库位就顺手把这一批上架，省得入完再去库位页移一次
        if (request.locationCode() != null && !request.locationCode().isBlank()) {
            locationService.assignBatch(request.productId(), request.warehouseId(), batchNo,
                    request.locationCode().trim());
        }
        return ApiResponse.ok();
    }

    @PostMapping("/inventory/outbound")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE')")
    public ApiResponse outbound(@Valid @RequestBody StockRequest request) {
        service.outbound(request.productId(), request.warehouseId(), request.qty(), "MANUAL", null,
                request.remark() == null ? "手工出库" : request.remark());
        return ApiResponse.ok();
    }
}
