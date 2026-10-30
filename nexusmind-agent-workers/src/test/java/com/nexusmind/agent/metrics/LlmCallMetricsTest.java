package com.nexusmind.agent.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 切片 1：LLM 调用指标采集验证（纯单元）。
 */
class LlmCallMetricsTest {

    @Test
    void recordsCallMetrics() {
        MeterRegistry reg = new SimpleMeterRegistry();
        LlmCallMetrics metrics = new LlmCallMetrics(reg);

        metrics.recordCall(123L, 57, true);

        assertEquals(1.0, reg.counter("nexusmind_llm_calls_total", "status", "ok").count(), 0.0);
        assertNotNull(reg.find("nexusmind_llm_call_seconds").tag("status", "ok").timer());
        assertEquals(57.0, reg.counter("nexusmind_llm_tokens_total", "kind", "total").count(), 0.0);
    }

    @Test
    void recordsFailureMetricWithoutTokens() {
        MeterRegistry reg = new SimpleMeterRegistry();
        LlmCallMetrics metrics = new LlmCallMetrics(reg);

        metrics.recordCall(0, 0, false);

        assertEquals(1.0, reg.counter("nexusmind_llm_calls_total", "status", "error").count(), 0.0);
        // 失败路径不应累计 token
        assertEquals(0.0, reg.counter("nexusmind_llm_tokens_total", "kind", "total").count(), 0.0);
    }
}
