package com.nexusmind.agent.curator.cleaner;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义化 HTML → Markdown 转译器（面向 RAG 语料，而非浏览器展示）。
 * <p>
 * 修正了初版四处产出错误内容的缺陷：
 * <ol>
 *   <li><b>列表</b>——改用 {@code renderChildren} 递归，支持多层嵌套列表并按层级缩进，
 *       同时保留列表项内的加粗 / 行内代码，不再用 {@code li.text()} 一把梭；</li>
 *   <li><b>引用</b>——同理由递归渲染后再逐行加 {@code > } 前缀，保留引用内的代码块与列表；</li>
 *   <li><b>表格</b>——只取 table 的<b>直接子级</b> tr，嵌套表格不再被扁平混入外层列；</li>
 *   <li><b>代码块</b>——从 {@code <code class="language-xxx">} / {@code data-lang} 提取语言标识。</li>
 * </ol>
 * 另外：Base64 内嵌图片替换为占位符（避免 token 爆炸），危险协议链接降级为纯文本。
 */
@Component
public class HtmlToMarkdownConverter {

    /** 内嵌 Base64 图片占位符：保留语义痕迹又不膨胀上下文。 */
    static final String EMBEDDED_IMAGE_PLACEHOLDER = "[Embedded Image Ignored]";

    /** 有序 / 无序列表缩进单位。 */
    private static final String LIST_INDENT = "  ";

    private final UrlSanitizer urlSanitizer;

    public HtmlToMarkdownConverter(UrlSanitizer urlSanitizer) {
        this.urlSanitizer = urlSanitizer;
    }

