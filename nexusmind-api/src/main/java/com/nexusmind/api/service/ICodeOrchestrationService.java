package com.nexusmind.api.service;

import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.CodeAnalysisRequest;
import com.nexusmind.security.rbac.AgentSecured;

import java.util.List;

/**
 * 代码分析编排服务（Supervisor 侧公共契约）。
 *
 * <p>由 nexusmind-supervisor 的 {@code CodeOrchestrationServiceImpl} 实现并导出到 Nacos，
 * nexusmind-gateway（WebFlux）经 {@code @DubboReference} 远程调用，承担「Supervisor 编排联动」。
 *
 * <p><b>为什么存在这一层：</b>网关是 WebFlux（承载 JWT/RBAC/SSE），Supervisor 是 Servlet MVC，
 * 二者分属不同进程；网关不能直接注入 Supervisor 的 Spring bean，必须经 Dubbo 薄门面访问，
 * 与 {@link IJournalReviewService} / {@link ITutorService} 同构。真正的 AST 遍历仍发生在
 * agent-workers 进程（{@link ICodeAnalysisService}），本编排层只做任务下发、进度帧聚合与编排。
 *
 * <p><b>RBAC：</b>仅 supervisor / codeAgent 可触发编排——前者是统一编排入口，后者是代码分析的主体。
 * 越权（如 journalAgent / tutorAgent）由 AgentRbacDubboFilter 在 Provider 端拦截。
 */
@AgentSecured(allowedAgents = {"supervisor", "codeAgent"})
public interface ICodeOrchestrationService {

    /**
     * 编排一次代码符号分析任务：下发到 CodeAgent 并聚合进度帧。
     *
     * <p>当前切片为单 Agent 编排（CodeAgent）；后续切片可在此扇出到 Tutor/Journal 做
     * 跨 Agent 协同，而调用方（网关）的 SSE 形态与契约保持不变。
     *
     * @param request 含 taskId / projectSubPath / language
     * @return 进度帧序列：SCANNING → PARSING* → INDEXED* → (COMPLETED | FAILED)，
     *         网关侧再转译为 SSE 推流
     */
    List<CodeAnalysisProgress> orchestrateCodeAnalysis(CodeAnalysisRequest request);
}
