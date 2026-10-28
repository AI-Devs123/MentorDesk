package com.nexusmind.api.rag.dto;

import java.io.Serializable;
import java.util.Map;

/**
 * 单通道检索候选命中项。
 *
 * <p>由 LuceneDualEngineManager 的 searchDense / searchSparse 产出，携带 1-based 的通道内 rank，
 * 供下游 ReciprocalRankFusionService 做 RRF 融合（融合仅需 rank，score 是否归一化不影响）。
 */
public record ScoredCandidate(
        String docId,
        String collection,
        String content,
        float score,
        int rank,
        Map<String, String> metadata
) implements Serializable {
}
