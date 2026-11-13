package com.mes.warehouse;

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

/**
 * 库位：库位维护、批次分布、移库与移库记录。
 */
@RestController
@RequestMapping("/api/locations")
@PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE', 'PLANNER')")
public class LocationController {

    private final LocationService service;

    public LocationController(LocationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) Long warehouseId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.page(warehouseId, keyword, page, size == null ? 20 : size));
        }
        return ApiResponse.ok(service.list(warehouseId, keyword));
    }

    @GetMapping("/options")
    public ApiResponse options(@RequestParam(required = false) Long warehouseId,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.options(warehouseId, keyword, limit));
    }

    /** 已上架 / 未指定库位的批次与数量，给库位页做概览，避免误以为仓库空了 */
    @GetMapping("/stats")
    public ApiResponse stats(@RequestParam(required = false) Long warehouseId) {
        return ApiResponse.ok(service.stats(warehouseId));
    }

    @PostMapping
    public ApiResponse save(@RequestBody LocationService.LocationRequest request) {
        return ApiResponse.ok(java.util.Map.of("id", service.save(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/batches")
    public ApiResponse batches(@RequestParam(required = false) Long warehouseId,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.batchLocationsPage(warehouseId, keyword, page,
                    size == null ? 20 : size));
        }
        return ApiResponse.ok(service.batchLocations(warehouseId, keyword));
    }

    @PostMapping("/move")
    public ApiResponse move(@RequestBody LocationService.MoveRequest request) {
        return ApiResponse.ok(service.move(request));
    }

    @GetMapping("/moves")
    public ApiResponse moves(@RequestParam(required = false) Long warehouseId,
                             @RequestParam(defaultValue = "100") int limit,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.movesPage(warehouseId, page, size == null ? 20 : size));
        }
        return ApiResponse.ok(service.moves(warehouseId, limit));
    }
}
