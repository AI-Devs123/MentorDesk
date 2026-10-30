package com.nexusmind.supervisor.resilience;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Supervisor 接入层限流/熔断过滤器（Servlet / jakarta）。
 *
 * <p>与网关同构，采用「准入即放行」模式：请求进入时同步 {@code SphU.entry} 判定，立即 {@code exit()}，
 * 再放行 {@code chain}。Servlet 每请求单线程模型保证 entry/exit 在同一线程，无上下文错乱风险。
 * 命中限流/熔断返回 {@code 429 Too Many Requests} + JSON；{@code /actuator} 等排除路径直通。
 *
 * <p>注意：未使用官方 {@code sentinel-spring-webmvc-adapter}——其基于 {@code javax.servlet}，
 * 在 Spring Boot 3（jakarta）下无法加载。此处用原生 {@code jakarta.servlet.Filter} 自研接入。
 */
@Component
@Order(-50)
public class SentinelServletFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SentinelServletFilter.class);

    private final SentinelProperties props;
    private final ObjectMapper objectMapper;

    public SentinelServletFilter(SentinelProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest) || !props.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String path = httpRequest.getRequestURI();
        if (isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }
        String resource = httpRequest.getMethod() + ":" + path;
        Entry entry;
        try {
            entry = SphU.entry(resource);
        } catch (BlockException ex) {
            log.warn("[Sentinel] supervisor 限流/熔断命中 resource={} -> 返回 429", resource);
            writeBlocked((HttpServletResponse) response, resource);
            return;
        }
        // 准入即放行：立即退出，仅做接入层 QPS 门禁
        entry.exit();
        chain.doFilter(request, response);
    }

    private boolean isExcluded(String path) {
        if (path == null) {
            return true;
        }
        for (String prefix : props.getExcludedPaths()) {
            if (path.equals(prefix) || path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void writeBlocked(HttpServletResponse response, String resource) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "TOO_MANY_REQUESTS");
        body.put("message", "请求被 Sentinel 限流或熔断: " + resource);
        body.put("resource", resource);
        body.put("timestamp", System.currentTimeMillis());
        try {
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            response.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"resource\":\"" + resource + "\"}");
        }
    }
}
