package com.nexusmind.gateway.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 活跃 SSE 会话状态持有（与 Micrometer 解耦，便于单测）。
 *
 * <p>由 {@link SseMetricsWebFilter} 在检测到 {@code text/event-stream} 请求时调用
 * {@link #onConnect()} / {@link #onDisconnect()}，框住整个 SSE 会话生命周期。
 */
@Component
public class SseConnectionRegistry {

    private final AtomicLong active = new AtomicLong(0);

    /** 会话开启：活跃数 +1。返回当前活跃数。 */
    public long onConnect() {
        return active.incrementAndGet();
    }

    /** 会话关闭（complete / error / cancel）：活跃数 -1。返回当前活跃数。 */
    public long onDisconnect() {
        return active.decrementAndGet();
    }

    /** 当前活跃 SSE 会话数（供 Micrometer Gauge 读取）。 */
    public long activeCount() {
        return active.get();
    }
}
