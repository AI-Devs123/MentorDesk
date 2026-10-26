package com.nexusmind.agent.code.service;

import com.nexusmind.agent.code.orchestrator.ProjectSymbolAnalyzer;
import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.CodeAnalysisRequest;
import com.nexusmind.api.service.ICodeAnalysisService;
import com.nexusmind.security.rbac.AgentSecured;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * CodeAgent 核心服务（agent-workers 层 - 代码库 AST 解析与符号治理）。
 *
 * <p>切片5 落地时本类为 agent 进程内组件，切片1 起提升为跨进程公共契约：
 * 经 {@code @DubboService(interfaceClass = ICodeAnalysisService.class)} 导出到 Nacos，
 * supervisor 侧 CodeAgentFacade 远程引用——AST 遍历全部发生在 agent-workers 进程。
 *
 * <p><b>执行边界（CODE_AGENT）：</b>
 * <ul>
 *   <li>仅可在代码沙箱 {@code projects/} 下读写与 AST 解析；</li>
 *   <li>禁止访问 journal / 健康等私密目录（物理隔离 + RBAC 双层）；</li>
 *   <li>挂 {@code @AgentSecured(allowedAgents = {"supervisor", "codeAgent"})}，
 *       经 Dubbo 导出后由 AgentRbacDubboFilter 在 RPC 层强制执行。</li>
 * </ul>
 */
@Service
@DubboService(interfaceClass = ICodeAnalysisService.class, timeout = 60000)
public class CodeAgentServiceImpl implements ICodeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CodeAgentServiceImpl.class);

    private final ProjectSymbolAnalyzer analyzer;

    public CodeAgentServiceImpl(ProjectSymbolAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @AgentSecured(allowedAgents = {"supervisor", "codeAgent"}, audit = true)
    @Override
    public List<CodeAnalysisProgress> analyzeProject(CodeAnalysisRequest request) {
        log.info("[CodeAgent] 收到符号分析请求: taskId={}, project={}, language={}",
                request.taskId(), request.projectSubPath(), request.language());

        // 聚合流式 sink 为列表返回（Supervisor 侧再适配为 SSE / Dubbo 流）
        List<CodeAnalysisProgress> frames = new ArrayList<>();
        Consumer<CodeAnalysisProgress> sink = frames::add;
        analyzer.analyze(request, sink);
        return frames;
    }
}
