package com.nexusmind.agent.curator.cleaner;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于 DOM 密度评分的正文容器提取器（Mozilla Readability 核心思想的 Java 精简实现）。
 * <p>
 * 评分模型：
 * <ul>
 *   <li><b>自然语言密度</b>——段落内逗号 / 句号 / 分号越多，越像正文；</li>
 *   <li><b>阶梯冒泡</b>——段落得分按 1.0 累加到父节点、0.5 累加到祖父节点；</li>
 *   <li><b>标签先验</b>——article +15 / section +8 / div +5 / table·ul −5，class 命中正向词再 +10；</li>
 *   <li><b>链接密度抑制</b>——最终分 × (1 − linkDensity)，把导航栏、推荐列表压下去；</li>
 *   <li><b>兄弟节点合并</b>——关键补强：正文常被拆成多个平铺的兄弟 div，只取单点会漏后半篇。</li>
 * </ul>
 */
@Component
public class ReadabilityExtractor {

    /**
     * 几乎不可能是正文的容器特征。
     * <p>
     * 相比初版收紧了边界：{@code foot} / {@code header} 这类短词会误伤 {@code footnote}、{@code header-link}，
     * 因此改用 {@code -ad-}、{@code footer}、{@code header} 等更具辨识度的写法，
     * 并统一要求「命中负向词且未命中正向词」才剔除。
     */
    private static final Pattern UNLIKELY_CANDIDATES = Pattern.compile(
            "combx|comment|community|disqus|extra|footer|header|menu|remark|rss|shoutbox|sidebar"
                    + "|sponsor|-ad-|ad-break|agegate|pagination|pager|popup|share|social|related"
                    + "|recommend|breadcrumb|copyright|newsletter|subscribe",
            Pattern.CASE_INSENSITIVE);

    /** 强正向特征：命中后可「豁免」负向特征（如 article-header 仍是正文的一部分）。 */
    private static final Pattern OK_MAYBE_ITS_CANDIDATE = Pattern.compile(
            "and|article|body|column|main|shadow|post|entry|content|text|blog|story|prose",
            Pattern.CASE_INSENSITIVE);

    /** 参与打分的段落级节点。 */
    private static final String PARAGRAPH_SELECTOR = "p, pre, td, blockquote, article";

    /** 过短段落不参与计分（放大噪声）。 */
    private static final int MIN_PARAGRAPH_TEXT_LENGTH = 25;

    /** 兄弟合并阈值：兄弟分数达到最优节点的该比例才并入，避免把侧栏一起吸进来。 */
    private static final double SIBLING_MERGE_RATIO = 0.2;

    /**
     * 提取正文根容器。
     *
     * @param doc 已预清洗的文档
     * @return 正文根节点；无法判定时回退到 body
     */
    public Element extractArticleRoot(Document doc) {
        Element body = doc.body();
        if (body == null) {
            return doc;
        }

        // 1. 剔除高置信度噪音容器：先收集、后删除，避免遍历中修改 DOM
        removeUnlikelyCandidates(body);

        // 2. 段落级计分 + 阶梯冒泡
        Map<Element, Double> scores = new HashMap<>();
        Elements paragraphs = body.select(PARAGRAPH_SELECTOR);

        for (Element p : paragraphs) {
            String text = p.text().trim();
            if (text.length() < MIN_PARAGRAPH_TEXT_LENGTH) {
                continue;
            }
            Element parent = p.parent();
            if (parent == null) {
                continue;
            }
            Element grandParent = parent.parent();

            double score = 1.0;
            score += countSentencePunctuation(text);
            score += Math.min(text.length() / 100.0, 3.0);

            addScore(parent, scores, score);
            if (grandParent != null && grandParent != body) {
                addScore(grandParent, scores, score / 2.0);
            }
        }

        if (scores.isEmpty()) {
            return body;
        }

        // 3. 链接密度抑制后选优
        Element best = null;
        double maxScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Element, Double> entry : scores.entrySet()) {
            Element candidate = entry.getKey();
            double finalScore = entry.getValue() * (1.0 - calculateLinkDensity(candidate));
            if (finalScore > maxScore) {
                maxScore = finalScore;
                best = candidate;
            }
        }
        if (best == null) {
            return body;
        }

