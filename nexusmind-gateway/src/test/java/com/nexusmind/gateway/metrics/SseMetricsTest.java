package com.nexusmind.gateway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 切片 1：网关 SSE 会话指标验证（纯单元）。
 */
class SseMetricsTest {

    @Test
    void tracksActiveSessionsAndTotals() {
        MeterRegistry reg = new SimpleMeterRegistry();
        SseConnectionRegistry registry = new SseConnectionRegistry();
        SseMetrics metrics = new SseMetrics(reg, registry);

        // 两个会话先后开启
        metrics.onSessionOpen();
        metrics.onSessionOpen();
        // 其中一个异常
        metrics.onSessionError();
        // 两个都关闭
        metrics.onSessionClose();
        metrics.onSessionClose();

        assertEquals(0L, registry.activeCount(), "全部关闭后活跃数应归零");
        assertEquals(2.0, reg.counter("nexusmind_sse_sessions_total").count(), 0.0);
        assertEquals(1.0, reg.counter("nexusmind_sse_errors_total").count(), 0.0);
        // Gauge 应已注册（活跃数归零时仍为有效 gauge）
        assertEquals(0.0,
                reg.find("nexusmind_sse_active_sessions").gauge().value(), 0.0);
    }
}
