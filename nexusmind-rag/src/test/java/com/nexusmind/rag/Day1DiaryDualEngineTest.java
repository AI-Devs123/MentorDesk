package com.nexusmind.rag;

import com.nexusmind.api.rag.dto.RagIndexDocument;
import com.nexusmind.api.rag.dto.ScoredCandidate;
import com.nexusmind.rag.embedding.EmbeddingClient;
import com.nexusmind.rag.engine.LuceneDualEngineManager;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
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
 * 验收闭环：一条 Day1 日记能同时在单一 Lucene 引擎被 BM25 词法与 HNSW 向量召回。
 *
 * <p>使用 {@code test} profile 加载 application-test.yml（索引落 target 临时目录 + embedding.mock=true），
 * {@link AfterEach} 递归清理索引目录，避免多次运行互相污染。
 */
@SpringBootTest(classes = RagApplication.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Day1DiaryDualEngineTest {

    @Autowired
    private LuceneDualEngineManager engineManager;

    @Autowired
    private EmbeddingClient embeddingClient;

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
                            }
                        });
            }
        }
    }

    @Test
    @DisplayName("验收：一条 Day1 日记能同时在单一 Lucene 引擎被 BM25 和 HNSW 向量召回")
    void testDiaryDualRetrieval() throws Exception {
        String collection = "journal";
        String content = "今天完成了微服务通信基座的搭建，通过 Triple 协议打通了双向流式通道，但取消信号的传播仍需完善。";

        // 1. 向量化与双通道落盘
        float[] vector = embeddingClient.embed(content);
        RagIndexDocument doc = new RagIndexDocument(
                "JOURNAL-2026-10-27-01",
                collection,
                content,
                vector,
                Map.of("date", "2026-10-27", "agent", "JournalAgent"));
        engineManager.indexDocument(doc);

        // 2. 验证 BM25 词法检索（精确匹配 "Triple"）
        TermQuery bm25Query = new TermQuery(new Term("content", "triple"));
        List<ScoredCandidate> sparseHits = engineManager.searchSparse(collection, bm25Query, 5);
        Assertions.assertFalse(sparseHits.isEmpty(), "BM25 词法通道必须命中");
        Assertions.assertEquals("JOURNAL-2026-10-27-01", sparseHits.get(0).docId());

        // 3. 验证 HNSW 密集语义召回（用语义相似但用词不同的 query）
        String queryText = "微服务的 RPC 流式网络搭建进展";
        float[] queryVec = embeddingClient.embed(queryText);
        List<ScoredCandidate> denseHits = engineManager.searchDense(collection, queryVec, 5);
        Assertions.assertFalse(denseHits.isEmpty(), "HNSW 向量通道必须命中");
        Assertions.assertEquals("JOURNAL-2026-10-27-01", denseHits.get(0).docId());

        // 4. 验证元数据完整保留
        Assertions.assertEquals("JournalAgent", denseHits.get(0).metadata().get("agent"));
    }
}
