package com.mes.system;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 静态资源缓存策略：
 * 页面入口（/ 与 *.html）不缓存，避免发版后浏览器仍用旧 index.html 去加载已删除的旧 chunk；
 * 带哈希的 /assets/** 允许长缓存，内容变了文件名就变了。
 */
@Component
public class StaticCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean isPage = path.equals("/") || path.equals("/index.html") || path.endsWith(".html");
        if (isPage) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            response.setHeader("Pragma", "no-cache");
        } else if (path.startsWith("/assets/")) {
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        }
        chain.doFilter(request, response);
    }
}
