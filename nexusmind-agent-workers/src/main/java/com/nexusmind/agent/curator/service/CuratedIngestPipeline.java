package com.nexusmind.agent.curator.service;

import com.nexusmind.agent.curator.chunker.AdaptiveSemanticChunker;
import com.nexusmind.agent.curator.cleaner.CleanArticlePipeline;
import com.nexusmind.agent.curator.extractor.DocMetadataExtractor;
import com.nexusmind.agent.curator.model.CuratedDocumentChunk;
import com.nexusmind.agent.curator.model.DocMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * 切片 3 编排服务：把切片 2 的纯净 Markdown 转成「全局元数据 + 语义片段集合」的入库载荷，
 * 供 Week 7 的向量化与倒排索引直接消费。
 * <p>
 * docId 生成策略：<b>URL 的 SHA256 前 12 位</b>而非纯随机 UUID。
 * 这样同一篇网页重复抓取会得到相同 docId，入库时天然幂等（可覆盖旧版本），
 * 而随机 UUID 会导致同一文章在知识库里累积多份副本。
 */
@Service
public class CuratedIngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(CuratedIngestPipeline.class);

    private final DocMetadataExtractor metadataExtractor;
    private final AdaptiveSemanticChunker chunker;

    public CuratedIngestPipeline(DocMetadataExtractor metadataExtractor,
                                 AdaptiveSemanticChunker chunker) {
        this.metadataExtractor = metadataExtractor;
        this.chunker = chunker;
    }

    /**
     * 处理切片 2 的产物。
     *
     * @param document 切片 2 的纯净文档
     * @return 入库载荷（docId + 元数据 + 全文 + 片段集合）
     */
    public IngestPayload process(CleanArticlePipeline.CleanMarkdownDocument document) {
        if (document == null || document.markdown() == null || document.markdown().isBlank()) {
            throw new IllegalArgumentException("入库失败：清洗后的正文为空，无法分块");
        }
        String docId = buildDocId(document.originUrl());

        // 标题优先用切片 2 已提取的（含 og:title），LLM 萃取时作为兜底与上下文
        DocMetadata metadata = metadataExtractor.extract(document.title(), document.markdown());

        List<CuratedDocumentChunk> chunks = chunker.chunkDocument(
                docId, document.markdown(), metadata, document.originUrl());

        log.info("[CuratedIngest] docId={} title={} chunks={}",
                docId, metadata.title(), chunks.size());

        return new IngestPayload(docId, metadata, document.markdown(), document.originUrl(), chunks);
    }

    /**
     * docId = "doc_" + SHA256(url) 前 12 位；URL 为空时退化为随机（不该发生，但别让链路断）。
     */
    private String buildDocId(String originUrl) {
        if (originUrl == null || originUrl.isBlank()) {
            return "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(originUrl.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                String h = Integer.toHexString(0xff & hash[i]);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return "doc_" + hex;
        } catch (Exception e) {
            return "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
    }

    /**
     * 入库载荷。
     *
     * @param docId        文档全局唯一标识（URL 哈希，幂等）
     * @param metadata     全局元数据
     * @param fullMarkdown 完整纯净正文
     * @param originUrl    原始地址
     * @param chunks       语义片段集合
     */
    public record IngestPayload(
            String docId,
            DocMetadata metadata,
            String fullMarkdown,
            String originUrl,
            List<CuratedDocumentChunk> chunks
    ) {
    }
}
