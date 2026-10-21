package com.nexusmind.supervisor.service;

import com.nexusmind.agent.facade.AgentStreamRequest;
import com.nexusmind.agent.facade.AgentStreamResponse;
import com.nexusmind.agent.facade.AgentStreamingFacade;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@DubboService
public class SupervisorStreamingService implements AgentStreamingFacade {

    private static final Logger log = LoggerFactory.getLogger(SupervisorStreamingService.class);

    @Override
    public void executeTaskStream(AgentStreamRequest request, StreamObserver<AgentStreamResponse> responseObserver) {
        String taskId = request.getTaskId();
        String agentName = request.getAgentName();
        String payload = request.getPayload();

        log.info("[Supervisor] 接收到任务调度请求 -> TaskId: [{}], TargetAgent: [{}], Payload: [{}]", 
                taskId, agentName, payload);

        // 1. 中断控制标志位
        AtomicBoolean isCancelled = new AtomicBoolean(false);

        // 2. 启动普通线程执行长耗时 Agent 业务编排
        Thread agentThread = new Thread(() -> {
            try {
                // 模拟 Agent 内部拆解的多阶段生命周期
                String[] stages = {
                    "SECURITY_GUARD_SCAN",  // 安全沙箱与敏感词拦截
                    "DAG_ORCHESTRATION",   // 任务依赖拓扑编排
                    "WORKER_DISPATCH",     // 分发到具体 WorkerAgent
                    "RAG_RETRIEVAL",       // 混合检索召回 (Milvus + BM25)
                    "TASK_COMPLETED"       // 执行完毕
                };

                for (int i = 0; i < stages.length; i++) {
                    // 阶段前置检查：如果收到客户端中断或当前线程被打断，立即跳出
                    if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                        log.warn("[Supervisor] 任务已被客户端取消，放弃执行剩余阶段，TaskId: {}", taskId);
                        return;
                    }

                    String currentStage = stages[i];

                    // 构造响应帧
                    AgentStreamResponse chunk = AgentStreamResponse.newBuilder()
                            .setTaskId(taskId)
                            .setAgentName("Supervisor")
                            .setTimestamp(Instant.now().toEpochMilli())
                            .setStage(currentStage)
                            .setDeltaChunk(String.format("【%s】阶段处理中...", currentStage))
                            .setRequiresConfirmation(false)
                            .build();

                    // 向下游（网关）推流
                    responseObserver.onNext(chunk);

                    // 模拟每个阶段的耗时（虚拟线程在此处休眠时会自动释放底层载体线程）
                    Thread.sleep(700);
                }

                // 所有阶段推流完毕，通知客户端正常关闭流
                responseObserver.onCompleted();
                log.info("[Supervisor] 任务全流程推流完毕，TaskId: {}", taskId);

            } catch (InterruptedException e) {
                log.warn("[Supervisor] 虚拟线程响应中断信号退出，TaskId: {}", taskId);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[Supervisor] 任务执行出现异常，TaskId: {}", taskId, e);
                // 向客户端发送错误异常帧
                responseObserver.onError(e);
            }
        }, "agent-task-" + taskId);
        agentThread.start();
    }

    @Override
    public StreamObserver<AgentStreamRequest> biDirectionalStream(StreamObserver<AgentStreamResponse> responseObserver) {
        return new StreamObserver<AgentStreamRequest>() {
            @Override
            public void onNext(AgentStreamRequest request) {
                log.info("[Supervisor] 收到双向流请求: {}", request.getPayload());
                // 可以在这里处理双向流的逻辑
                executeTaskStream(request, responseObserver);
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("[Supervisor] 双向流发生错误", throwable);
                responseObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                log.info("[Supervisor] 双向流已关闭");
                responseObserver.onCompleted();
            }
        };
    }
}