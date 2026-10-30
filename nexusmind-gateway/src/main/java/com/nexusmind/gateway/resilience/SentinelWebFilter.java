package com.nexusmind.gateway.resilience;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关接入层限流/熔断过滤器（WebFlux）。
 *
 * <p><b>设计</b>：采用「准入即放行」模式——在请求进入时同步做 {@code SphU.entry} 准入判断，
 * 判定后立即 {@code exit()}，再放行 {@code chain}。这样把 Sentinel 当作纯「接入门禁」，
 * 限定的是<b>接入速率（QPS）</b>而非在途并发，且 entry/exit 在同一（事件循环）线程上完成，
 * 彻底规避 WebFlux 响应式上下文跨线程导致 Sentinel 上下文错乱的经典坑。
 *
 * <p>命中限流/熔断时直接返回 {@code 429 Too Many Requests} + JSON，不进入后续链路。
 * {@code /actuator}、{@code /swagger-ui} 等排除路径直通。资源名形如 {@code GET:/api/v1/agent}。
 */
@Component
@Order(-50)
public class SentinelWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(SentinelWebFilter.class);

    private final SentinelProperties props;
    private final ObjectMapper objectMapper;

    public SentinelWebFilter(SentinelProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (isExcluded(path)) {
            return chain.filter(exchange);
        }
        org.springframework.http.HttpMethod method = exchange.getRequest().getMethod();
        String resource = (method != null ? method.name() : "GET") + ":" + path;
        Entry entry;
        try {
            entry = SphU.entry(resource);
        } catch (BlockException ex) {
            log.warn("[Sentinel] 网关限流/熔断命中 resource={} -> 返回 429", resource);
            return writeBlocked(exchange, resource);
        }
        // 准入即放行：立即退出，仅做接入层 QPS 门禁（不统计在途请求）
        entry.exit();
        return chain.filter(exchange);
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

    private Mono<Void> writeBlocked(ServerWebExchange exchange, String resource) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "TOO_MANY_REQUESTS");
        body.put("message", "请求被 Sentinel 限流或熔断: " + resource);
        body.put("resource", resource);
        body.put("timestamp", System.currentTimeMillis());
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"code\":\"TOO_MANY_REQUESTS\",\"resource\":\"" + resource + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
