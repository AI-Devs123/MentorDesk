package com.nexusmind.rag.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 混合检索指标（切片 1 可观测性基座）。
 *
 * <ul>
 *   <li>{@code nexusmind_rag_hybrid_search_seconds}：单次检索端到端耗时（Timer，按是否经 Cross-Encoder 精排分标签）；</li>
 *   <li>{@code nexusmind_rag_hybrid_search_total}：检索调用计数（按 status=ok/error、rerank=true/false）；</li>
 *   <li>{@code nexusmind_rag_candidates}：单次返回融合候选条数（DistributionSummary，反映召回覆盖度）。</li>
 * </ul>
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次混合检索结果。
     *
     * @param elapsedMs      端到端耗时（毫秒）
     * @param candidateCount 最终返回候选数
     * @param rerank         是否走了 Cross-Encoder 精排（慢路径）
     * @param success        是否成功（false 时为异常路径，仅累加 error 计数）
     */
    public void recordSearch(long elapsedMs, int candidateCount, boolean rerank, boolean success) {
        if (!success) {
            registry.counter("nexusmind_rag_hybrid_search_total", "status", "error").increment();
            return;
        }
        registry.counter("nexusmind_rag_hybrid_search_total",
                        "status", "ok",
                        "rerank", String.valueOf(rerank))
                .increment();
        Timer.builder("nexusmind_rag_hybrid_search_seconds")
                .tags("rerank", String.valueOf(rerank))
                .register(registry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
        DistributionSummary.builder("nexusmind_rag_candidates")
                .description("单次混合检索返回的融合候选条数")
                .register(registry)
                .record(candidateCount);
    }
}