        // 4. 兄弟节点合并：正文常被切成多个平铺 div，单点提取会漏后半篇
        return mergeSiblings(best, scores, maxScore);
    }

    /**
     * 剔除负向特征容器。命中 {@link #UNLIKELY_CANDIDATES} 且未命中 {@link #OK_MAYBE_ITS_CANDIDATE}、
     * 且不是 body/article/table 的节点整体摘除。
     */
    private void removeUnlikelyCandidates(Element body) {
        List<Element> toRemove = new ArrayList<>();
        for (Element el : body.getAllElements()) {
            if (el == body) {
                continue;
            }
            String tag = el.tagName().toLowerCase();
            if ("body".equals(tag) || "article".equals(tag) || "table".equals(tag) || "pre".equals(tag)) {
                continue;
            }
            String matchString = el.className() + " " + el.id() + " " + roleOf(el);
            boolean unlikely = UNLIKELY_CANDIDATES.matcher(matchString).find();
            boolean rescue = OK_MAYBE_ITS_CANDIDATE.matcher(matchString).find();
            if (unlikely && !rescue && !hasStrongContentChild(el)) {
                toRemove.add(el);
            }
        }
        toRemove.forEach(Element::remove);
    }

    private String roleOf(Element el) {
        String role = el.attr("role");
        return role == null ? "" : role;
    }

    /**
     * 反向保护：即使 class 像噪音，只要内部含足够长的段落就直接放行，
     * 防止 "article-footer-nav" 这类命名把真正的结尾段落一起删掉。
     */
    private boolean hasStrongContentChild(Element el) {
        for (Element p : el.select("p")) {
            if (p.text().trim().length() >= MIN_PARAGRAPH_TEXT_LENGTH * 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * 兄弟节点合并：把与最优候选同父、同层、分数够高的兄弟整体并入。
     * <p>
     * 实现方式不是新建包裹节点（会打乱原有相对链接解析），而是直接返回最优节点，
     * 由 {@link CleanArticlePipeline} 在转换阶段按「最优节点 + 其合格兄弟」的序列输出。
     * 这里把合格兄弟以 DOM 属性方式登记，保持 extractor 单一返回值契约。
     */
    private Element mergeSiblings(Element best, Map<Element, Double> scores, double maxScore) {
        Element parent = best.parent();
        if (parent == null) {
            return best;
        }
        double threshold = maxScore * SIBLING_MERGE_RATIO;
        int merged = 0;
        for (Element sibling : parent.children()) {
            if (sibling == best) {
                continue;
            }
            Double siblingScore = scores.get(sibling);
            if (siblingScore != null && siblingScore >= threshold) {
                merged++;
            }
        }
        if (merged > 0) {
            // 登记合并数量，供上层/日志观测；不改变返回类型契约
            best.attr("data-nexusmind-merged-siblings", String.valueOf(merged));
        }
        return best;
    }

    /**
     * 按「最优节点 + 合格兄弟」的顺序返回正文片段序列，供转换器连续输出，避免后半篇丢失。
     * <p>
     * 这是 {@link #extractArticleRoot} 的增强版入口：多数场景直接调用本方法。
     */
    public List<Element> extractArticleFragments(Document doc) {
        Element root = extractArticleRoot(doc);
        List<Element> fragments = new ArrayList<>();
        if (root == null) {
            return fragments;
        }
        Element parent = root.parent();
        if (parent == null || "body".equalsIgnoreCase(root.tagName())) {
            fragments.add(root);
            return fragments;
        }
        // 兄弟合并：把 root 及其「同为块级、非噪音」的兄弟按文档顺序收集
        for (Element sibling : parent.children()) {
            if (isContentSibling(sibling)) {
                fragments.add(sibling);
            }
        }
        if (fragments.isEmpty()) {
            fragments.add(root);
        }
        return fragments;
    }

    /**
     * 兄弟是否算正文片段：非噪音特征、文本量足够、链接密度不过高。
     */
    private boolean isContentSibling(Element sibling) {
        String tag = sibling.tagName().toLowerCase();
        if ("script".equals(tag) || "style".equals(tag) || "nav".equals(tag)
                || "header".equals(tag) || "footer".equals(tag) || "aside".equals(tag)) {
            return false;
        }
        String matchString = sibling.className() + " " + sibling.id();
        if (UNLIKELY_CANDIDATES.matcher(matchString).find()
                && !OK_MAYBE_ITS_CANDIDATE.matcher(matchString).find()) {
            return false;
        }
        String text = sibling.text().trim();
        if (text.length() < MIN_PARAGRAPH_TEXT_LENGTH) {
            return false;
        }
        return calculateLinkDensity(sibling) < 0.5;
    }

    private void addScore(Element el, Map<Element, Double> scores, double delta) {
        scores.put(el, initialScore(el, scores) + delta);
    }

    /** 标签与 class 先验分；首次访问时写入基础分。 */
    private double initialScore(Element el, Map<Element, Double> scores) {
        Double existing = scores.get(el);
        if (existing != null) {
            return existing;
        }
        double initial = 0.0;
        switch (el.tagName().toLowerCase()) {
            case "article" -> initial += 15;
            case "div" -> initial += 5;
            case "section" -> initial += 8;
            case "td", "table", "ul", "ol" -> initial -= 5;
            default -> {
                // 其他标签不加不减
            }
        }
        String matchString = el.className() + " " + el.id();
        if (OK_MAYBE_ITS_CANDIDATE.matcher(matchString).find()) {
            initial += 10;
        }
        return initial;
    }

    /** 统计句读符号数量，衡量自然语言流畅度。 */
    private int countSentencePunctuation(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' || c == '，' || c == '.' || c == '。' || c == ';' || c == '；'
                    || c == '!' || c == '！' || c == '?' || c == '？') {
                count++;
            }
        }
        return count;
    }

    private double calculateLinkDensity(Element element) {
        int totalTextLength = element.text().trim().length();
        if (totalTextLength == 0) {
            return 0.0;
        }
        int linkTextLength = 0;
        for (Element link : element.select("a")) {
            linkTextLength += link.text().trim().length();
        }
        return Math.min(1.0, (double) linkTextLength / totalTextLength);
    }
}
