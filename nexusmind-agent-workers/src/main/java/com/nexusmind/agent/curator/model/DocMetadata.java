package com.nexusmind.agent.curator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * 文档级全局元数据（LLM 结构化萃取的产物）。
 * <p>
 * 挂载到该文档切出的每一个 {@link CuratedDocumentChunk} 上，让片段在脱离原文后仍具备全局认知：
 * 属于哪篇文章、讲什么、涉及哪些标签与实体。
 *
 * @param title    文章精简标题
 * @param summary  150 字以内核心论点摘要
 * @param tags     分类标签（如 Dubbo3 / HTTP2 / 网络协议）
 * @param entities 核心实体（专有名词、协议名、类名）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocMetadata(
        String title,
        String summary,
        List<String> tags,
        List<String> entities
) {

    /** 规范化：null 字段收敛为空值/空列表，避免下游 NPE 与元数据缺失。 */
    public DocMetadata {
        title = title == null ? "" : title.trim();
        summary = summary == null ? "" : summary.trim();
        tags = tags == null ? Collections.emptyList() : List.copyOf(tags);
        entities = entities == null ? Collections.emptyList() : List.copyOf(entities);
    }

    /** LLM 未配置 / 调用失败 / JSON 解析失败时的确定性降级结果。 */
    public static DocMetadata fallback(String fallbackTitle) {
        String title = (fallbackTitle == null || fallbackTitle.isBlank()) ? "未命名技术文档" : fallbackTitle.trim();
        return new DocMetadata(
                title,
                "自动降级：正文已清洗归档，未提取到 LLM 摘要。",
                List.of("TechDoc", "Curated"),
                Collections.emptyList()
        );
    }
}
