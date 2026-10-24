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
            logger.debug("Authorization header: {}", authHeader != null ? "present" : "missing");

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

            // Token 验证成功，继续处理请求（STATELESS 会话，结束后清理上下文防线程污染）
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }

        } catch (JwtException e) {
            logger.warn("Token validation failed: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"Token 验证失败: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            logger.error("Unexpected error in JWT filter", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"服务器错误\"}");
        }
    }

    private boolean isPublicPath(String uri) {
        return PUBLIC_PATHS.stream().anyMatch(uri::startsWith);
    }
}
