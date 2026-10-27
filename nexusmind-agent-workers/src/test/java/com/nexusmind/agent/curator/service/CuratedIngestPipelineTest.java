package com.nexusmind.agent.curator.service;

import com.nexusmind.agent.curator.chunker.AdaptiveSemanticChunker;
import com.nexusmind.agent.curator.chunker.TokenEstimator;
import com.nexusmind.agent.curator.cleaner.CleanArticlePipeline;
import com.nexusmind.agent.curator.cleaner.DocumentMeta;
import com.nexusmind.agent.curator.cleaner.HtmlToMarkdownConverter;
import com.nexusmind.agent.curator.cleaner.JsoupPreCleaner;
import com.nexusmind.agent.curator.cleaner.ReadabilityExtractor;
import com.nexusmind.agent.curator.cleaner.UrlSanitizer;
import com.nexusmind.agent.curator.extractor.DocMetadataExtractor;
import com.nexusmind.agent.curator.model.CuratedDocumentChunk;
import com.nexusmind.agent.curator.model.DocMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 2 + 切片 3 端到端测试：HTML → 纯净 Markdown → 元数据 → 语义片段。
 * <p>
 * 使用真实 {@link LLMClient}（测试环境未配 API Key，会走 chatOrFallback 的 Mock 分支），
 * 验证「Mock 与真实 LLM 走同一条解析路径」这一设计是否成立。
 */
class CuratedIngestPipelineTest {

    private final CleanArticlePipeline cleanPipeline = new CleanArticlePipeline(
            new JsoupPreCleaner(),
            new ReadabilityExtractor(),
            new HtmlToMarkdownConverter(new UrlSanitizer()));

    private final CuratedIngestPipeline ingestPipeline = new CuratedIngestPipeline(
            new DocMetadataExtractor(new LLMClient(), new ObjectMapper()),
            new AdaptiveSemanticChunker(new TokenEstimator()));

    private static final String ARTICLE_URL = "https://example.com/dubbo3";

    private static final String ARTICLE_HTML = """
            <html>
            <head>
              <title>Dubbo 3 Triple 协议深度剖析 - 某技术站</title>
              <meta name="author" content="张工">
              <link rel="canonical" href="https://example.com/dubbo3">
            </head>
            <body>
              <nav>首页 | 归档 | 关于</nav>
              <div class="article-content">
                <h1>Dubbo 3 Triple 协议深度剖析</h1>
                <p>Triple 协议是 Dubbo 3 主推的主力通信协议，它完全对齐 gRPC 标准并原生运行在 HTTP/2 之上，支持双向流式调用。</p>
                <p>它支持双向流式推流，同时兼容移动端穿透与网关解耦，在长连接场景下表现稳定，是微服务通信的重要演进方向。</p>
                <pre><code class="language-java">public class TripleServerStream {
    public void onData(byte[] bytes) {
        processStreamFrame(bytes);
    }
}</code></pre>
                <p>更多细节请参考官方文档，也可以结合实际项目中的网关配置来理解协议协商过程与超时控制策略。</p>
              </div>
              <div class="advertisement"><p>云服务器五折促销，立即抢购领取新人礼包！</p></div>
            </body>
            </html>
            """;

    @Test
    @DisplayName("端到端：HTML → 清洗 → 元数据 → 分块，链路贯通")
    void shouldRunEndToEnd() {
        // 切片 2
        CleanArticlePipeline.CleanMarkdownDocument cleaned = cleanPipeline.execute(ARTICLE_HTML, ARTICLE_URL);
        assertTrue(cleaned.markdown().contains("Triple 协议是 Dubbo 3 主推"), "清洗应保留正文");
        assertFalse(cleaned.markdown().contains("云服务器五折促销"), "清洗应剔除广告");

        // 切片 3
        CuratedIngestPipeline.IngestPayload payload = ingestPipeline.process(cleaned);

        assertNotNull(payload.docId(), "docId 不应为 null");
        assertTrue(payload.docId().startsWith("doc_"), "docId 应有 doc_ 前缀, 实际: " + payload.docId());
        assertEquals(ARTICLE_URL, payload.originUrl());
        assertFalse(payload.chunks().isEmpty(), "应产出片段");

        DocMetadata metadata = payload.metadata();
        assertNotNull(metadata.title());
        assertFalse(metadata.title().isBlank(), "元数据标题不应为空（Mock 降级也应给出标题）");
        assertFalse(metadata.tags().isEmpty(), "标签不应为空");

        for (CuratedDocumentChunk c : payload.chunks()) {
            assertEquals(payload.docId(), c.docId(), "片段 docId 应与载荷一致");
            assertFalse(c.textContent().isBlank(), "片段内容不应为空");
            assertEquals(metadata.title(), c.metadata().get("title"), "片段应挂载同一份元数据标题");
        }
    }

    @Test
    @DisplayName("同一 URL 重复处理应得到相同 docId（入库幂等）")
    void shouldBeIdempotentForSameUrl() {
        CleanArticlePipeline.CleanMarkdownDocument cleaned1 = cleanPipeline.execute(ARTICLE_HTML, ARTICLE_URL);
        CleanArticlePipeline.CleanMarkdownDocument cleaned2 = cleanPipeline.execute(ARTICLE_HTML, ARTICLE_URL);

        String id1 = ingestPipeline.process(cleaned1).docId();
        String id2 = ingestPipeline.process(cleaned2).docId();

        assertEquals(id1, id2, "同一 URL 的 docId 必须稳定，否则知识库会积累重复副本");
    }

    @Test
    @DisplayName("正文为空时应抛 IllegalArgumentException 而非静默产出空载荷")
    void shouldRejectEmptyDocument() {
        CleanArticlePipeline.CleanMarkdownDocument empty = new CleanArticlePipeline.CleanMarkdownDocument(
                "", "", ARTICLE_URL, DocumentMeta.empty(), 0);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ingestPipeline.process(empty));
    }
}
