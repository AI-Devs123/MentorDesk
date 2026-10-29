package com.nexusmind.api.rag.service;

import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;
import com.nexusmind.security.rbac.AgentSecured;

/**
 * 混合检索中枢 Dubbo Triple RPC 契约接口（切片 3 契约，切片 5 补方法级 RBAC）。
 *
 * <p>Provider 侧由 nexusmind-rag 的 {@code HybridSearchServiceImpl} 以
 * {@code @DubboService(protocol = "tri", version = "1.0.0")} 导出；
 * Consumer 侧（nexusmind-gateway）以 {@code @DubboReference} 引用。
 *
 * <p><b>RBAC 两层防线（切片 5）</b>：
 * <ol>
 *   <li><b>方法级</b>：本接口的 {@code @AgentSecured} 由 {@code AgentRbacDubboFilter}
 *       在 Dubbo Provider 侧解析 —— 校验 {@code RpcContext} 里
 *       {@code x-nexusmind-agent-role} 携带的角色是否在 {@code allowedAgents} 白名单内。
 *       注解必须标在<b>接口方法</b>上：Filter 通过 {@code invoker.getInterface()} 取方法再读注解。</li>
 *   <li><b>集合级</b>：Provider 业务方法入口的 {@code AgentCollectionRbacGuard}
 *       按「角色 → collection 白名单」二次校验，补齐 Filter 不感知 collection 维度的缺口。</li>
 * </ol>
 *
 * <p><b>不要挂 {@code @Sanitize}</b>：该注解在 rag 上下文是死注解 ——
 * {@code SanitizationAspect} 只脱敏 String 入参、从不处理返回值，且其宿主
 * {@code SecurityAutoConfiguration} 未被任何 {@code AutoConfiguration.imports} 注册。
 * 真实 PII 防护由 {@code SearchResponseSanitizer} 在业务方法内显式完成。
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
    @AgentSecured(
            allowedAgents = {"supervisor", "journalAgent", "codeAgent", "webCurator", "tutorAgent"},
            readOnly = true,
            audit = true
    )
    HybridSearchResponse hybridSearch(HybridSearchRequest request);
}
