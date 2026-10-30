package com.nexusmind.rag.metrics;

import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.ScoredCandidate;
import com.nexusmind.rag.embedding.EmbeddingClient;
import com.nexusmind.rag.engine.LuceneDualEngineManager;
import com.nexusmind.rag.fusion.ReciprocalRankFusionService;
import com.nexusmind.rag.rerank.BgeRerankerService;
import com.nexusmind.rag.sanitizer.SearchResponseSanitizer;
import com.nexusmind.rag.security.AgentCollectionRbacGuard;
import com.nexusmind.rag.service.HybridSearchServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 切片 1：混合检索指标采集验证（纯单元，不触 Nacos / Dubbo / Redis）。
 */
class RagMetricsTest {

    private HybridSearchServiceImpl buildService(MeterRegistry reg) throws IOException {
        LuceneDualEngineManager engine = mock(LuceneDualEngineManager.class);
        when(engine.searchSparseByText(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(engine.searchDense(anyString(), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of());

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embed(anyString())).thenReturn(new float[8]);

        ReciprocalRankFusionService rrf = mock(ReciprocalRankFusionService.class);
        when(rrf.fuse(anyString(), anyList(), anyList(), anyInt())).thenReturn(List.of());

        AgentCollectionRbacGuard rbac = mock(AgentCollectionRbacGuard.class);

        SearchResponseSanitizer sanitizer = mock(SearchResponseSanitizer.class);
        when(sanitizer.sanitize(anyList())).thenAnswer(i -> i.getArgument(0));

        BgeRerankerService reranker = mock(BgeRerankerService.class);
        when(reranker.isAvailable()).thenReturn(false); // 走快路径，rerank=false

        return new HybridSearchServiceImpl(engine, embedding, rrf, rbac, sanitizer, reranker, new RagMetrics(reg));
    }

    @Test
    void recordsMetricsOnSuccessfulSearch() throws IOException {
        MeterRegistry reg = new SimpleMeterRegistry();
        HybridSearchServiceImpl svc = buildService(reg);

        svc.hybridSearch(new HybridSearchRequest("query", "collection", 20, 5, false));

        assertEquals(1.0,
                reg.counter("nexusmind_rag_hybrid_search_total", "status", "ok", "rerank", "false").count(),
                0.0, "成功检索应计 1 次 ok 计数");
        assertNotNull(reg.find("nexusmind_rag_hybrid_search_seconds").tag("rerank", "false").timer(),
                "应记录检索耗时 Timer");
        assertEquals(1.0, reg.find("nexusmind_rag_candidates").summary().count(), 0.0,
                "应记录候选数 DistributionSummary");
    }

    @Test
    void recordsErrorMetricOnFailure() throws IOException {
        MeterRegistry reg = new SimpleMeterRegistry();
        LuceneDualEngineManager engine = mock(LuceneDualEngineManager.class);
        // 令双路召回抛错，触发 hybridSearch 的 catch 分支
        when(engine.searchSparseByText(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("engine down"));

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        ReciprocalRankFusionService rrf = mock(ReciprocalRankFusionService.class);
        AgentCollectionRbacGuard rbac = mock(AgentCollectionRbacGuard.class);
        SearchResponseSanitizer sanitizer = mock(SearchResponseSanitizer.class);
        BgeRerankerService reranker = mock(BgeRerankerService.class);

        HybridSearchServiceImpl svc = new HybridSearchServiceImpl(
                engine, embedding, rrf, rbac, sanitizer, reranker, new RagMetrics(reg));

        try {
            svc.hybridSearch(new HybridSearchRequest("q", "c", 20, 5, false));
        } catch (RuntimeException ignored) {
            // 期望异常，重点验证 error 计数
        }

        assertEquals(1.0,
                reg.counter("nexusmind_rag_hybrid_search_total", "status", "error").count(),
                0.0, "失败检索应计 1 次 error 计数");
    }
}
