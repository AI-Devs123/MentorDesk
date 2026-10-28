package com.nexusmind.api.rag.dto;

import java.io.Serializable;

/**
 * 混合检索请求参数（切片 3 契约）。
 *
 * <p>通过 Dubbo Triple 在 Gateway 与 nexusmind-rag 之间传输，故必须 {@link Serializable}。
 *
 * @param query        检索自然语句
 * @param collection   目标集合（journal / code / web_kb），受 RBAC 白名单约束
 * @param topK         稠密与稀疏单路召回数（默认 20~30）
 * @param fusedTopN    RRF 融合后截断数量（默认 5~10）
 * @param enableRerank 是否启用慢路径精排（切片 4 落地，当前保留契约位、默认 false 走纯 RRF 快路径）
 */
public record HybridSearchRequest(
        String query,
        String collection,
        int topK,
        int fusedTopN,
        boolean enableRerank
) implements Serializable {
}