    /**
     * 转换单个容器为 Markdown。
     *
     * @param element 正文根容器
     * @param baseUri 基准地址（用于相对链接补全），可为空
     * @return 紧凑的 Markdown 文本
     */
    public String convert(Element element, String baseUri) {
        if (element == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        renderChildren(element, sb, 0, baseUri);
        return normalize(sb.toString());
    }

    /** 连续转换多个片段（正文 + 合并进来的兄弟节点），用空行衔接。 */
    public String convert(List<Element> fragments, String baseUri) {
        if (fragments == null || fragments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Element fragment : fragments) {
            renderChildren(fragment, sb, 0, baseUri);
            sb.append("\n\n");
        }
        return normalize(sb.toString());
    }

    /** 压缩三连空行为两连，并去掉首尾空白。 */
    private String normalize(String markdown) {
        return markdown.replaceAll("(\\r?\\n){3,}", "\n\n").trim();
    }

    private void renderChildren(Element el, StringBuilder sb, int depth, String baseUri) {
        for (Node child : el.childNodes()) {
            renderNode(child, sb, depth, baseUri);
        }
    }

    private void renderNode(Node node, StringBuilder sb, int depth, String baseUri) {
        if (node instanceof TextNode textNode) {
            String text = textNode.text();
            if (!text.isBlank()) {
                sb.append(text.replaceAll("[\\r\\n]+", " "));
            }
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        switch (el.tagName().toLowerCase()) {
            case "h1" -> renderHeader(el, sb, "# ", baseUri);
            case "h2" -> renderHeader(el, sb, "## ", baseUri);
            case "h3" -> renderHeader(el, sb, "### ", baseUri);
            case "h4", "h5", "h6" -> renderHeader(el, sb, "#### ", baseUri);
            case "p" -> {
                sb.append("\n\n");
                renderChildren(el, sb, depth, baseUri);
                sb.append("\n");
            }
            case "strong", "b" -> wrap(el, sb, depth, baseUri, "**");
            case "em", "i" -> wrap(el, sb, depth, baseUri, "*");
            case "del", "s", "strike" -> wrap(el, sb, depth, baseUri, "~~");
            case "code" -> renderInlineOrNestedCode(el, sb, depth, baseUri);
            case "pre" -> renderCodeBlock(el, sb);
            case "blockquote" -> renderBlockquote(el, sb, depth, baseUri);
            case "ul", "ol" -> renderList(el, sb, depth, baseUri);
            case "table" -> renderTable(el, sb, baseUri);
            case "a" -> renderLink(el, sb, baseUri);
            case "img" -> renderImage(el, sb, baseUri);
            case "br" -> sb.append("\n");
            case "hr" -> sb.append("\n\n---\n\n");
            default -> renderChildren(el, sb, depth, baseUri);
        }
    }

    private void wrap(Element el, StringBuilder sb, int depth, String baseUri, String marker) {
        sb.append(marker);
        renderChildren(el, sb, depth, baseUri);
        sb.append(marker);
    }

    private void renderHeader(Element el, StringBuilder sb, String prefix, String baseUri) {
        sb.append("\n\n").append(prefix);
        renderChildren(el, sb, 0, baseUri);
        sb.append("\n\n");
    }

    /** code 在 pre 内部时由 pre 统一渲染围栏，此处只处理行内代码与脱离 pre 的 code。 */
    private void renderInlineOrNestedCode(Element el, StringBuilder sb, int depth, String baseUri) {
        Element parent = el.parent();
        if (parent != null && "pre".equalsIgnoreCase(parent.tagName())) {
            renderChildren(el, sb, depth, baseUri);
            return;
        }
        String text = el.wholeText().replace("\n", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        // 行内代码里可能含反引号，用双反引号包裹
        String fence = text.contains("`") ? "``" : "`";
        sb.append(fence).append(text).append(fence);
    }

    private void renderCodeBlock(Element pre, StringBuilder sb) {
        String language = detectLanguage(pre);
        String code = extractCodeText(pre);
        sb.append("\n\n```").append(language).append("\n");
        sb.append(code);
        if (!code.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("```\n\n");
    }

    /** 提取代码块内的原始文本（保留缩进与换行，不 collapse 空白）。 */
    private String extractCodeText(Element pre) {
        Element codeEl = pre.selectFirst("code");
        String raw = codeEl != null ? codeEl.wholeText() : pre.wholeText();
        // 去掉首尾空行，保留内部缩进
        return raw.replaceAll("^\\s*\\n", "").replaceAll("\\n\\s*$", "");
    }

    /** 从 code class="language-java" / class="lang-js" / data-lang 提取语言标识。 */
    private String detectLanguage(Element pre) {
        Element codeEl = pre.selectFirst("code");
        String[] candidates = {
                codeEl != null ? codeEl.className() : "",
                codeEl != null ? codeEl.attr("data-lang") : "",
                pre.className(),
                pre.attr("data-lang")
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            for (String token : candidate.split("\\s+")) {
                String lang = stripLanguagePrefix(token);
                if (!lang.isEmpty() && isLikelyLanguage(lang)) {
                    return lang;
                }
            }
        }
        return "";
    }

    private String stripLanguagePrefix(String token) {
        String t = token.toLowerCase();
        for (String prefix : new String[]{"language-", "lang-", "highlight-source-"}) {
            if (t.startsWith(prefix)) {
                return t.substring(prefix.length());
            }
        }
        return t;
    }

    /** 已知语言白名单，避免把纯样式 class（如 "highlight"）误当语言。 */
    private boolean isLikelyLanguage(String token) {
        return switch (token) {
            case "java", "python", "py", "javascript", "js", "typescript", "ts", "tsx", "jsx",
                 "go", "rust", "c", "cpp", "c++", "csharp", "cs", "php", "ruby", "rb",
                 "kotlin", "kt", "scala", "swift", "sql", "bash", "sh", "shell", "zsh",
                 "powershell", "yaml", "yml", "json", "xml", "html", "css", "scss",
                 "protobuf", "proto", "dockerfile", "makefile", "ini", "toml", "lua", "dart" -> true;
            default -> false;
        };
    }

    /**
     * 引用块：先递归渲染内部内容，再对每一行加 {@code > } 前缀。
     * 这样引用里的代码块、列表、加粗都能存活（初版用 el.text() 全部抹平了）。
     */
    private void renderBlockquote(Element bq, StringBuilder sb, int depth, String baseUri) {
        StringBuilder inner = new StringBuilder();
        renderChildren(bq, inner, depth, baseUri);
        String body = inner.toString().replaceAll("(\\r?\\n){3,}", "\n\n").trim();
        if (body.isEmpty()) {
            return;
        }
        sb.append("\n\n");
        for (String line : body.split("\n", -1)) {
            sb.append(line.isBlank() ? ">" : "> " + line).append("\n");
        }
        sb.append("\n");
    }

    /**
     * 列表：递归渲染，支持多层嵌套与列表项内的行内格式。
     * 每层缩进 {@link #LIST_INDENT}，有序列表按同级 li 序号递增。
     */
    private void renderList(Element list, StringBuilder sb, int depth, String baseUri) {
        boolean ordered = "ol".equalsIgnoreCase(list.tagName());
        String indent = LIST_INDENT.repeat(depth);
        sb.append("\n");
        int index = 1;
        for (Element li : list.children()) {
            if (!"li".equalsIgnoreCase(li.tagName())) {
                continue;
            }
            String marker = ordered ? (index++) + ". " : "- ";
            sb.append(indent).append(marker);
            // 渲染 li 的直接内容，子列表单独处理以正确缩进
            for (Node child : li.childNodes()) {
                if (child instanceof Element childEl && isListTag(childEl.tagName())) {
                    continue;
                }
                renderNode(child, sb, depth, baseUri);
            }
            sb.append("\n");
            // 子列表递归，层级 +1
            for (Element child : li.children()) {
                if (isListTag(child.tagName())) {
                    renderList(child, sb, depth + 1, baseUri);
                }
            }
        }
        sb.append("\n");
    }

    private boolean isListTag(String tag) {
        String t = tag.toLowerCase();
        return "ul".equals(t) || "ol".equals(t);
    }

    /**
     * 表格：只取 table 的直接子级 tr（含 thead/tbody/tfoot 一层的 tr），
     * 避免 {@code select("tr")} 深度遍历把嵌套表格的行扁平混入外层。
     */
    private void renderTable(Element table, StringBuilder sb, String baseUri) {
        List<Element> rows = directRows(table);
        if (rows.isEmpty()) {
            return;
        }
        Element headerRow = rows.get(0);
        List<Element> headerCells = cellsOf(headerRow);
        if (headerCells.isEmpty()) {
            return;
        }

        List<String> headerTexts = new ArrayList<>();
        for (Element cell : headerCells) {
            headerTexts.add(cellText(cell, baseUri));
        }
        int columnCount = headerTexts.size();

        sb.append("\n\n");
        sb.append("| ").append(String.join(" | ", headerTexts)).append(" |\n");
        sb.append("|");
        for (int i = 0; i < columnCount; i++) {
            sb.append(" --- |");
        }
        sb.append("\n");

        for (int i = 1; i < rows.size(); i++) {
            List<Element> cells = cellsOf(rows.get(i));
            if (cells.isEmpty()) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) {
                texts.add(c < cells.size() ? cellText(cells.get(c), baseUri) : "");
            }
            sb.append("| ").append(String.join(" | ", texts)).append(" |\n");
        }
        sb.append("\n");
    }

    /** 只收集直接属于本表格的行（穿透 thead/tbody/tfoot 一层，不进入嵌套 table）。 */
    private List<Element> directRows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            String tag = child.tagName().toLowerCase();
            if ("tr".equals(tag)) {
                rows.add(child);
            } else if ("thead".equals(tag) || "tbody".equals(tag) || "tfoot".equals(tag)) {
                for (Element tr : child.children()) {
                    if ("tr".equalsIgnoreCase(tr.tagName())) {
                        rows.add(tr);
                    }
                }
            }
        }
        return rows;
    }

    /** 只取该行的直接 th/td，不深入嵌套表格。 */
    private List<Element> cellsOf(Element row) {
        List<Element> cells = new ArrayList<>();
        for (Element cell : row.children()) {
            String tag = cell.tagName().toLowerCase();
            if ("th".equals(tag) || "td".equals(tag)) {
                cells.add(cell);
            }
        }
        return cells;
    }

    /** 单元格文本：转义 Markdown 表格分隔符竖线与换行。 */
    private String cellText(Element cell, String baseUri) {
        StringBuilder inner = new StringBuilder();
        renderChildren(cell, inner, 0, baseUri);
        return inner.toString()
                .replaceAll("[\\r\\n]+", " ")
                .replace("|", "\\|")
                .trim();
    }

    private void renderLink(Element a, StringBuilder sb, String baseUri) {
        String anchorText = a.text().trim();
        if (anchorText.isEmpty()) {
            return;
        }
        String rawHref = a.hasAttr("href") ? a.attr("href") : a.absUrl("href");
        String href = urlSanitizer.sanitize(rawHref, resolveBase(a, baseUri));

        // 危险协议（javascript: 等）已被 sanitize 为空 → 降级为纯文本
        if (href.isBlank()) {
            sb.append(anchorText);
            return;
        }
        sb.append("[").append(anchorText).append("](").append(href).append(")");
    }

    private void renderImage(Element img, StringBuilder sb, String baseUri) {
        String src = img.hasAttr("src") ? img.attr("src") : img.absUrl("src");
        if (src == null || src.isBlank()) {
            // 懒加载图片常见于 data-src / data-original
            src = firstNonBlank(img.attr("data-src"), img.attr("data-original"), img.attr("data-lazy-src"));
        }
        if (src.isBlank()) {
            return;
        }
        if (src.trim().toLowerCase().startsWith("data:image/")) {
            sb.append(' ').append(EMBEDDED_IMAGE_PLACEHOLDER).append(' ');
            return;
        }
        String alt = img.attr("alt");
        if (alt.isBlank()) {
            alt = "image";
        }
        String cleanSrc = urlSanitizer.sanitize(src, resolveBase(img, baseUri));
        if (cleanSrc.isBlank()) {
            return;
        }
        sb.append("![").append(alt.replace("[", "").replace("]", "")).append("](").append(cleanSrc).append(")");
    }

    /**
     * 容器自身的绝对基地址：优先取元素所在 Document 的 baseUri（jsoup 解析时已设置，
     * {@code absUrl()} 与相对链接补全都依赖它），其次回退调用方传入的 baseUri。
     */
    private String resolveBase(Element el, String baseUri) {
        org.jsoup.nodes.Document doc = el.ownerDocument();
        if (doc != null) {
            String docBase = doc.baseUri();
            if (docBase != null && !docBase.isBlank()) {
                return docBase;
            }
        }
        return baseUri;
    }

    private String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }
}
