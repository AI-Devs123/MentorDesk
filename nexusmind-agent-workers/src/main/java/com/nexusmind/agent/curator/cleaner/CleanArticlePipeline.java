package com.nexusmind.agent.curator.cleaner;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 切片 2 正文提纯流水线：原始 HTML → 纯净 Markdown + 文档元数据。
 * <p>
 * 三段式：
 * <pre>
 * [原始 HTML]
 *    ↓ JsoupPreCleaner      剥脚本/样式/广告/导航，抽 title/author/canonical（必须先抽后删）
 *    ↓ ReadabilityExtractor  DOM 密度评分 + 链接密度抑制 + 兄弟节点合并 → 正文根
 *    ↓ HtmlToMarkdownConverter  语义化转译，Base64 占位、tracking 参数剔除
 * [CleanMarkdownDocument]
 * </pre>
 * 另外做一层<b>质量门禁</b>：正文过短通常意味着抓到了反爬页 / 登录墙 / SPA 空壳，
 * 直接抛异常让上层（切片 1 的 HybridWebFetcher）决定是否降级到 Playwright，而不是静默产出空文档入库。
 */
@Service
public class CleanArticlePipeline {

    /** 正文低于该字符数视为提纯失败（需上层降级渲染）。 */
    private static final int MIN_CONTENT_CHARS = 100;

    private final JsoupPreCleaner preCleaner;
    private final ReadabilityExtractor readabilityExtractor;
    private final HtmlToMarkdownConverter markdownConverter;

    public CleanArticlePipeline(JsoupPreCleaner preCleaner,
                               ReadabilityExtractor readabilityExtractor,
                               HtmlToMarkdownConverter markdownConverter) {
        this.preCleaner = preCleaner;
        this.readabilityExtractor = readabilityExtractor;
        this.markdownConverter = markdownConverter;
    }

    /**
     * 执行提纯。
     *
     * @param rawHtml 原始 HTML（来自切片 1 的双通道抓取器）
     * @param url     原始页面地址，用作相对链接补全的 baseUri
     * @return 纯净正文 + 元数据
     * @throws ContentExtractionException 正文过短，疑似反爬 / 登录墙 / SPA 空壳
     */
    public CleanMarkdownDocument execute(String rawHtml, String url) {
        JsoupPreCleaner.PreCleaned preCleaned = preCleaner.clean(rawHtml, url);
        Document doc = preCleaned.document();
        DocumentMeta meta = preCleaned.meta();

        // 正文碎片 = 最优候选节点 + 合格的平铺兄弟（防「正文被拆成多个 div」漏后半篇）
        List<Element> fragments = readabilityExtractor.extractArticleFragments(doc);
        String markdown = markdownConverter.convert(fragments, url);

        if (markdown.length() < MIN_CONTENT_CHARS) {
            throw new ContentExtractionException(
                    "正文提纯失败：有效内容仅 " + markdown.length() + " 字符（阈值 " + MIN_CONTENT_CHARS
                            + "），疑似反爬页 / 登录墙 / SPA 空壳 -> " + url);
        }

        // 标题兜底：og:title 与 <title> 都缺失时，退化为正文首个一级标题
        String title = meta.title();
        if (title.isBlank()) {
            Element h1 = doc.selectFirst("h1");
            title = h1 != null ? h1.text().trim() : "";
        }
        if (!title.isBlank() && !title.equals(meta.title())) {
            meta = new DocumentMeta(title, meta.siteName(), meta.author(),
                    meta.publishedAt(), meta.description(), meta.canonical());
        }

        return new CleanMarkdownDocument(title, markdown, url, meta, estimateChunkReadyLength(markdown));
    }

    /** 粗略估算可读长度（去 Markdown 标记），供切片 3 分块前做体量判断。 */
    private int estimateChunkReadyLength(String markdown) {
        return markdown.replaceAll("[#*`>\\-|\\[\\]()!]", "").replaceAll("\\s+", " ").trim().length();
    }

    /**
     * 提纯产物。
     *
     * @param title      文档标题
     * @param markdown   纯净 Markdown 正文
     * @param originUrl  原始地址
     * @param meta       文档元数据（author / publishedAt / canonical 等）
     * @param plainLength 去标记后的有效字符数
     */
    public record CleanMarkdownDocument(
            String title,
            String markdown,
            String originUrl,
            DocumentMeta meta,
            int plainLength
    ) {
    }

    /** 提纯失败（内容过短），继承 RuntimeException 便于在流式管道中向上传播。 */
    public static class ContentExtractionException extends RuntimeException {
        public ContentExtractionException(String message) {
            super(message);
        }
    }
}
