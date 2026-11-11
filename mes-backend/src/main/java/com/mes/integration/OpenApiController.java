package com.mes.integration;

import com.mes.common.ApiResponse;
import com.mes.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对外开放接口（给 ERP / 财务等外部系统读数据）。
 *
 * 鉴权走 X-API-Token，不依赖登录态；每个令牌按权限范围放行，
 * 并按分钟做简单的调用次数限制（默认 120 次/分钟/令牌），避免被脚本刷爆。
 * 所有接口只读，写入仍走站内流程，保证账实不被外部系统绕过。
 */
@RestController
@RequestMapping("/api/open/v1")
public class OpenApiController {

    private static final int RATE_LIMIT_PER_MINUTE = 120;

    private final ApiTokenService tokenService;
    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, int[]> rateCounter = new ConcurrentHashMap<>();

    public OpenApiController(ApiTokenService tokenService, JdbcTemplate jdbcTemplate) {
        this.tokenService = tokenService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/ping")
    public ApiResponse ping(@RequestHeader(value = "X-API-Token", required = false) String token) {
        ApiTokenService.TokenContext context = check(token, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", context.name());
        result.put("scopes", context.scopes());
        result.put("rateLimitPerMinute", RATE_LIMIT_PER_MINUTE);
        return ApiResponse.ok(result);
    }

    @GetMapping("/work-orders")
    public ApiResponse workOrders(@RequestHeader(value = "X-API-Token", required = false) String token,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        check(token, "WORK_ORDER");
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (Math.max(page, 1) - 1) * safeSize;
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and w.status = ? ");
            args.add(status.trim());
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from rm_work_order w " + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(offset);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select w.work_no as "workNo", p.code as "productCode", p.name as "productName",
                       w.status, w.plan_qty as "planQty", w.done_qty as "doneQty",
                       w.qualified_qty as "qualifiedQty",
                       to_char(w.schedule_date, 'YYYY-MM-DD') as "scheduleDate",
                       to_char(w.updated_at, 'YYYY-MM-DD HH24:MI') as "updatedAt"
                from rm_work_order w
                join rm_product p on p.id = w.product_id
                """ + where + " order by w.id desc limit ? offset ?", pageArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total == null ? 0 : total);
        result.put("page", Math.max(page, 1));
        result.put("size", safeSize);
        return ApiResponse.ok(result);
    }

    @GetMapping("/inventory")
    public ApiResponse inventory(@RequestHeader(value = "X-API-Token", required = false) String token,
                                 @RequestParam(required = false) String keyword) {
        check(token, "INVENTORY");
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return ApiResponse.ok(jdbcTemplate.queryForList("""
                select p.code as "productCode", p.name as "productName", p.unit, p.category,
                       w.code as "warehouseCode", w.name as "warehouseName",
                       i.qty, i.locked_qty as "lockedQty", (i.qty - i.locked_qty) as "available",
                       p.avg_cost as "avgCost", round(i.qty * p.avg_cost, 2) as "stockAmount",
                       p.safety_stock as "safetyStock"
                from rm_inventory i
                join rm_product p on p.id = i.product_id
                join rm_warehouse w on w.id = i.warehouse_id
                where p.code ilike ? or p.name ilike ?
                order by p.code, w.id
                """, like, like));
    }

    @GetMapping("/materials")
    public ApiResponse materials(@RequestHeader(value = "X-API-Token", required = false) String token,
                                 @RequestParam(required = false) String keyword) {
        check(token, "MATERIAL");
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return ApiResponse.ok(jdbcTemplate.queryForList("""
                select p.code, p.name, p.spec, p.category, p.unit, p.price, p.cost,
                       p.avg_cost as "avgCost", p.safety_stock as "safetyStock", p.enabled
                from rm_product p
                where p.code ilike ? or p.name ilike ?
                order by p.code
                """, like, like));
    }

    @GetMapping("/cost/work-orders")
    public ApiResponse costWorkOrders(@RequestHeader(value = "X-API-Token", required = false) String token,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        check(token, "COST");
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (Math.max(page, 1) - 1) * safeSize;
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from rm_work_order where costed_at is not null
                """, Long.class);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select w.work_no as "workNo", p.code as "productCode", p.name as "productName",
                       w.qualified_qty as "qualifiedQty",
                       w.material_cost as "materialCost", w.labor_cost as "laborCost",
                       w.overhead_cost as "overheadCost", w.total_cost as "totalCost",
                       w.unit_cost as "unitCost",
                       to_char(w.costed_at, 'YYYY-MM-DD HH24:MI') as "costedAt"
                from rm_work_order w
                join rm_product p on p.id = w.product_id
                where w.costed_at is not null
                order by w.id desc limit ? offset ?
                """, safeSize, offset);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total == null ? 0 : total);
        result.put("page", Math.max(page, 1));
        result.put("size", safeSize);
        return ApiResponse.ok(result);
    }

    /** 校验令牌 + 权限 + 限流；scope 为空表示只校验令牌有效 */
    private ApiTokenService.TokenContext check(String token, String scope) {
        ApiTokenService.TokenContext context = tokenService.authenticate(token);
        if (scope != null && !context.has(scope)) {
            throw new BizException(4032, "该令牌没有 " + scope + " 范围权限，请让管理员调整");
        }
        int minute = (int) (System.currentTimeMillis() / 60000);
        int[] counter = rateCounter.computeIfAbsent(context.id(), key -> new int[]{minute, 0});
        synchronized (counter) {
            if (counter[0] != minute) {
                counter[0] = minute;
                counter[1] = 0;
            }
            counter[1] = counter[1] + 1;
            if (counter[1] > RATE_LIMIT_PER_MINUTE) {
                throw new BizException(4033, "调用过于频繁，每分钟最多 " + RATE_LIMIT_PER_MINUTE + " 次");
            }
        }
        return context;
    }

    /** 便于测试与排障：当前活跃令牌数（不清洗，就不做统计了） */
    protected int activeTokenCount() {
        AtomicInteger count = new AtomicInteger();
        rateCounter.forEach((key, value) -> count.incrementAndGet());
        return count.get();
    }
}
