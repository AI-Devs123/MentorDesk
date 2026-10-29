package com.nexusmind.rag.service;

import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;
import com.nexusmind.api.rag.dto.ScoredCandidate;
import com.nexusmind.api.rag.service.IHybridSearchService;
import com.nexusmind.rag.embedding.EmbeddingClient;
import com.nexusmind.rag.engine.LuceneDualEngineManager;
import com.nexusmind.rag.fusion.ReciprocalRankFusionService;
import com.nexusmind.rag.rerank.BgeRerankerService;
import com.nexusmind.rag.sanitizer.SearchResponseSanitizer;
import com.nexusmind.rag.security.AgentCollectionRbacGuard;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 混合检索中枢实现（切片 3 Provider）。
 *
 * <p>导出为 Dubbo Triple 服务，供 nexusmind-gateway 引用。执行链路：
 * RBAC 鉴权 → 稀疏 BM25 + 稠密 HNSW 双路召回 → RRF 融合 → 响应 PII 脱敏。
 *
 * <p><b>关于注解</b>：仅用 {@link DubboService} 导出 RPC；额外加 {@link Service}+{@link Primary}
 * 是让 {@code @Autowired IHybridSearchService} 在测试与消费侧无歧义（避免与 Dubbo 生成的 ServiceBean 冲突），
 * 并非冗余注册。
 *
 * <p><b>脱敏修正</b>：未挂 {@code @Sanitize(sanitizeReturnValue = true)}——
 * 该注解在现有切面中仅脱敏 String 入参、对返回值无效（空操作）。改由 {@link SearchResponseSanitizer}
 * 显式对候选 content / 敏感 metadata 打码，真正阻断 PII 经 Triple 逃逸。
 */
@DubboService(protocol = "tri", version = "1.0.0")
@Service
@Primary
public class HybridSearchServiceImpl implements IHybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchServiceImpl.class);
    private static final int DEFAULT_TOP_K = 20;
    private static final int DEFAULT_FUSED_TOP_N = 5;

    private final LuceneDualEngineManager engineManager;
    private final EmbeddingClient embeddingClient;
    private final ReciprocalRankFusionService rrfService;
    private final AgentCollectionRbacGuard rbacGuard;
    private final SearchResponseSanitizer sanitizer;
    private final BgeRerankerService rerankerService;

    public HybridSearchServiceImpl(LuceneDualEngineManager engineManager,
                                  EmbeddingClient embeddingClient,
                                  ReciprocalRankFusionService rrfService,
                                  AgentCollectionRbacGuard rbacGuard,
                                  SearchResponseSanitizer sanitizer,
                                  BgeRerankerService rerankerService) {
        this.engineManager = engineManager;
        this.embeddingClient = embeddingClient;
        this.rrfService = rrfService;
        this.rbacGuard = rbacGuard;
        this.sanitizer = sanitizer;
        this.rerankerService = rerankerService;
    }

    @Override
    public HybridSearchResponse hybridSearch(HybridSearchRequest request) {
        long start = System.currentTimeMillis();

        // 1. RBAC 集合级鉴权（越权直接抛 SecurityException → Triple 透传为 FORBIDDEN）
        rbacGuard.validateAccess(request.collection());

        try {
            int topK = request.topK() > 0 ? request.topK() : DEFAULT_TOP_K;
            int fusedTopN = request.fusedTopN() > 0 ? request.fusedTopN() : DEFAULT_FUSED_TOP_N;

            // 2. 双通道并行召回（Sparse BM25 词法 + Dense HNSW 语义）
            List<ScoredCandidate> sparseHits =
                    engineManager.searchSparseByText(request.collection(), request.query(), topK);
            float[] queryVector = embeddingClient.embed(request.query());
            List<ScoredCandidate> denseHits =
                    engineManager.searchDense(request.collection(), queryVector, topK);

            // 3. RRF 融合（修正点：4 参签名，collection 在前，供覆盖度统计与路由一致性）
            //    慢路径精排就绪时，粗排漏斗收窄到 Top-8（Cross-Encoder 仅对少量候选打分，控时延）；
            //    否则（未开启 / 模型不可用）漏斗保持 fusedTopN，纯净返回。两者绑定避免「重排降级却少返回」。
            // null 安全：enableRerank 是包装类型（避免 hessian2 反序列化 record 时
            // "Boolean cannot be assigned to boolean" 缺陷），未携带时按 false 处理
            boolean rerankReady = Boolean.TRUE.equals(request.enableRerank()) && rerankerService.isAvailable();
            int rrfCandidatesLimit = rerankReady ? 8 : fusedTopN;
            List<FusedCandidate> fused =
                    rrfService.fuse(request.collection(), denseHits, sparseHits, rrfCandidatesLimit);

            // 4. 慢路径 Cross-Encoder 精排（可选）；快路径直接采用 RRF 结果
            List<FusedCandidate> finalCandidates;
            if (rerankReady) {
                long rerankStart = System.currentTimeMillis();
                finalCandidates = rerankerService.rerank(request.query(), fused, fusedTopN);
                log.info("[HybridSearch] Cross-Encoder 精排完成，粗排候选数={}, 输出数={}, 耗时={}ms",
                        fused.size(), finalCandidates.size(), (System.currentTimeMillis() - rerankStart));
            } else {
                finalCandidates = fused;
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("[HybridSearch] query='{}', collection='{}', sparseHits={}, denseHits={}, fusedCount={}, rerank={}, elapsed={}ms",
                    request.query(), request.collection(), sparseHits.size(), denseHits.size(),
                    fused.size(), rerankReady, elapsed);

            // 5. 响应脱敏：无论快/慢路径，最终候选都要过 PII 脱敏，阻断经 Triple 逃逸（切片 3 P0 修复不得被绕过）
            List<FusedCandidate> sanitized = sanitizer.sanitize(finalCandidates);
            return new HybridSearchResponse(request.query(), request.collection(), elapsed, sanitized);
        } catch (Exception e) {
            log.error("[HybridSearch] 执行异常", e);
            throw new RuntimeException("混合检索执行失败: " + e.getMessage(), e);
        }
    }
}
