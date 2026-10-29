package com.nexusmind.supervisor.evolution;

import com.nexusmind.api.rag.dto.FusedCandidate;

import java.io.Serializable;
import java.util.List;

/**
 * 新旧知识关联点（切片 2 核心载体）。
 *
 * <p>由 {@link KnowledgeEvolutionService#extractAssociations} 基于 RRF 融合候选产出：
 * 当一条历史文档被<b>稠密（语义）</b>与<b>稀疏（字面）</b>两路同时召回、且都进前
 * {@code RANK_WINDOW}（默认 3）时，视为一个「新旧知识关联点」，驱动个人知识网络自进化。
 *
 * <h3>为何不用 rrfScore 做阈值？</h3>
 * {@code FusedCandidate.rrfScore} = RRF 融合分 = Σ 1/(k+rank)，k=60，
 * 取值范围仅 ≈ 0 ~ 0.033（单命中≈0.016，双命中≈0.033），<b>不是归一化 0~1 相似度</b>。
 * 原计划 W8 的 0.65~0.85 阈值带对它永远不命中，故切片 2 改用
 * {@link FusedCandidate#bothHit()} + rank 窗口的<b>启发式</b>（详见 {@code extractAssociations}）。
 */
public record KnowledgeAssociation(
        /** 本次新落盘资产 docId（journal/tutor/code 任务可能为 null） */
        String newDocId,
        /** 关联到的历史资产 docId */
        String relatedDocId,
        /** 所属 rag 集合（journal / code / web_kb） */
        String collection,
        /** 融合分（仅作参考展示，不参与阈值判定） */
        float rrfScore,
        /** 稠密通道排名 */
        int denseRank,
        /** 稀疏通道排名 */
        int sparseRank,
        /** 关联类型，如 BOTH_HIT_TOP3 */
        String relationType,
        /** 发现时间戳（ms） */
        long discoveredAt
) implements Serializable {

    public static final long serialVersionUID = 1L;

    /** 由融合候选便捷构造关联点（同一次扫描共享 discoveredAt）。 */
    public static KnowledgeAssociation of(String newDocId, FusedCandidate c, String relationType, long discoveredAt) {
        return new KnowledgeAssociation(newDocId, c.docId(), c.collection(), c.rrfScore(),
                c.denseRank(), c.sparseRank(), relationType, discoveredAt);
    }
}
