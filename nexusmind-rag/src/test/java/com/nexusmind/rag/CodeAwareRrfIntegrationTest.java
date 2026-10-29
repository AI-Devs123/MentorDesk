package com.nexusmind.rag;

import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.api.rag.dto.RagIndexDocument;
import com.nexusmind.api.rag.dto.RetrievalCoverage;
import com.nexusmind.api.rag.dto.ScoredCandidate;
import com.nexusmind.rag.embedding.EmbeddingClient;
import com.nexusmind.rag.engine.LuceneDualEngineManager;
import com.nexusmind.rag.fusion.ReciprocalRankFusionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 切片 2 验收：代码感知分词 + RRF 融合的闭环。
 *
 * <p>对比原始草稿的四处偏差已修复：
 * <ol>
 *   <li>分析器按集合分发（code 用 CodeSymbolAnalyzer，非 code 用 StandardAnalyzer）；</li>
 *   <li>稀疏检索走 SearcherManager NRT，不再 DirectoryReader.open 绕过刷新；</li>
 *   <li>稀疏 query 直接用分析器切词拼 BooleanQuery，避免 QueryParser.escape 把 {@code ->} 拆单字符；</li>
 *   <li>使用真实 RagApplication 启动上下文，并 @AfterEach 递归清理索引目录。</li>
 * </ol>
 *
 * <p>注：embedding 走 mock（确定性哈希向量），本测试只验证 RRF 机制 + BM25 词法召回，
 * 真实语义相似度需接入真实向量端点后另测（已在 EmbeddingClient 防静默降级中约束）。
 */
@SpringBootTest(classes = RagApplication.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CodeAwareRrfIntegrationTest {

    @Autowired
    private LuceneDualEngineManager engineManager;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ReciprocalRankFusionService rrfService;

    @AfterEach
    void cleanup() throws Exception {
        Path dir = Paths.get("target/nexusmind-rag-test-indices");
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                                // Windows 下若 writer 仍持有句柄会跳过，不影响断言正确性
                            }
                        });
            }
        }
    }

    @Test
    @DisplayName("验收1：代码集合的符号算子(->)与子词(getUser)均可被稀疏召回，且双路融合为 BOTH_HIT")
    void codeSymbolAndSubwordRecallThenFusion() throws Exception {
        String collection = "code";
        String content = "public class Foo { Runnable r = () -> System.out::println; String name = getUser(); int x = a + b; }";

        float[] vector = embeddingClient.embed(content);
        engineManager.indexDocument(new RagIndexDocument(
                "CODE-1", collection, content, vector, Map.of("lang", "java")));

        // 1. 算子 token 召回：QueryParser.escape 时代 -> 被拆成 - 与 > 会失败，此处必须命中
        List<ScoredCandidate> arrowHits = engineManager.searchSparseByText(collection, "->", 10);
        Assertions.assertFalse(arrowHits.isEmpty(), "代码感知分词必须保留 -> 算子 token，稀疏召回应命中");
        Assertions.assertEquals("CODE-1", arrowHits.get(0).docId());

        // 2. 子词召回：getUser 应拆出 get / user，命中包含 getUser() 的文档
        List<ScoredCandidate> subwordHits = engineManager.searchSparseByText(collection, "getUser", 10);
        Assertions.assertFalse(subwordHits.isEmpty(), "子词拆分(get/user)应命中 getUser() 文档");
        Assertions.assertEquals("CODE-1", subwordHits.get(0).docId());

        // 3. 稠密通道（mock 向量，单文档必召回）
        List<ScoredCandidate> denseHits = engineManager.searchDense(collection,
                embeddingClient.embed("lambda arrow expression and name resolver"), 10);
        Assertions.assertFalse(denseHits.isEmpty());

        // 4. RRF 融合：双路均命中 -> BOTH_HIT
        List<FusedCandidate> fused = rrfService.fuse(collection, denseHits, arrowHits, 10);
        RetrievalCoverage coverage = rrfService.coverage(fused);
        Assertions.assertTrue(coverage.bothHit() >= 1, "CODE-1 应同时出现在稠密与稀疏通道，构成 BOTH_HIT");
        FusedCandidate top = fused.stream().filter(FusedCandidate::bothHit).findFirst().orElseThrow();
        Assertions.assertEquals("CODE-1", top.docId());
        Assertions.assertTrue(top.denseRank() > 0 && top.sparseRank() > 0);
    }

    @Test
    @DisplayName("验收2：非代码集合仍走 StandardAnalyzer，中文词法召回正常（分析器分发未误伤）")
    void nonCodeCollectionUsesStandardAnalyzer() throws Exception {
        String collection = "disp";
        String content = "今天完成了微服务通信基座的搭建，并通过 Triple 协议打通了双向流式通道。";

        float[] vector = embeddingClient.embed(content);
        engineManager.indexDocument(new RagIndexDocument(
                "DISP-1", collection, content, vector, Map.of("date", "2026-10-28")));

        // 中文 BM25 召回（StandardAnalyzer 路径，不抛异常、可命中）
        List<ScoredCandidate> sparse = engineManager.searchSparseByText(collection, "通信", 10);
        Assertions.assertFalse(sparse.isEmpty(), "非代码集合应走 StandardAnalyzer，中文词法召回正常");
        Assertions.assertEquals("DISP-1", sparse.get(0).docId());
    }
}
