package com.mes.quality;

import com.mes.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/inspections")
public class QualityController {

    private final QualityService service;
    private final InspectionItemService itemService;
    private final SamplingService samplingService;

    public QualityController(QualityService service, InspectionItemService itemService,
                             SamplingService samplingService) {
        this.service = service;
        this.itemService = itemService;
        this.samplingService = samplingService;
    }

    public record ItemSaveRequest(Long productId, Long processId, List<InspectionItemService.ItemRequest> items) {
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String type,
                            @RequestParam(required = false) String result,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(type, result, keyword, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('quality:create')")
    public ApiResponse create(@RequestBody QualityService.InspectionRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/summary")
    public ApiResponse summary() {
        return ApiResponse.ok(service.summary());
    }

    /** 检验项模板：按产品维护通用项，按工序维护该道工序的检查项 */
    @GetMapping("/items")
    public ApiResponse items(@RequestParam(required = false) Long productId,
                            @RequestParam(required = false) Long processId) {
        return ApiResponse.ok(itemService.list(productId, processId));
    }

    @PutMapping("/items")
    @PreAuthorize("hasAuthority('quality:item')")
    public ApiResponse saveItems(@RequestBody ItemSaveRequest request) {
        return ApiResponse.ok(itemService.save(request.productId(), request.processId(), request.items()));
    }

    /** 抽样方案：全检或按批量分档抽检，返回样本量与接收 / 拒收数 */
    @GetMapping("/sample-plan")
    public ApiResponse samplePlan(@RequestParam BigDecimal lotQty,
                                  @RequestParam(required = false) String plan,
                                  @RequestParam(required = false) BigDecimal aql) {
        return ApiResponse.ok(samplingService.plan(lotQty, plan, aql));
    }

    /** 不良项 Top：按检验项统计不合格次数与不良数 */
    @GetMapping("/item-defects")
    public ApiResponse itemDefects(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(service.itemDefectTop(days));
    }
}
