package com.nexusmind.api.observability;

import java.util.Map;

/**
 * 监控未启用 / Langfuse 未部署时的 no-op 实现，保证业务零侵入、零开销。
 *
 * <p>对齐切片 1 的 {@code KnowledgeEvolutionService} 与 {@code @Autowired(required=false)} 降级范式：
 * 探针缺失或被显式关闭时，编排链路与 LLM 调用完全不感知可观测层存在。
 */
public class NoOpLfTracingClient implements LfTracingClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void trace(String traceId, String name, String inputJson, Map<String, Object> metadata) {
        // no-op
    }

    @Override
    public void spanAndClose(String traceId, String spanId, String name,
                            String inputSummary, String outputSummary,
                            long startTimeMs, long endTimeMs, String level) {
        // no-op
    }

    @Override
    public void generationAndClose(String traceId, String generationId, String name,
                                  String model, String prompt, String completion,
                                  TokenUsage usage, long startTimeMs, long endTimeMs) {
        // no-op
    }
}
