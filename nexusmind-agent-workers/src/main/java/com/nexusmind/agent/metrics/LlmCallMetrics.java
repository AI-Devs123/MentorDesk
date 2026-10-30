package com.nexusmind.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * LLM 调用指标（切片 1 可观测性基座）。
 *
 * <ul>
 *   <li>{@code nexusmind_llm_calls_total}：LLM 调用计数（按 status=ok/error）；</li>
 *   <li>{@code nexusmind_llm_call_seconds}：单次 LLM 调用耗时（Timer，按 status）；</li>
 *   <li>{@code nexusmind_llm_tokens_total}：累计消耗的 token 数（仅成功调用，kind=total）。</li>
 * </ul>
 *
 * <p>由 {@code LLMClient} 在真实调用路径上 best-effort 上报（字段注入、required=false，
 * 单测直接 {@code new LLMClient()} 不注入则跳过，绝不拖垮主链路）。
 */
@Component
public class LlmCallMetrics {

    private final MeterRegistry registry;

    public LlmCallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCall(long latencyMs, int totalTokens, boolean success) {
        registry.counter("nexusmind_llm_calls_total", "status", success ? "ok" : "error").increment();
        Timer.builder("nexusmind_llm_call_seconds")
                .tags("status", success ? "ok" : "error")
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
        if (success && totalTokens > 0) {
            registry.counter("nexusmind_llm_tokens_total", "kind", "total").increment(totalTokens);
        }
    }
}
