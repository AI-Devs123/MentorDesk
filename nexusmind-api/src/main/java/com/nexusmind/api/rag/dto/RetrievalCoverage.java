package com.nexusmind.api.rag.dto;

import java.io.Serializable;

/**
 * RRF 融合覆盖度统计（切片 2）。
 *
 * <p>回答「融合结果里有多少来自双路交集、多少来自单路」——这是衡量 Week7 混合检索
 * 是否真正「互补」而非「冗余」的核心指标。三类计数：
 * <ul>
 *   <li>{@code bothHit}：双路都召回（BOTH_HIT）—— 高置信；</li>
 *   <li>{@code denseOnly}：仅向量召回（DENSE_ONLY）—— 长尾语义；</li>
 *   <li>{@code sparseOnly}：仅词法召回（SPARSE_ONLY）—— 精确字面命中。</li>
 * </ul>
 */
public record RetrievalCoverage(
        long bothHit,
        long denseOnly,
        long sparseOnly,
        long total
) implements Serializable {

    public double bothHitRatio() {
        return total == 0 ? 0d : (double) bothHit / total;
    }
}
