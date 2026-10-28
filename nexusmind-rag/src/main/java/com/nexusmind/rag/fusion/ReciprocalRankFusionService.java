package com.nexusmind.rag.fusion;

import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.api.rag.dto.RetrievalCoverage;
import com.nexusmind.api.rag.dto.ScoredCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合服务（Reciprocal Rank Fusion，切片 2）。
 *
 * <p>把稠密（Dense KNN 语义）与稀疏（Sparse BM25 词法）两路召回按 rank 融合，
 * 不依赖各自 score 的量纲对齐，仅用排名：
 * <pre>  RRF score(doc) = Σ_{r∈命中通道} 1 / (k + rank)   </pre>
 * 默认 {@code k = 60}（Cormack 等人经验值，对 rank 噪声鲁棒）。
 *
 * <p>融合后每个候选保留两路各自的 rank，供 {@link #coverage(List)} 统计
 * BOTH_HIT / DENSE_ONLY / SPARSE_ONLY 覆盖度——这是判断混合检索是否「互补」而非「冗余」的关键指标。
 */
@Component
public class ReciprocalRankFusionService {

    private static final float K = 60f;

    /**
     * 融合两路召回结果。
     *
     * @param dense   稠密通道候选（含 1-based rank）
     * @param sparse  稀疏通道候选（含 1-based rank）
     * @param topN    融合后返回的 Top-N（按 RRF 分数降序）
     * @return 融合候选列表，按 rrfScore 降序
     */
    public List<FusedCandidate> fuse(String collection, List<ScoredCandidate> dense, List<ScoredCandidate> sparse,
                                     int topN) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Builder> builders = new HashMap<>();

        accumulate(scores, builders, dense, true);
        accumulate(scores, builders, sparse, false);

        List<FusedCandidate> result = new ArrayList<>(builders.size());
        for (Map.Entry<String, Builder> e : builders.entrySet()) {
            Builder b = e.getValue();
            double score = scores.getOrDefault(e.getKey(), 0d);
            result.add(new FusedCandidate(
                    b.doc.docId(), b.doc.collection(), b.doc.content(),
                    (float) score, b.denseRank, b.sparseRank, b.doc.metadata()));
        }
        result.sort(Comparator.comparingDouble(FusedCandidate::rrfScore).reversed());
        return result.subList(0, Math.min(topN, result.size()));
    }

    private void accumulate(Map<String, Double> scores, Map<String, Builder> builders,
                            List<ScoredCandidate> candidates, boolean isDense) {
        if (candidates == null) {
            return;
        }
        for (ScoredCandidate c : candidates) {
            if (c == null || c.docId() == null) {
                continue;
            }
            double contribution = 1.0 / (K + c.rank());
            scores.merge(c.docId(), contribution, Double::sum);
            Builder b = builders.computeIfAbsent(c.docId(), k -> new Builder(c));
            if (isDense) {
                b.denseRank = c.rank();
            } else {
                b.sparseRank = c.rank();
            }
        }
    }

    /**
     * 统计融合结果的覆盖度。
     */
    public RetrievalCoverage coverage(List<FusedCandidate> fused) {
        if (fused == null || fused.isEmpty()) {
            return new RetrievalCoverage(0, 0, 0, 0);
        }
        long both = 0, denseOnly = 0, sparseOnly = 0;
        for (FusedCandidate f : fused) {
            if (f.bothHit()) {
                both++;
            } else if (f.denseOnly()) {
                denseOnly++;
            } else if (f.sparseOnly()) {
                sparseOnly++;
            }
        }
        return new RetrievalCoverage(both, denseOnly, sparseOnly, fused.size());
    }

    /** 融合构建的中间态：保留首个见到的候选（取 content/metadata）与两路 rank。 */
    private static final class Builder {
        final ScoredCandidate doc;
        int denseRank = 0;
        int sparseRank = 0;

        Builder(ScoredCandidate doc) {
            this.doc = doc;
        }
    }
}
