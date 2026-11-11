package com.mes.offline;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 幂等过滤器：只对带 X-Idempotency-Key 的写请求生效。
 * <p>
 * 移动端断网时把请求存本地队列，恢复后重传；如果第一次其实已经到达服务端（只是响应没回去），
 * 重传会撞上同一个 key —— 这里直接把上次的响应体原样返回，业务不会再执行一遍。
 * <p>
 * 加了 key 但没有记录的请求按正常流程走，执行完把响应快照存起来；
 * 5xx 不缓存，让手机下次重试仍有机会成功。
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Idempotency-Key";
    public static final String REPLAY_HEADER = "X-Idempotent-Replay";

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** 登录、令牌这类接口不要缓存，令牌有时效性 */
    private static final Set<String> SKIP_PREFIX = Set.of("/api/auth/", "/api/open/", "/api/public/");

    private final OfflineSyncService service;

    public IdempotencyFilter(OfflineSyncService service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank() || !shouldHandle(request) || !service.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        Map<String, Object> cached = service.cached(key);
        if (cached != null) {
            Object status = cached.get("statusCode");
            Object body = cached.get("responseBody");
            response.setStatus(status == null ? 200 : ((Number) status).intValue());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader(REPLAY_HEADER, "true");
            if (body != null) {
                response.getWriter().write(String.valueOf(body));
            }
            response.flushBuffer();
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            byte[] bytes = wrapper.getContentAsByteArray();
            if (wrapper.getStatus() < 500 && bytes.length > 0) {
                service.remember(key, request.getMethod(), request.getRequestURI(),
                        wrapper.getStatus(), new String(bytes, StandardCharsets.UTF_8));
            }
            wrapper.copyBodyToResponse();
        }
    }

    private boolean shouldHandle(HttpServletRequest request) {
        if (!MUTATING.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return false;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return false;
        }
        for (String prefix : SKIP_PREFIX) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
