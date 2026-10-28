package com.nexusmind.api.rag.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 混合检索综合响应（切片 3 契约）。
 *
 * @param query       回显请求语句
 * @param collection  回显目标集合
 * @param elapsedMs   服务端纯检索链路耗时（ms）
 * @param candidates  RRF 融合后的候选集（已按 rrfScore 降序，PII 已在服务端脱敏）
 */
public record HybridSearchResponse(
        String query,
        String collection,
        long elapsedMs,
        List<FusedCandidate> candidates
) implements Serializable {
}
