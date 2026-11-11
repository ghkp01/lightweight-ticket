package com.mes.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 改密前只放行登录态相关的接口，业务接口一律拦住 */
    private static final List<String> PASSWORD_CHANGE_ALLOWED_PREFIXES = List.of(
            "/api/auth/", "/actuator/health", "/actuator/info");

    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;
    /** 关掉后不再强制改密，只给内部有特殊诉求的部署用 */
    private final boolean forcePasswordChange;

    public JwtAuthFilter(JwtService jwtService, JdbcTemplate jdbcTemplate,
                         @Value("${mes.security.force-password-change:true}") boolean forcePasswordChange) {
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
        this.forcePasswordChange = forcePasswordChange;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        Claims claims;
        try {
            claims = jwtService.parse(header.substring(7));
        } catch (Exception invalidToken) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }
        String role = String.valueOf(claims.get("role"));
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        try {
            jdbcTemplate.query("""
                    select rp.permission from sys_role_permission rp
                    join sys_role r on r.id = rp.role_id
                    where r.code = ?
                    """, rs -> {
                authorities.add(new SimpleGrantedAuthority(rs.getString("permission")));
            }, role);
        } catch (Exception ignored) {
            // 权限表查不到不影响登录态本身，按角色名鉴权即可
        }
        var auth = new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 拿着「还没改初始密码」的令牌，只能改密码，不能干活
        if (forcePasswordChange
                && Boolean.TRUE.equals(claims.get(JwtService.CLAIM_MUST_CHANGE_PASSWORD))
                && !allowedBeforePasswordChange(request.getRequestURI())) {
            rejectPasswordNotChanged(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowedBeforePasswordChange(String uri) {
        if (uri == null) {
            return false;
        }
        for (String prefix : PASSWORD_CHANGE_ALLOWED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void rejectPasswordNotChanged(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":4023,\"message\":\"首次登录请先修改密码\",\"data\":{\"mustChangePassword\":true}}");
    }
}
