package com.nexusmind.agent.curator.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.curator.model.DocMetadata;
import com.nexusmind.agent.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 结构化元数据萃取器（复用 Week 4 的统一 {@link LLMClient}）。
 * <p>
 * 容错三层：
 * <ol>
 *   <li>LLM 返回可能带 Markdown 围栏 / 前后解说，先做 <b>JSON 抽取自愈</b>；</li>
 *   <li>JSON 字段可能缺失或类型漂移，由 {@link DocMetadata} 的紧凑构造器规范化；</li>
 *   <li>任何异常（未配 API Key / 网络失败 / 解析失败）→ <b>确定性降级</b>，绝不中断入库链路。</li>
 * </ol>
 * 注意：LLM 调用统一走 {@code chatOrFallback}，必须提供 mock 供应者，
 * 否则未配置 API Key 的场景下拿不到合规数据。
 */
@Component
public class DocMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocMetadataExtractor.class);

    /** 抽取摘要只需开头部分，避免长文浪费 token。 */
    private static final int CONTEXT_SNIPPET_CHARS = 4000;

    /** LLM 偏好低温以保证抽取稳定。 */
    private static final double TEMPERATURE = 0.2;

    private static final int MAX_TOKENS = 800;

    /** 匹配 ```json ... ``` 或 ``` ... ``` 围栏内的 JSON。 */
    private static final Pattern JSON_FENCE_PATTERN =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
            你是一个企业级知识工程专家。请对输入的 Markdown 网页文本进行全局元数据萃取。
            必须严格输出如下 JSON 格式，不要输出任何多余的解释文字：
            {
              "title": "文章精简技术标题（不超过 30 字，去掉站点名等冗余后缀）",
              "summary": "150字以内的核心论点与技术方案摘要",
              "tags": ["核心技术标签1", "标签2", "标签3"],
              "entities": ["专有名词1", "核心协议/类名2", "关键组件3"]
            }
            要求：tags 与 entities 各 3~6 项，使用原文中出现的技术术语，不要臆造。
            """;

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public DocMetadataExtractor(LLMClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 萃取文档元数据。
     *
     * @param fallbackTitle   切片 2 已提取的标题（LLM 失败时兜底）
     * @param markdownContent 纯净 Markdown 正文
     * @return 元数据；任何失败均返回 {@link DocMetadata#fallback(String)}
     */
    public DocMetadata extract(String fallbackTitle, String markdownContent) {
        String content = markdownContent == null ? "" : markdownContent;
        if (content.isBlank()) {
            return DocMetadata.fallback(fallbackTitle);
        }
        String snippet = content.length() > CONTEXT_SNIPPET_CHARS
                ? content.substring(0, CONTEXT_SNIPPET_CHARS)
                : content;
        String userPrompt = "【待分析文档标题】: " + (fallbackTitle == null ? "" : fallbackTitle)
                + "\n【正文节选】:\n" + snippet;

        try {
            // chatOrFallback 的最后一个参数是 Mock 供应者：未配 API Key 时返回确定性 JSON，
            // 后续解析路径与真实 LLM 完全一致，便于联调。
            String raw = llmClient.chatOrFallback(
                    SYSTEM_PROMPT, userPrompt, TEMPERATURE, MAX_TOKENS,
                    () -> mockJson(fallbackTitle));
            DocMetadata parsed = objectMapper.readValue(extractJson(raw), DocMetadata.class);
            if (parsed.title().isBlank() && (fallbackTitle != null && !fallbackTitle.isBlank())) {
                return new DocMetadata(fallbackTitle, parsed.summary(), parsed.tags(), parsed.entities());
            }
            return parsed;
        } catch (Exception e) {
            log.warn("[DocMetadataExtractor] LLM 元数据萃取失败，启用确定性降级: {}", e.getMessage());
            return DocMetadata.fallback(fallbackTitle);
        }
    }

    /**
     * 从 LLM 原始响应中捞出 JSON：优先围栏内，其次首个 { 到最后一个 }，最后原文。
     */
    String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        Matcher matcher = JSON_FENCE_PATTERN.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1).trim();
        }
        return raw.trim();
    }

    /**
     * 未配置 API Key 时的确定性 Mock（与真实 LLM 输出同格式，走同一条解析路径）。
     */
    private String mockJson(String fallbackTitle) {
        String title = (fallbackTitle == null || fallbackTitle.isBlank()) ? "未命名技术文档" : fallbackTitle;
        return """
                {
                  "title": "%s",
                  "summary": "自动降级：未配置 LLM API Key，已按文档标题生成占位摘要，正文已完成清洗与分块。",
                  "tags": ["TechDoc", "Curated"],
                  "entities": []
                }
                """.formatted(title.replace("\"", "'"));
    }
}
