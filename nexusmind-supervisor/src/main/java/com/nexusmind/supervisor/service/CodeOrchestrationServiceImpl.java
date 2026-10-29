package com.nexusmind.supervisor.service;

import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.CodeAnalysisRequest;
import com.nexusmind.api.service.ICodeAnalysisService;
import com.nexusmind.api.service.ICodeOrchestrationService;
import com.nexusmind.security.rbac.AgentSecured;
import com.nexusmind.supervisor.evolution.KnowledgeEvolutionService;
import com.nexusmind.supervisor.evolution.TaskCompletionEvent;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代码分析编排实现（Supervisor 层 - 薄编排层）。
 *
 * <p>对齐 {@link JournalReviewFacade} / {@link TutorFacade} 的「@DubboReference 远程引用 agent-workers」
 * 思路，但本类本身又被网关经 {@code @DubboReference ICodeOrchestrationService} 远程引用——
 * 形成「网关 → Supervisor(编排) → agent-workers(执行)」的三段式进程边界，全程 RBAC 在 RPC 层强制。
 *
 * <p><b>执行边界：</b>Supervisor 不触碰任何文件 / AST，只做编排与进度帧聚合；真正的解析全部发生在
 * agent-workers 进程（{@link ICodeAnalysisService} 实现）。
 */
@DubboService(interfaceClass = ICodeOrchestrationService.class, timeout = 60000)
@Service
public class CodeOrchestrationServiceImpl implements ICodeOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(CodeOrchestrationServiceImpl.class);

    /**
     * Dubbo 消费者引用 agent-workers 的 CodeAgent（公共契约接口）。
     * 服务方在 CodeAgentServiceImpl 上以 @DubboService(interfaceClass = ICodeAnalysisService.class) 导出。
     */
    @DubboReference(timeout = 60000)
    private ICodeAnalysisService codeAnalysisService;

    /** 切片 8-1：知识自进化后置钩子（required=false，缺失时不阻断编排）。 */
    @Autowired(required = false)
    private KnowledgeEvolutionService evolutionService;

    @AgentSecured(allowedAgents = {"supervisor", "codeAgent"})
    @Override
    public List<CodeAnalysisProgress> orchestrateCodeAnalysis(CodeAnalysisRequest request) {
        log.info("[CodeOrchestration] 收到编排请求: taskId={}, project={}, language={}",
                request.taskId(), request.projectSubPath(), request.language());

        // 1. 下发到 CodeAgent（跨进程 Dubbo 调用，agent-workers 进程内执行 AST 遍历）
        List<CodeAnalysisProgress> frames = codeAnalysisService.analyzeProject(request);

        // 2. 切片 8-1：知识自进化后置钩子 —— 代码分析完成后异步发起相似度扫描，非阻塞返回
        if (evolutionService != null) {
            evolutionService.onTaskCompleted(TaskCompletionEvent.forCode(request.taskId(), frames));
        }

        // 3. 编排层可在此扇出到 Tutor/Journal 做跨 Agent 协同（后续切片扩展点）
        log.info("[CodeOrchestration] 编排完成: taskId={}, 共 {} 帧", request.taskId(), frames.size());
        return frames;
    }
}
