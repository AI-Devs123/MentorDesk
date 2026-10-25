package com.nexusmind.agent.facade;

import com.nexusmind.security.rbac.AgentSecured;
import org.apache.dubbo.common.stream.StreamObserver;

/**
 * 认知协同流式服务接口（Dubbo Triple 协议）
 *
 * Week4 切片4: Supervisor 编排 JournalAgent(落盘) -> Persona(异步旁路)
 * -> TutorAgent(启发提问) 的认知复盘 DAG，过程帧经 Gateway 转 SSE 推前端。
 *
 * 调用链: Gateway(WebFlux, JWT/RBAC) -> 本服务(supervisor) -> agent-workers
 * 接口层 RBAC 与 AgentStreamingFacade 一致: 仅放行 supervisor 角色
 * (Gateway 以 JWT 提取的 supervisor 身份调用；DAG 内部对 TutorAgent 的
 * 下游调用由 CognitiveWorkflowFacadeImpl 自行 setCurrentAgent("tutorAgent"))。
 */
public interface CognitiveWorkflowFacade {

    /**
     * 服务端流式调用: 执行端到端认知复盘 DAG，过程帧逐条推给观察者
     *
     * @param request           含 rawContent/userId/taskId 的请求
     * @param responseObserver  响应观察者 (onNext 推帧 / onCompleted 完成 / onError 异常)
     */
    @AgentSecured(allowedAgents = {"supervisor"})
    void executeCognitiveStream(CognitiveStreamRequest request, StreamObserver<CognitiveStreamChunk> responseObserver);

    /**
     * 取消指定认知任务（网关在前端 SSE 断连时调用，即 RST_STREAM 语义）。
     * Supervisor 置位中断标志并唤醒执行线程，停止后续 DAG 阶段。
     *
     * @param taskId 任务ID
     * @return 取消处理结果
     */
    @AgentSecured(allowedAgents = {"supervisor"})
    CognitiveCancelResponse cancelCognitiveTask(String taskId);
}
