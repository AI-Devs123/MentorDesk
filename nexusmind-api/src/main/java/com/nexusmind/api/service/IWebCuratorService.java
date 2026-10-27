package com.nexusmind.api.service;

import com.nexusmind.api.dto.WebCurateRequest;
import com.nexusmind.api.dto.WebCurateResult;
import com.nexusmind.security.rbac.AgentSecured;

/**
 * WebCurator 网页策展入库服务（公共契约）。
 *
 * <p>由 nexusmind-agent-workers 的 {@code WebCuratorAgent} 实现并以
 * {@code @DubboService(interfaceClass = IWebCuratorService.class)} 导出到 Nacos；
 * nexusmind-supervisor 的 WebCuratorFacade 经 {@code @DubboReference} 远程调用。
 *
 * <p>与 {@link ICodeAnalysisService} 同构：supervisor 不得本地注入 agent 实现类，
 * 必须经本接口 + Dubbo 薄门面访问，确保 RBAC 在 RPC 层由
 * {@code AgentRbacDubboFilter} 强制执行。
 *
 * <p>切片 4 交付边界：返回一次同步聚合的入库结果（落盘路径 + docId + 元数据 + 分块规模）；
 * Supervisor 侧再适配为 SSE / Dubbo 服务端流。内部抓取、提纯、萃取、落盘全部发生在
 * agent-workers 进程。
 */
public interface IWebCuratorService {

    /**
     * 抓取目标网页 → 正文提纯 → LLM 结构化萃取 → 自适应语义分块 → 沙箱落盘。
     *
     * @param request 含 requestId / url / forceRenderJs
     * @return 入库结果（docId / 落盘路径 / 元数据 / 分块数等）
     */
    @AgentSecured(allowedAgents = {"supervisor", "webCurator"}, audit = true)
    WebCurateResult curateAndIngest(WebCurateRequest request);
}
