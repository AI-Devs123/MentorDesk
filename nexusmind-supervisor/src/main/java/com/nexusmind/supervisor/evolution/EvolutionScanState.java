package com.nexusmind.supervisor.evolution;

import java.io.Serializable;
import java.util.List;

/**
 * 知识进化扫描态（切片 1 Redis 暂存对象）。
 *
 * <p>复用 {@code SnapshotRepository} 的 Redis 模式（key 前缀 + TTL + null 安全），
 * 把每次后置扫描的「命中概况 + 关联点」落盘，供前端回灌与切片 2 关联抽取读取。
 *
 * <p>切片 2 在尾部追加 {@code associations} 字段：本扫描发现的新旧知识关联点列表，
 * 经 Jackson 自动序列化（record + List<record> 已就绪），保证 Redis 往返可还原。
 */
public record EvolutionScanState(
        String taskId,
        String agentRole,
        String collection,
        String query,
        int candidateCount,
        String topDocId,
        float topRrfScore,
        /** 切片 2：本次扫描发现的新旧知识关联点（无则空列表） */
        List<KnowledgeAssociation> associations,
        long scannedAt
) implements Serializable {

    public static final long serialVersionUID = 1L;

    /** 无关联点时的便捷构造（关联点默认空列表）。 */
    public static EvolutionScanState withoutLinks(String taskId, String agentRole, String collection,
                                                  String query, int candidateCount, String topDocId,
                                                  float topRrfScore, long scannedAt) {
        return new EvolutionScanState(taskId, agentRole, collection, query, candidateCount,
                topDocId, topRrfScore, List.of(), scannedAt);
    }
}
