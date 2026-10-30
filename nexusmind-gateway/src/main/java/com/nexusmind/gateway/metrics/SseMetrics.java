package com.nexusmind.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 网关 SSE 指标（切片 1 可观测性基座）。
 *
 * <ul>
 *   <li>{@code nexusmind_sse_active_sessions}：当前活跃 SSE 会话数（Gauge，绑定 SseConnectionRegistry）；</li>
 *   <li>{@code nexusmind_sse_sessions_total}：SSE 会话累计开启数；</li>
 *   <li>{@code nexusmind_sse_errors_total}：发生异常的 SSE 会话数。</li>
 * </ul>
 *
 * <p>命名空间统一 {@code nexusmind_*}，并由 {@code management.metrics.tags.application}
 * 自动附加 {@code application} 标签，便于在 Prometheus 中按服务区分。
 */
@Component
public class SseMetrics {

    private final SseConnectionRegistry registry;
    private final Counter sessionsTotal;
    private final Counter errorsTotal;

    public SseMetrics(MeterRegistry meterRegistry, SseConnectionRegistry registry) {
        this.registry = registry;
        Gauge.builder("nexusmind_sse_active_sessions", registry, r -> (double) r.activeCount())
                .description("当前活跃的 SSE（Server-Sent Events）会话数")
                .register(meterRegistry);
        this.sessionsTotal = Counter.builder("nexusmind_sse_sessions_total")
                .description("SSE 会话累计开启数")
                .register(meterRegistry);
        this.errorsTotal = Counter.builder("nexusmind_sse_errors_total")
                .description("发生异常的 SSE 会话数")
                .register(meterRegistry);
    }

    /** SSE 会话开启：活跃 +1、累计 +1。 */
    public void onSessionOpen() {
        registry.onConnect();
        sessionsTotal.increment();
    }

    /** SSE 会话正常关闭：活跃 -1。 */
    public void onSessionClose() {
        registry.onDisconnect();
    }

    /** SSE 会话异常终止：错误计数 +1（活跃数由 onSessionClose 在 doFinally 中回收）。 */
    public void onSessionError() {
        errorsTotal.increment();
    }
}
