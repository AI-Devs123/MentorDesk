package com.nexusmind.gateway.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 过滤器：对 {@code Accept} 含 {@code text/event-stream} 的 SSE 请求统一埋点。
 *
 * <p><b>为什么用 WebFilter 而非改控制器</b>：网关存在 5 个 SSE 控制器
 * （agent / code / cognitive / curate / search），逐个改侵入大且易漏；集中在此处零侵入。
 *
 * <p><b>生命周期判定</b>：浏览器 SSE 拉流以 {@code Accept: text/event-stream} 发起，
 * 请求期即可判定，无需等待响应头。{@code chain.filter} 返回的 Mono 在整条 SSE 流完整写出
 * （complete / error / cancel）后才终止，故 {@code doOnSubscribe}（进入）与
 * {@code doFinally}（离开）恰好框住整个会话，活跃计数严格配对、绝不泄漏。
 */
@Component
public class SseMetricsWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(SseMetricsWebFilter.class);

    private final SseMetrics sseMetrics;

    public SseMetricsWebFilter(SseMetrics sseMetrics) {
        this.sseMetrics = sseMetrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        boolean sse = isSseRequest(exchange);
        return chain.filter(exchange)
                .doOnSubscribe(s -> {
                    if (sse) {
                        sseMetrics.onSessionOpen();
                    }
                })
                .doOnError(e -> {
                    if (sse) {
                        sseMetrics.onSessionError();
                    }
                })
                .doFinally(sig -> {
                    if (sse) {
                        sseMetrics.onSessionClose();
                    }
                });
    }

    private boolean isSseRequest(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getAccept().stream()
                .anyMatch(mt -> mt.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }
}
