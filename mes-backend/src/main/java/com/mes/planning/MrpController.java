package com.mes.planning;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 物料需求计算（MRP）：先算建议，人确认后再生成采购申请。
 */
@RestController
@RequestMapping("/api/mrp")
@PreAuthorize("hasAnyRole('ADMIN', 'PLANNER', 'WAREHOUSE')")
public class MrpController {

    private final MrpService service;
    private final MultiLevelMrpService multiLevelService;
    private final SalesMrpService salesMrpService;

    public MrpController(MrpService service, MultiLevelMrpService multiLevelService,
                         SalesMrpService salesMrpService) {
        this.service = service;
        this.multiLevelService = multiLevelService;
        this.salesMrpService = salesMrpService;
    }

    /** 跑一次物料需求：缺多少、什么时候要、建议哪天下单 */
    @GetMapping("/run")
    public ApiResponse run(@RequestParam(required = false) Boolean includeScheduled,
                           @RequestParam(required = false) Boolean onlyShortage,
                           @RequestParam(required = false) Integer horizonDays) {
        return ApiResponse.ok(service.run(new MrpService.MrpQuery(includeScheduled, onlyShortage, horizonDays)));
    }

    /** 多层展开：把半成品继续拆到原料，给出「该开工单多少」与「该买多少」 */
    @GetMapping("/explode")
    public ApiResponse explode(@RequestParam(required = false) Boolean includeScheduled,
                               @RequestParam(required = false) Integer horizonDays,
                               @RequestParam(required = false) Integer maxLevel) {
        return ApiResponse.ok(multiLevelService.explode(
                new MultiLevelMrpService.ExplodeQuery(includeScheduled, horizonDays, maxLevel)));
    }

    /** 把勾选的缺口行生成一张采购申请（草稿） */
    @PostMapping("/requisitions")
    @PreAuthorize("hasAuthority('purchase:request')")
    public ApiResponse createRequisition(@RequestBody MrpService.CreateRequest request) {
        return ApiResponse.ok(service.createRequisition(request));
    }

    /**
     * 销售订单 MRP：成品先扣库存，再逐层展开；自制件建工单、外购件建采购申请。
     * 一次运行只处理一张已确认订单，重复运行会被工单幂等校验拦住。
     */
    @PostMapping("/sales-orders/{id}/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
    public ApiResponse runSalesOrder(@PathVariable long id,
                                     @RequestParam(required = false) Integer maxLevel) {
        return ApiResponse.ok(salesMrpService.run(id, maxLevel));
    }

    /**
     * 全局 MRP 的运行范围：已确认、未生成工单的订单，供物料需求页展示与逐单运行。
     */
    @GetMapping("/sales-orders/open")
    public ApiResponse openSalesOrders() {
        return ApiResponse.ok(salesMrpService.openOrders());
    }

    /**
     * 全局 MRP：所有已确认、未生成工单的销售订单一起算。
     * 成品库存按交期先到先得做 ATP 预留，半成品与采购需求按物料汇总后只生成一批单据。
     */
    @PostMapping("/sales-orders/global/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'PLANNER')")
    public ApiResponse runGlobalSalesOrders(@RequestParam(required = false) Integer maxLevel) {
        return ApiResponse.ok(salesMrpService.runGlobal(maxLevel));
    }
}
