package com.mes.platform.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 操作日志拦截器：所有写操作自动落一条，查询默认不记（附件下载单独记）。
 * 业务代码可以用 OperLogService.touch 补充摘要，没补充就用「方法 + 路径」兜底。
 *
 * <p>和 OperLogService 一起属于平台能力，注册在 {@code com.mes.system.WebMvcConfig} 里
 * （那个配置类只是「挂载点」，留在系统管理模块不影响业务模块的依赖方向）。</p>
 */
@Component
public class OperLogInterceptor implements HandlerInterceptor {

    private static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("auth", "登录认证"),
            Map.entry("files", "附件"),
            Map.entry("system", "系统管理"),
            Map.entry("sales", "销售订单"),
            Map.entry("sales-quotes", "报价管理"),
            Map.entry("shipments", "发货管理"),
            Map.entry("returns", "退货管理"),
            Map.entry("production", "生产工单"),
            Map.entry("planning", "排产计划"),
            Map.entry("bom-tasks", "BOM 任务"),
            Map.entry("workshop", "车间看板"),
            Map.entry("inventory", "库存管理"),
            Map.entry("warehouse", "仓储作业"),
            Map.entry("transfer", "库存调拨"),
            Map.entry("stocktake", "库存盘点"),
            Map.entry("quality", "质量检验"),
            Map.entry("inspections", "质量检验"),
            Map.entry("defects", "不良处置"),
            Map.entry("documents", "工艺文件"),
            Map.entry("trace", "批次追溯"),
            Map.entry("org", "组织架构"),
            Map.entry("devices", "设备台账"),
            Map.entry("workcenters", "工作中心"),
            Map.entry("customers", "客户管理"),
            Map.entry("suppliers", "供应商"),
            Map.entry("purchase-orders", "采购订单"),
            Map.entry("purchase-requisitions", "采购申请"),
            Map.entry("purchase-rfqs", "询价比价"),
            Map.entry("ap", "发票与应付"),
            Map.entry("supplier-prices", "价格库"),
            Map.entry("products", "产品与 BOM"),
            Map.entry("processes", "工序管理"),
            Map.entry("warehouses", "仓库管理"),
            Map.entry("notices", "消息通知"),
            Map.entry("reports", "报表"),
            Map.entry("andon", "异常安灯")
    );

    private static final Map<String, String> SYSTEM_SUB = Map.of(
            "configs", "系统配置",
            "logs", "操作日志",
            "users", "用户管理",
            "roles", "角色权限",
            "orgs", "组织架构"
    );

    private static final Pattern DOWNLOAD = Pattern.compile("^/api/files/[0-9]+/download$");

    private final OperLogService operLogService;

    public OperLogInterceptor(OperLogService operLogService) {
        this.operLogService = operLogService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (shouldLog(request)) {
            request.setAttribute(OperLogService.ATTR_START, System.currentTimeMillis());
        }
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        Object start = request.getAttribute(OperLogService.ATTR_START);
        if (!(start instanceof Long startedAt)) {
            return;
        }
        String path = request.getRequestURI();
        String summary = (String) request.getAttribute(OperLogService.ATTR_SUMMARY);
        String actionAttr = (String) request.getAttribute(OperLogService.ATTR_ACTION);
        Long bizId = null;
        Object bizIdAttr = request.getAttribute(OperLogService.ATTR_BIZ_ID);
        if (bizIdAttr instanceof Long value) {
            bizId = value;
        } else if (bizIdAttr instanceof Number number) {
            bizId = number.longValue();
        }

        boolean success = ex == null && response.getStatus() < 400;
        String error = ex == null ? null : ex.getMessage();
        String query = request.getQueryString();
        String detail = query == null || query.isBlank() ? null : "参数：" + query;

        String operator = (String) request.getAttribute(OperLogService.ATTR_OPERATOR);
        if (operator == null || operator.isBlank()) {
            operator = OperLogService.operatorName();
        }
        String role = (String) request.getAttribute(OperLogService.ATTR_ROLE);
        if (role == null || role.isBlank()) {
            role = roleName();
        }

        OperLogService.Entry entry = new OperLogService.Entry(
                moduleOf(path),
                actionAttr == null || actionAttr.isBlank() ? actionOf(request.getMethod()) : actionAttr,
                (String) request.getAttribute(OperLogService.ATTR_BIZ_TYPE),
                bizId,
                (String) request.getAttribute(OperLogService.ATTR_BIZ_NO),
                summary == null || summary.isBlank()
                        ? request.getMethod() + " " + path
                        : summary,
                detail,
                operator,
                role,
                clientIp(request),
                request.getHeader("User-Agent"),
                request.getMethod(),
                path,
                success,
                error,
                (int) (System.currentTimeMillis() - startedAt));
        try {
            operLogService.write(entry);
        } catch (Exception ignored) {
            // 日志失败不能影响业务响应
        }
    }

    private boolean shouldLog(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return false;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return DOWNLOAD.matcher(path).matches();
        }
        return true;
    }

    static String moduleOf(String path) {
        String[] parts = path.split("/");
        // ["", "api", "system", "configs"]
        String first = parts.length > 2 ? parts[2] : "";
        if ("system".equals(first) && parts.length > 3 && SYSTEM_SUB.containsKey(parts[3])) {
            return SYSTEM_SUB.get(parts[3]);
        }
        if ("files".equals(first)) {
            return "附件";
        }
        return MODULES.getOrDefault(first, "其他");
    }

    static String actionOf(String method) {
        return switch (method == null ? "" : method.toUpperCase()) {
            case "POST" -> "新增";
            case "PUT", "PATCH" -> "修改";
            case "DELETE" -> "删除";
            case "GET" -> "查看";
            default -> method == null ? "操作" : method;
        };
    }

    private static String roleName() {
        String role = com.mes.security.CurrentUser.role();
        return role == null ? null : role;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
