package com.nexusmind.api.dto;

import java.util.List;

/**
 * 网页经 LLM 结构化萃取出的全局元数据（公共契约 DTO）。
 *
 * <p>切片 3 的 {@code DocMetadata} 是 agent-workers 内部模型，为避免让 api 契约层反向依赖
 * agent-workers 内部包，这里在 api 层定义轻量镜像，由 WebCuratorAgent 在 RPC 出口处做映射。
 */
public record WebDocMetadata(
        /** 文档标题（优先 og:title / &lt;title&gt;，LLM 兜底） */
        String title,
        /** 一句话摘要 */
        String summary,
        /** 关键词标签 */
        List<String> tags,
        /** 命名实体（人物 / 组织 / 产品等） */
        List<String> entities
) {
}
