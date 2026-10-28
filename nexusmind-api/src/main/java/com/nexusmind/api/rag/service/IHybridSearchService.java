package com.nexusmind.api.rag.service;

import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;

/**
 * 混合检索中枢 Dubbo Triple RPC 契约接口（切片 3）。
 *
 * <p>Provider 侧由 nexusmind-rag 的 {@code HybridSearchServiceImpl} 以
 * {@code @DubboService(protocol = "tri", version = "1.0.0")} 导出；
 * Consumer 侧（nexusmind-gateway）以 {@code @DubboReference} 引用。
 *
 * <p>方法级 RBAC 仅由 Provider 内的 {@code AgentCollectionRbacGuard} 实施
 * （现有 {@code AgentRbacDubboFilter} 只做 {@code @AgentSecured} 方法级校验，
 * 不感知 collection 维度，故集合白名单为切片 3 新增逻辑）。
 */
public interface IHybridSearchService {

    /**
     * 执行混合检索（Dense HNSW + Sparse BM25 + RRF 融合）。
     *
     * @param request 检索请求
     * @return 综合重排候选集（已脱敏）
     * @throws SecurityException 当调用方角色无权访问目标 collection 时由 Provider 抛出，
     *                           经 Triple 透传为 {@code RpcException(FORBIDDEN_EXCEPTION)}
     */
    HybridSearchResponse hybridSearch(HybridSearchRequest request);
}
