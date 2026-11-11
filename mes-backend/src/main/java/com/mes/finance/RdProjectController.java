package com.mes.finance;

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
 * 研发费用辅助账：项目与费用归集。
 */
@RestController
@RequestMapping("/api/finance/rd-projects")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public class RdProjectController {

    private final RdProjectService service;

    public RdProjectController(RdProjectService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse projects(@RequestParam(required = false) Integer year,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.projects(year, keyword, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse create(@RequestBody RdProjectService.ProjectRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping("/{id}/expenses")
    public ApiResponse expenses(@PathVariable long id,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.expenses(id, page, size));
    }

    @PostMapping("/{id}/expenses")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse addExpense(@PathVariable long id,
                                  @RequestBody RdProjectService.ExpenseRequest request) {
        return ApiResponse.ok(service.addExpense(id, request));
    }

    @DeleteMapping("/expenses/{expenseId}")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse deleteExpense(@PathVariable long expenseId) {
        service.deleteExpense(expenseId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('finance:tax')")
    public ApiResponse close(@PathVariable long id) {
        service.closeProject(id);
        return ApiResponse.ok();
    }

    @GetMapping("/summary")
    public ApiResponse summary(@RequestParam int year) {
        return ApiResponse.ok(service.summary(year));
    }
}
