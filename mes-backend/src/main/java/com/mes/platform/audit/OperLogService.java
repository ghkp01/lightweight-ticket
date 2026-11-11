package com.mes.platform.audit;

import com.mes.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志：拦截器自动落库，业务代码通过 touch 补充「哪张单、做了什么」。
 * touch 只写请求属性，真正入库在请求结束时由拦截器统一完成，避免一次操作写两条。
 *
 * <p>属于平台能力（所有业务模块都会写审计），因此放在 {@code platform.audit} 而不是 {@code system}：
 * 业务模块依赖它不算跨模块依赖，将来拆进程时它跟着 logback 一起走，不跟业务模块走。</p>
 */
@Service
public class OperLogService {

    static final String ATTR_SUMMARY = "mes.oper.summary";
    static final String ATTR_BIZ_TYPE = "mes.oper.bizType";
    static final String ATTR_BIZ_ID = "mes.oper.bizId";
    static final String ATTR_BIZ_NO = "mes.oper.bizNo";
    static final String ATTR_ACTION = "mes.oper.action";
    static final String ATTR_OPERATOR = "mes.oper.operator";
    static final String ATTR_ROLE = "mes.oper.role";
    static final String ATTR_START = "mes.oper.start";

    private final JdbcTemplate jdbcTemplate;

    public OperLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按保留期清理历史操作日志，返回删除条数 */
    public int purgeBefore(LocalDate date) {
        return jdbcTemplate.update("delete from oper_log where created_at::date < ?", date);
    }

    public record Entry(String module, String action, String bizType, Long bizId, String bizNo,
                        String summary, String detail, String operator, String operatorRole,
                        String ip, String userAgent, String httpMethod, String requestPath,
                        boolean success, String errorMsg, Integer durationMs) {
    }

    /** 业务侧补充本次操作的摘要与单号 */
    public void touch(String bizType, Long bizId, String bizNo, String summary) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        request.setAttribute(ATTR_BIZ_TYPE, bizType);
        request.setAttribute(ATTR_BIZ_ID, bizId);
        request.setAttribute(ATTR_BIZ_NO, bizNo);
        request.setAttribute(ATTR_SUMMARY, summary);
    }

    /** 登录这类还没建立安全上下文的操作，由业务代码告诉日志「操作人是谁」 */
    public void actor(String operator, String role) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        if (operator != null && !operator.isBlank()) {
            request.setAttribute(ATTR_OPERATOR, operator);
        }
        if (role != null && !role.isBlank()) {
            request.setAttribute(ATTR_ROLE, role);
        }
    }

    /** 业务侧覆盖默认动作，如「判定」「出库」 */
    public void action(String action) {
        HttpServletRequest request = currentRequest();
        if (request != null && action != null && !action.isBlank()) {
            request.setAttribute(ATTR_ACTION, action);
        }
    }

    public void write(Entry entry) {
        jdbcTemplate.update("""
                insert into oper_log (module, action, biz_type, biz_id, biz_no, summary, detail,
                                      operator, operator_role, ip, user_agent, http_method, request_path,
                                      success, error_msg, duration_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.module(), entry.action(), entry.bizType(), entry.bizId(), entry.bizNo(),
                clamp(entry.summary(), 500), entry.detail(), entry.operator(), entry.operatorRole(),
                entry.ip(), clamp(entry.userAgent(), 255), entry.httpMethod(), clamp(entry.requestPath(), 255),
                entry.success(), clamp(entry.errorMsg(), 500), entry.durationMs());
    }

    public List<Map<String, Object>> list(String module, String operator, String keyword,
                                          String success, String from, String to, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                select id, module, action, biz_type as "bizType", biz_id as "bizId", biz_no as "bizNo",
                       summary, detail, operator, operator_role as "operatorRole", ip,
                       user_agent as "userAgent", http_method as "httpMethod",
                       request_path as "requestPath", success, error_msg as "errorMsg",
                       duration_ms as "durationMs",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') as "createdAt"
                from oper_log where 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (module != null && !module.isBlank()) {
            sql.append(" and module = ?");
            params.add(module.trim());
        }
        if (operator != null && !operator.isBlank()) {
            sql.append(" and (operator ilike ? or operator_role ilike ?)");
            params.add("%" + operator.trim() + "%");
            params.add("%" + operator.trim() + "%");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (summary ilike ? or biz_no ilike ? or request_path ilike ?)");
            params.add("%" + keyword.trim() + "%");
            params.add("%" + keyword.trim() + "%");
            params.add("%" + keyword.trim() + "%");
        }
        if ("true".equalsIgnoreCase(success) || "false".equalsIgnoreCase(success)) {
            sql.append(" and success = ?");
            params.add(Boolean.parseBoolean(success));
        }
        if (from != null && !from.isBlank()) {
            sql.append(" and created_at >= cast(? as date)");
            params.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            sql.append(" and created_at < cast(? as date) + 1");
            params.add(to.trim());
        }
        int size = limit == null || limit <= 0 ? 200 : Math.min(limit, 1000);
        sql.append(" order by id desc limit ").append(size);
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modules", jdbcTemplate.queryForList(
                "select distinct module from oper_log order by module", String.class));
        result.put("operators", jdbcTemplate.queryForList(
                "select distinct operator from oper_log where operator is not null order by operator", String.class));
        Map<String, Object> stats = jdbcTemplate.queryForMap("""
                select count(*) as "total",
                       count(*) filter (where success = false) as "failed",
                       to_char(max(created_at), 'YYYY-MM-DD HH24:MI') as "latest"
                from oper_log
                """);
        result.put("total", stats.get("total"));
        result.put("failed", stats.get("failed"));
        result.put("latest", stats.get("latest"));
        return result;
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 供拦截器兜底使用：没有登录态时记为 system */
    static String operatorName() {
        String name = CurrentUser.name();
        return name == null || name.isBlank() ? "system" : name;
    }
}
