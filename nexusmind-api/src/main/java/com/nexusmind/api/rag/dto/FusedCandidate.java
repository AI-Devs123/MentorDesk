package com.nexusmind.api.rag.dto;

import java.io.Serializable;
import java.util.Map;

/**
 * RRF 融合后的候选命中项（切片 2）。
 *
 * <p>由 {@code ReciprocalRankFusionService#fuse} 产出：把稠密（Dense KNN）与稀疏（Sparse BM25）
 * 两路结果按倒数排名融合（RRF score = Σ 1/(k+rank)），并保留各自通道的 rank 供覆盖度统计。
 * {@code denseRank} / {@code sparseRank} 为 0 表示该通道未命中此文档。
 */
public record FusedCandidate(
        String docId,
        String collection,
        String content,
        float rrfScore,
        int denseRank,
        int sparseRank,
        Map<String, String> metadata
) implements Serializable {

    /** 两路均命中（BOTH_HIT），是融合质量最值得信赖的候选。 */
    public boolean bothHit() {
        return denseRank > 0 && sparseRank > 0;
    }

    /** 仅稠密通道命中（DENSE_ONLY）。 */
    public boolean denseOnly() {
        return denseRank > 0 && sparseRank == 0;
    }

    /** 仅稀疏通道命中（SPARSE_ONLY）。 */
    public boolean sparseOnly() {
        return sparseRank > 0 && denseRank == 0;
    }
}
