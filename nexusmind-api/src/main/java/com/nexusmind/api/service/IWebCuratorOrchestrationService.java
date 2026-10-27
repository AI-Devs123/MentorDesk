package com.nexusmind.api.service;

import com.nexusmind.api.dto.WebCurateProgress;
import com.nexusmind.api.dto.WebCurateRequest;
import com.nexusmind.security.rbac.AgentSecured;

import java.util.List;

/**
 * 网页策展编排服务（Supervisor 侧公共契约）。
 *
 * <p>由 nexusmind-supervisor 的 {@code WebCuratorOrchestrationServiceImpl} 实现并导出到 Nacos，
 * nexusmind-gateway（WebFlux）经 {@code @DubboReference} 远程调用后转译为 SSE 推流。
 *
 * <p><b>为什么必须多这一层（而不是网关直接引用 WebCuratorAgent）：</b>
 * <ul>
 *   <li>网关是 WebFlux 进程，Supervisor 是 Servlet MVC 进程，无法直接注入彼此的 Spring bean；</li>
 *   <li>「Supervisor 编排」是架构约定——所有跨 Agent 协同都应由中枢下发，
 *       与 {@link ICodeOrchestrationService} 完全同构；</li>
 *   <li>编排层承担「阶段帧聚合」与「失败转译」，让网关控制器保持薄。</li>
 * </ul>
 * 真正的抓取 / 提纯 / 萃取 / 落盘全部发生在 agent-workers 进程（{@link IWebCuratorService}）。
 *
 * <p><b>RBAC：</b>仅 supervisor / webCurator 可触发编排——前者是统一编排入口，后者是网页策展主体。
 * 越权（如 journalAgent / tutorAgent）由 AgentRbacDubboFilter 在 Provider 端拦截，
 * 网关侧再对 {@code RpcException.FORBIDDEN_EXCEPTION} 做安全熔断转译。
 */
@AgentSecured(allowedAgents = {"supervisor", "webCurator"})
public interface IWebCuratorOrchestrationService {

    /**
     * 编排一次网页萃取入库任务：下发到 WebCuratorAgent 并聚合阶段进度帧。
     *
     * <p>当前为单 Agent 编排；后续可在此扇出到 Tutor（RAG 问答）/ Journal（复盘引用）做
     * 跨 Agent 协同，而调用方（网关 SSE）的契约形态保持不变。
     *
     * @param request 含 requestId / url / forceRenderJs
     * @return 阶段帧序列：SECURITY_CHECK → FETCHING → EXTRACTED → STAGED → COMPLETED，
     *         或 SECURITY_CHECK → FETCHING → FAILED
     */
    List<WebCurateProgress> orchestrateWebCurate(WebCurateRequest request);
}
