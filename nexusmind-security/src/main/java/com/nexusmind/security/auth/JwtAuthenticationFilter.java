package com.nexusmind.security.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * JWT 认证过滤器 - 拦截请求并验证 Token
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // 不需要认证的路径
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/swagger-ui",
            "/v3/api-docs",
            "/actuator/health"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        String method = request.getMethod();

        logger.debug("Request: {} {}", method, uri);

        // 检查是否是公开路径
        if (isPublicPath(uri)) {
            logger.debug("Public path, skipping authentication: {}", uri);
            filterChain.doFilter(request, response);
            return;
        }

        // OPTIONS 预检请求直接放过
        if ("OPTIONS".equalsIgnoreCase(method)) {
            logger.debug("OPTIONS request, skipping authentication");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String authHeader = request.getHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new JwtException("缺少或无效的 Authorization header");
            }

            String token = authHeader.substring(7);
            Claims claims = jwtTokenProvider.validateAndParseToken(token);
            logger.debug("Token validated successfully");

            // 将身份写入 SecurityContext，供 Spring Security 授权层（anyRequest().authenticated()）放行
            String role = claims.get("role", String.class);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            claims.getSubject(),
                            token,
                            role != null ? List.of(new SimpleGrantedAuthority("ROLE_" + role)) : List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (JwtException e) {
            // 认证失败是我们自己的响应：401 + 明确原因
            logger.warn("Token validation failed: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\": \"Token 验证失败: " + e.getMessage() + "\"}");
            return;
        }
        // 注意：不再用 catch (Exception) 兜底 doFilter。
        // 业务异常一旦在这里被改写成 "服务器错误" 空响应，
        // 真实根因（堆栈）就彻底消失，前端只看到无信息量的 500。

        // Token 验证成功，继续处理请求（STATELESS 会话，结束后清理上下文防线程污染）
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isPublicPath(String uri) {
        return PUBLIC_PATHS.stream().anyMatch(uri::startsWith);
    }

    /**
     * ERROR dispatch（如无匹配 handler 时转发 /error）时也要执行本过滤器。
     * OncePerRequestFilter 默认跳过 ERROR dispatch，导致该轮请求没有任何人写
     * SecurityContext，AuthorizationFilter 判定匿名后直接返回 403 空响应，
     * 把本应暴露的 404 掩盖成 403（前端看到的就是“莫名 403”）。
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * ASYNC dispatch 时同样必须执行本过滤器。
     *
     * SSE / Flux 端点在 Servlet 栈是异步请求：首轮 REQUEST 派发认证通过后启动异步，
     * 响应写回前会再来一次 ASYNC 派发。OncePerRequestFilter 默认在 ASYNC 派发时跳过，
     * 于是这一轮没有任何人写 SecurityContext，AuthorizationFilter（Security 6 默认对所有
     * 派发类型生效）判定匿名 → 直接返回 403。
     *
     * 现象：/api/v1/cognitive/stream、/api/v1/agent/stream 等一切流式端点即使用合法 Token
     * 也恒 403，而同步端点（如 /api/v1/health）正常 200。
     *
     * 返回 false 让过滤器在 ASYNC 派发时重新校验同一请求头里的 Token 并写入上下文。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
