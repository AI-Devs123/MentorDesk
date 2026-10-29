package com.nexusmind.supervisor.evolution;

import java.io.Serializable;

/**
 * 知识进化扫描态（切片 1 Redis 暂存对象）。
 *
 * <p>复用 {@code SnapshotRepository} 的 Redis 模式（key 前缀 + TTL + null 安全），
 * 把每次后置扫描的「命中概况」落盘，供切片 2 关联抽取与前端回灌读取。
 */
public record EvolutionScanState(
        String taskId,
        String agentRole,
        String collection,
        String query,
        int candidateCount,
        String topDocId,
        float topRrfScore,
        long scannedAt
) implements Serializable {

    public static final long serialVersionUID = 1L;
}
