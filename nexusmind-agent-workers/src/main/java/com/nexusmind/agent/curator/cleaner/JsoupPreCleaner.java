package com.nexusmind.agent.curator.cleaner;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTML 预清洗器：在正文密度分析之前，先物理剔除「非展示型噪音节点」，避免其内容污染字符统计与密度评分。
 * <p>
 * 关键设计：
 * <ol>
 *   <li><b>先抽元数据、再清洗</b>——{@code meta} / {@code link} 马上要被删，必须提前把 title / author / canonical 抢出来，
 *       否则切片 3 会白丢一批高置信度信息；</li>
 *   <li><b>先收集、后删除</b>——遍历过程中修改 DOM 会导致漏删或并发修改异常，统一收集到 List 再 remove；</li>
 *   <li><b>属性级净化</b>——移除 {@code on*} 内联事件句柄，并直接删除 {@code href/src} 为
 *       {@code javascript:} / {@code vbscript:} / 危险 {@code data:} 的节点，防止产出
 *       {@code [点我](javascript:...)} 这类毒链接进入知识库。</li>
 * </ol>
 */
@Component
public class JsoupPreCleaner {

    /** 直接物理删除的无意义标签（不影响语义正文）。 */
    private static final String[] UNWANTED_TAGS = {
            "script", "style", "noscript", "iframe", "svg", "canvas", "meta", "link",
            "object", "embed", "template", "form", "button", "input", "select", "textarea"
    };

    /**
     * 常见广告、弹窗、导航与推荐流的容器选择器。
     * <p>
     * 注意：这里只放「几乎必然是噪音」的强特征，弱特征（如含 header/foot 子串的 class）
     * 交给 {@link ReadabilityExtractor} 的密度评分去判，避免误删正文内的 footnote 等节点。
     */
    private static final String NOISE_SELECTORS = String.join(", ",
            ".advertisement", ".advert", ".ad-box", ".adsbygoogle", ".ad-slot",
            ".banner", ".modal", ".popup", ".cookie-banner", ".cookie-consent", ".gdpr-banner",
            "#comments", ".comment-section", ".comments-area", "#disqus_thread",
            ".footer-container", ".site-footer", ".related-posts", ".recommend-list",
            ".share-bar", ".social-share", ".breadcrumb", ".pagination",
            "nav", "header", "footer", "aside"
    );

    /**
     * 预清洗入口。
     *
     * @param rawHtml 原始 HTML
     * @param baseUri 基准地址（用于相对链接补全，可为空串）
     * @return 清洗结果：净化后的 Document + 抢在删除前抽取的元数据
     */
    public PreCleaned clean(String rawHtml, String baseUri) {
        if (rawHtml == null || rawHtml.isBlank()) {
            Document empty = Document.createShell(baseUri == null ? "" : baseUri);
            return new PreCleaned(empty, DocumentMeta.empty());
        }
        String base = baseUri == null ? "" : baseUri;
        Document doc = base.isBlank() ? org.jsoup.Jsoup.parse(rawHtml) : org.jsoup.Jsoup.parse(rawHtml, base);

        // 1. 抢在删除前提取元数据
        DocumentMeta meta = extractMeta(doc);

        // 2. 统一收集待删节点（先收集、后删除，避免遍历中修改 DOM）
        List<Element> toRemove = new ArrayList<>();
        for (String tag : UNWANTED_TAGS) {
            toRemove.addAll(doc.select(tag));
        }
        toRemove.addAll(doc.select(NOISE_SELECTORS));

        // 3. 属性级净化：剥离 on* 事件句柄，并摘除危险协议地址（只删属性，保留锚文本/正文）
        for (Element el : doc.getAllElements()) {
            stripEventHandlerAttributes(el);
            stripDangerousProtocolAttributes(el);
        }
        for (Element el : toRemove) {
            el.remove();
        }

        return new PreCleaned(doc, meta);
    }

