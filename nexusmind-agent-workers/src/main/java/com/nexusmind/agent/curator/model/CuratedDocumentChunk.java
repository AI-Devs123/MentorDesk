package com.nexusmind.agent.curator.model;

import java.util.Map;

/**
 * 独立知识碎片（切片 3 的最终产物，Week 7 RAG 索引的直接输入）。
 *
 * @param chunkId     片段局部哈希标识：SHA256(docId + "_" + chunkIndex) 前 16 位
 * @param docId       文档全局唯一标识
 * @param chunkIndex  分块全局序号（从 0 开始）
 * @param totalChunks 该文档分块总数
 * @param textContent 语义完整的 Markdown 片段（代码围栏保证闭合）
 * @param metadata    挂载的文档级元数据 + 片段级定位信息
 */
public record CuratedDocumentChunk(
        String chunkId,
        String docId,
        int chunkIndex,
        int totalChunks,
        String textContent,
        Map<String, Object> metadata
) {
}
