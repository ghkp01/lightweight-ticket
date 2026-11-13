package com.mes.masterdata;

import com.mes.common.ApiResponse;
import jakarta.validation.Valid;
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

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MasterDataController {

    private final MasterDataService service;

    public MasterDataController(MasterDataService service) {
        this.service = service;
    }

    @GetMapping("/options/customers")
    public ApiResponse customerOptions(@RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.customerOptions(keyword, limit));
    }

    @GetMapping("/options/products")
    public ApiResponse productOptions(@RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "20") int limit,
                                      @RequestParam(defaultValue = "false") boolean routingOnly) {
        return ApiResponse.ok(service.productOptions(keyword, limit, routingOnly));
    }

    @GetMapping("/options/warehouses")
    public ApiResponse warehouseOptions(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.warehouseOptions(keyword, type, limit));
    }

    @GetMapping("/customers")
    public ApiResponse customers(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.customerPage(keyword, page, size));
        }
        return ApiResponse.ok(service.customers(keyword));
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    public ApiResponse createCustomer(@Valid @RequestBody MasterDataService.CustomerRequest request) {
        return ApiResponse.ok(service.createCustomer(request));
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    public ApiResponse updateCustomer(@PathVariable long id,
                                      @Valid @RequestBody MasterDataService.CustomerRequest request) {
        service.updateCustomer(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/customers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse deleteCustomer(@PathVariable long id) {
        service.deleteCustomer(id);
        return ApiResponse.ok();
    }

    @GetMapping("/products")
    public ApiResponse products(@RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String category,
                                @RequestParam(required = false) String customFieldKey,
                                @RequestParam(required = false) String customFieldValue,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(defaultValue = "20") int size) {
        if (page != null) {
            return ApiResponse.ok(service.productPage(keyword, category, customFieldKey,
                    customFieldValue, page, size));
        }
        return ApiResponse.ok(service.products(keyword, category, customFieldKey, customFieldValue));
    }

    @PostMapping("/products")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse createProduct(@Valid @RequestBody MasterDataService.ProductRequest request) {
        return ApiResponse.ok(service.createProduct(request));
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse updateProduct(@PathVariable long id,
                                     @Valid @RequestBody MasterDataService.ProductRequest request) {
        service.updateProduct(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/products/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse deleteProduct(@PathVariable long id) {
        service.deleteProduct(id);
        return ApiResponse.ok();
    }

    @GetMapping("/products/{id}/bom")
    public ApiResponse bom(@PathVariable long id) {
        return ApiResponse.ok(service.bom(id));
    }

    @GetMapping("/products/{id}/routing")
    public ApiResponse routing(@PathVariable long id) {
        return ApiResponse.ok(service.routing(id));
    }

    @GetMapping("/products/{id}/routing-changes")
    public ApiResponse routingChanges(@PathVariable long id) {
        return ApiResponse.ok(service.routingChanges(id));
    }

    @PostMapping("/products/{id}/routing/copy")
    @PreAuthorize("hasAuthority('routing:save')")
    public ApiResponse copyRouting(@PathVariable long id,
                                   @RequestBody Map<String, Object> body) {
        Long sourceId = body.get("sourceProductId") == null ? null
                : Long.valueOf(String.valueOf(body.get("sourceProductId")));
        Boolean overwrite = body.get("overwrite") == null ? null
                : Boolean.valueOf(String.valueOf(body.get("overwrite")));
        return ApiResponse.ok(service.copyRouting(id, sourceId, overwrite));
    }

    @PutMapping("/products/{id}/routing")
    @PreAuthorize("hasAuthority('routing:save')")
    public ApiResponse saveRouting(@PathVariable long id,
                                   @Valid @RequestBody MasterDataService.RoutingRequest request) {
        service.saveRouting(id, request);
        return ApiResponse.ok();
    }

    @GetMapping("/warehouses")
    public ApiResponse warehouses(@RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        if (page != null) {
            return ApiResponse.ok(service.pageWarehouses(page, size == null ? 20 : size));
        }
        return ApiResponse.ok(service.warehouses());
    }

    @PostMapping("/warehouses")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse createWarehouse(@Valid @RequestBody MasterDataService.WarehouseRequest request) {
        return ApiResponse.ok(service.createWarehouse(request));
    }

    @PutMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse updateWarehouse(@PathVariable long id,
                                       @Valid @RequestBody MasterDataService.WarehouseRequest request) {
        service.updateWarehouse(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse deleteWarehouse(@PathVariable long id) {
        service.deleteWarehouse(id);
        return ApiResponse.ok();
    }
}