    /**
     * 抽取文档级元数据。必须在删除 meta/link 之前调用。
     */
    private DocumentMeta extractMeta(Document doc) {
        String ogTitle = attrOf(doc, "meta[property=og:title]", "content");
        String rawTitle = doc.title() != null ? doc.title().trim() : "";
        String siteName = attrOf(doc, "meta[property=og:site_name]", "content");

        String title = !ogTitle.isBlank() ? ogTitle.trim() : rawTitle;
        // <title> 常形如 "文章标题 - 站点名" / "文章标题 | 站点名"，剥掉后缀让标题更干净
        if (!ogTitle.isBlank() && !siteName.isBlank()) {
            title = stripSiteSuffix(title, siteName);
        } else if (ogTitle.isBlank() && !rawTitle.isEmpty()) {
            title = stripTitleSuffix(rawTitle);
        }

        String author = firstNonBlank(
                attrOf(doc, "meta[name=author]", "content"),
                attrOf(doc, "meta[property=article:author]", "content"),
                attrOf(doc, "meta[name=twitter:creator]", "content"));

        String publishedAt = firstNonBlank(
                attrOf(doc, "meta[property=article:published_time]", "content"),
                attrOf(doc, "meta[name=publishdate]", "content"),
                attrOf(doc, "meta[name=pubdate]", "content"),
                attrOf(doc, "time[datetime]", "datetime"));

        String description = firstNonBlank(
                attrOf(doc, "meta[property=og:description]", "content"),
                attrOf(doc, "meta[name=description]", "content"));

        String canonical = attrOf(doc, "link[rel=canonical]", "href");
        if (canonical.isBlank()) {
            canonical = attrOf(doc, "meta[property=og:url]", "content");
        }

        return new DocumentMeta(title, siteName, author, publishedAt, description, canonical);
    }

    private String attrOf(Document doc, String cssQuery, String attrKey) {
        Element el = doc.selectFirst(cssQuery);
        if (el == null) {
            return "";
        }
        String v = el.attr(attrKey);
        return v == null ? "" : v.trim();
    }

    private String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }

    /** 剥离 "标题 - 站点名" / "标题 | 站点名" / "标题_站点名" 这类后缀。 */
    private String stripSiteSuffix(String title, String siteName) {
        String[] seps = {" - ", " | ", " _ ", " — ", " – "};
        for (String sep : seps) {
            String tail = sep + siteName;
            if (title.endsWith(tail)) {
                return title.substring(0, title.length() - tail.length()).trim();
            }
        }
        return title;
    }

    /** 无 siteName 时的保守剥离：仅处理最后一段且长度合理的后缀。 */
    private String stripTitleSuffix(String title) {
        String[] seps = {" - ", " | ", " _ ", " — "};
        for (String sep : seps) {
            int idx = title.lastIndexOf(sep);
            if (idx > 0 && title.length() - idx - sep.length() <= 30) {
                String head = title.substring(0, idx).trim();
                if (!head.isEmpty()) {
                    return head;
                }
            }
        }
        return title;
    }

    /** 剥离 on* 内联事件句柄（onclick/onload/onerror...），降低后续转换产出的注入面。 */
    private void stripEventHandlerAttributes(Element el) {
        List<String> removeKeys = new ArrayList<>();
        for (Attribute attr : el.attributes()) {
            String key = attr.getKey().toLowerCase(Locale.ROOT);
            if (key.startsWith("on")) {
                removeKeys.add(attr.getKey());
            }
        }
        removeKeys.forEach(el::removeAttr);
    }

    /**
     * 摘除危险协议地址属性（{@code javascript:} / {@code vbscript:} / 非图片 {@code data:}）。
     * <p>
     * 这里<b>只删属性、不删节点</b>——整节点摘除会连带丢掉锚文本，造成正文缺句。
     * 属性删掉后，转换器会把该链接降级为纯文本（保留可读内容，去掉危险的跳转）。
     */
    private void stripDangerousProtocolAttributes(Element el) {
        for (String attrKey : new String[]{"href", "src", "action", "formaction", "xlink:href"}) {
            if (el.hasAttr(attrKey) && isDangerous(el.attr(attrKey))) {
                el.removeAttr(attrKey);
            }
        }
    }

    private boolean isDangerous(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        // 去掉控制字符与空白后再判，防 " java\tscript:" 这类混淆
        String v = url.replaceAll("[\\s\\u0000-\\u001f]", "").toLowerCase(Locale.ROOT);
        if (v.startsWith("javascript:") || v.startsWith("vbscript:")) {
            return true;
        }
        // data: 仅允许图片（img 的 src 会在转换阶段替换为占位符）；data:text/html 等一律摘除
        return v.startsWith("data:") && !v.startsWith("data:image/");
    }

    /**
     * 预清洗结果。
     *
     * @param document 净化后的 DOM
     * @param meta     删除 meta/link 前抢先抽取的文档元数据
     */
    public record PreCleaned(Document document, DocumentMeta meta) {
    }
}
