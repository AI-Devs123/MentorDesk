package com.nexusmind.api.rag.dto;

import java.io.Serializable;
import java.util.Map;

/**
 * 混合索引通用录入对象。
 *
 * <p>作为「数据源（日记/代码 AST/网页切片）→ Lucene 双引擎」的传输单元，由 Supervisor 在编排阶段
 * 通过 EmbeddingClient 完成向量化后注入。{@code vector} 维度必须与目标 collection 锁定的维度一致，
 * 否则 LuceneDualEngineManager 会拒绝写入（防止 HNSW 索引损坏）。
 */
public record RagIndexDocument(
        String docId,
        String collection,      // journal, code, web_kb
        String content,        // 用于 BM25 全文分词与生成的文本
        float[] vector,        // 密集特征向量 (bge-small 512 维 或 bge-m3 1024 维)
        Map<String, String> metadata // 标量元数据 (如 language, date, tags, file_path)
) implements Serializable {
}
