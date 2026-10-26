package com.nexusmind.api.service;

import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.CodeAnalysisRequest;

import java.util.List;

/**
 * CodeAgent 代码符号分析服务（公共契约）。
 *
 * <p>由 nexusmind-agent-workers 的 {@code CodeAgentServiceImpl} 实现并以
 * {@code @DubboService(interfaceClass = ICodeAnalysisService.class)} 导出到 Nacos；
 * nexusmind-supervisor 的 CodeAgentFacade 经 {@code @DubboReference} 远程调用。
 *
 * <p>与 {@link ITutorService} 同构：supervisor 不得本地注入 agent 实现类，
 * 必须经本接口 + Dubbo 薄门面访问，确保 RBAC 在 RPC 层由
 * {@code AgentRbacDubboFilter} 强制执行。
 *
 * <p>切片1 交付边界：返回全部进度帧列表（同步聚合），Supervisor 侧再适配为
 * SSE / Dubbo 服务端流；内部执行仍是逐文件流式（见 ProjectSymbolAnalyzer 的 sink 回调）。
 */
public interface ICodeAnalysisService {

    /**
     * 解析项目符号表与语法拓扑，逐阶段产出进度帧。
     *
     * @param request 含 taskId / projectSubPath / language
     * @return 进度帧序列：SCANNING → PARSING* → INDEXED* → (COMPLETED | FAILED)
     */
    List<CodeAnalysisProgress> analyzeProject(CodeAnalysisRequest request);
}
