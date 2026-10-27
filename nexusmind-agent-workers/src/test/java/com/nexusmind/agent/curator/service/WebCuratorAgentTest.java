package com.nexusmind.agent.curator.service;

import com.nexusmind.agent.curator.cleaner.CleanArticlePipeline;
import com.nexusmind.agent.curator.cleaner.DocumentMeta;
import com.nexusmind.agent.curator.fetcher.HybridWebFetcher;
import com.nexusmind.agent.curator.model.CuratedDocumentChunk;
import com.nexusmind.agent.curator.model.DocMetadata;
import com.nexusmind.api.dto.WebCurateRequest;
import com.nexusmind.api.dto.WebCurateResult;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import com.nexusmind.security.sandbox.SecureFileOperationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WebCuratorAgent 落盘编排测试。
 *
 * <p>策略：用 Mockito 桩掉 fetcher / clean / ingest 三个 pipeline（避免真实网络、Playwright、
 * LLM 调用），使用<b>真实</b>的临时沙箱 + PathSandboxValidator + SecureFileOperationService
 * 验证落盘行为：相对路径以 {@code inbox/web/} 开头、文件确实存在、frontmatter 与正文正确。
 */
@ExtendWith(MockitoExtension.class)
class WebCuratorAgentTest {

    @Mock
    private HybridWebFetcher fetcher;

    @Mock
    private CleanArticlePipeline cleanPipeline;

    @Mock
    private CuratedIngestPipeline ingestPipeline;

    @TempDir
    Path sandboxRoot;

    @Test
    void curateAndIngest_persistsMarkdownUnderInboxWeb() throws Exception {
        // 真实沙箱（临时目录）与安全文件服务
        PathSandboxValidator validator = new PathSandboxValidator(sandboxRoot.toString());
        SecureFileOperationService secureFileOps = new SecureFileOperationService(validator);

        // 真实对象构造：避免 mock 内部 record
        String originUrl = "https://example.com/article/42";
        String fullMarkdown = "# 标题\n\n这是正文第一段。\n\n## 小节\n\n第二段内容。";
        CleanArticlePipeline.CleanMarkdownDocument cleanDoc =
                new CleanArticlePipeline.CleanMarkdownDocument(
                        "标题", fullMarkdown, originUrl,
                        new DocumentMeta("标题", "", "", "", "", originUrl), fullMarkdown.length());

        DocMetadata md = new DocMetadata("标题", "这是一句话摘要",
                List.of("标签A", "标签B"), List.of("实体X"));
        CuratedDocumentChunk chunk = new CuratedDocumentChunk(
                "doc_abc-0", "doc_abc", 0, 1, "这是一句话摘要\n\n# 标题\n\n这是正文", Map.of());
        CuratedIngestPipeline.IngestPayload payload =
                new CuratedIngestPipeline.IngestPayload(
                        "doc_abc", md, fullMarkdown, originUrl, List.of(chunk));

        // 桩：抓取返回 html，提纯返回 cleanDoc，萃取分块返回 payload
        String rawHtml = "<html><head><script>var t=1;</script></head>"
                + "<body><div class=\"ad\">广告位</div><p>正文</p></body></html>";
        when(fetcher.fetch(anyString(), anyBoolean())).thenReturn(rawHtml);
        when(cleanPipeline.execute(anyString(), anyString())).thenReturn(cleanDoc);
        when(ingestPipeline.process(org.mockito.ArgumentMatchers.any(CleanArticlePipeline.CleanMarkdownDocument.class)))
                .thenReturn(payload);

        WebCuratorAgent agent = new WebCuratorAgent(fetcher, cleanPipeline, ingestPipeline, secureFileOps);

        WebCurateRequest request = new WebCurateRequest("req-1", originUrl, false);
        WebCurateResult result = agent.curateAndIngest(request);

        // 契约返回校验
        assertNotNull(result);
        assertEquals("req-1", result.requestId());
        assertEquals("doc_abc", result.docId());
        assertTrue(result.sandboxSavedPath().startsWith("inbox/web/"),
                "落盘路径必须以 inbox/web/ 开头，实际: " + result.sandboxSavedPath());
        assertEquals(1, result.chunkCount());
        assertEquals(fullMarkdown.length(), result.charCount());
        assertEquals(originUrl, result.canonicalUrl());
        assertNotNull(result.metadata());

        // 切片5 新增：原始 HTML 规模与真实落盘字节数（供 SSE 降噪比 / 资产体积展示）
        assertEquals(rawHtml.length(), result.rawHtmlCharCount());

        // 文件确实落盘（相对路径拼到沙箱根）
        Path savedFile = sandboxRoot.resolve(result.sandboxSavedPath());
        assertTrue(Files.exists(savedFile), "落盘文件应存在: " + savedFile);
        assertEquals(Files.size(savedFile), result.fileSizeBytes(),
                "fileSizeBytes 应等于磁盘上的真实字节数");
        assertEquals((rawHtml.length() - fullMarkdown.length()) * 100 / rawHtml.length(),
                result.noiseReductionPercent(), "降噪比应按 (原始-纯净)/原始 计算");

        String content = Files.readString(savedFile);
        assertTrue(content.contains("docId: doc_abc"), "frontmatter 应含 docId");
        assertTrue(content.contains("title: 标题"), "frontmatter 应含 title");
        assertTrue(content.contains("tags: [标签A, 标签B]"), "frontmatter 应含 tags 列表");
        assertTrue(content.contains("# 标题"), "正文应完整保留");
    }

    @Test
    void curateAndIngest_slugFallsBackToDocIdWhenTitleBlank() throws Exception {
        PathSandboxValidator validator = new PathSandboxValidator(sandboxRoot.toString());
        SecureFileOperationService secureFileOps = new SecureFileOperationService(validator);

        CleanArticlePipeline.CleanMarkdownDocument cleanDoc =
                new CleanArticlePipeline.CleanMarkdownDocument(
                        "", "# 无标题正文", "https://example.com/x", DocumentMeta.empty(), 10);

        DocMetadata md = new DocMetadata("", "摘要", List.of(), List.of());
        CuratedIngestPipeline.IngestPayload payload =
                new CuratedIngestPipeline.IngestPayload(
                        "doc_blank", md, "# 无标题正文", "https://example.com/x", List.of());

        when(fetcher.fetch(anyString(), anyBoolean())).thenReturn("<html></html>");
        when(cleanPipeline.execute(anyString(), anyString())).thenReturn(cleanDoc);
        when(ingestPipeline.process(org.mockito.ArgumentMatchers.any(CleanArticlePipeline.CleanMarkdownDocument.class)))
                .thenReturn(payload);

        WebCuratorAgent agent = new WebCuratorAgent(fetcher, cleanPipeline, ingestPipeline, secureFileOps);
        WebCurateResult result = agent.curateAndIngest(new WebCurateRequest("req-2", "https://example.com/x", false));

        // 标题为空时 slug 退化为 docId，路径仍合法且文件存在
        assertTrue(result.sandboxSavedPath().contains("doc_blank"),
                "无标题时应退化为 docId 作为文件名: " + result.sandboxSavedPath());
        Path savedFile = sandboxRoot.resolve(result.sandboxSavedPath());
        assertTrue(Files.exists(savedFile), "落盘文件应存在: " + savedFile);
    }
}
