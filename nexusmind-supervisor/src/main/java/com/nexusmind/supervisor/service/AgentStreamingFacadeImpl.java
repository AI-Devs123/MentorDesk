package com.nexusmind.supervisor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.facade.AgentStreamRequest;
import com.nexusmind.agent.facade.AgentStreamResponse;
import com.nexusmind.agent.facade.CancelTaskRequest;
import com.nexusmind.agent.facade.CancelTaskResponse;
import com.nexusmind.agent.facade.DubboAgentStreamingFacadeTriple;
import com.nexusmind.security.destructive.DestructiveOperation;
import com.nexusmind.security.destructive.ExecutionSnapshot;
import com.nexusmind.security.destructive.ExecutionSuspendedException;
import com.nexusmind.security.rbac.AgentSecured;
import com.nexusmind.security.rbac.AgentRole;
import com.nexusmind.supervisor.execution.SnapshotRepository;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@DubboService // 声明为 Dubbo 服务，自动注册到 Nacos
public class AgentStreamingFacadeImpl extends DubboAgentStreamingFacadeTriple.AgentStreamingFacadeImplBase {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamingFacadeImpl.class);
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    
    // 全局任务中断标志位映射表：taskId -> isCancelled
    private static final Map<String, AtomicBoolean> TASK_CANCELLATION_FLAGS = new ConcurrentHashMap<>();

    // 运行中任务的执行线程注册表：taskId -> Thread（收到 RST_STREAM 后用于立即中断线程）
    private static final Map<String, Thread> RUNNING_THREADS = new ConcurrentHashMap<>();
    
    @Autowired
    private SnapshotRepository snapshotRepository;
    
    @Autowired
    private DemoDestructiveService demoDestructiveService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AgentSecured(allowedAgents = {"supervisor"})
    @Override
    public void executeTaskStream(AgentStreamRequest request, StreamObserver<AgentStreamResponse> responseObserver) {
        String taskId = request.getTaskId();
        String prompt = request.getPayload();
        
        log.info(">>> [Supervisor] 收到任务下发");
        log.info("    TaskId: {}", taskId);
        log.info("    Prompt: {}", prompt);

        // 获取或创建该任务的中断标志位
        AtomicBoolean isCancelled = TASK_CANCELLATION_FLAGS.computeIfAbsent(taskId, k -> new AtomicBoolean(false));

        // 使用线程池处理耗时推流（模拟虚拟线程）
        executor.submit(() -> {
            // 注册执行线程，供 cancelTask 收到 RST_STREAM 时立即中断
            RUNNING_THREADS.put(taskId, Thread.currentThread());
            try {
                // 🔥 检测是否为破坏性操作触发测试（演示 AOP 拦截）
                // 使用特殊触发词 "cleanup old_file" 避免被 Gateway 第3层拦截
                log.info(">>> [Supervisor] 收到 Prompt: {}", prompt);
                log.info(">>> [Supervisor] demoDestructiveService 注入状态: {}", 
                    demoDestructiveService != null ? "已注入" : "未注入(NULL)");
                
                if (prompt != null && (prompt.toLowerCase().contains("delete old_file.py") 
                    || prompt.toLowerCase().contains("cleanup old_file"))) {
                    log.warn("🛑 [Supervisor] 检测到破坏性操作测试: {}", prompt);
                    
                if (demoDestructiveService != null) {
                        log.warn("🛑 [Supervisor] 准备调用 demoDestructiveService.deleteOldFile()");
                        try {
                            // 触发破坏性操作，会被 AOP 拦截
                            demoDestructiveService.deleteOldFile("old_file.py");
                            log.warn("🛑 [Supervisor] deleteOldFile() 调用完成（不应该执行到这里）");
                        } catch (ExecutionSuspendedException suspendEx) {
                            // AOP 拦截成功
                            log.warn("✅ [Supervisor] AOP 成功拦截，捕获到 ExecutionSuspendedException");
                            throw suspendEx;
                        }
                    } else {
                        log.error("❌ [Supervisor] demoDestructiveService 未注入，无法测试 AOP");
                    }
                }
                
                // 定义 5 个阶段的任务流程
                List<String[]> stages = List.of(
                        new String[]{"SECURITY_GUARD_SCAN", "【安全网关】执行 RAG 安全防护检测，拦截敏感信息..."},
                        new String[]{"DAG_ORCHESTRATION", "【任务编排】生成 DAG 拓扑，规划 5 个子任务节点依赖关系..."},
                        new String[]{"WORKER_DISPATCH", "【任务分发】向具体的 WorkerAgent 集群分发执行任务..."},
                        new String[]{"RAG_RETRIEVAL", "【知识检索】混合召回 (Milvus 向量库 + BM25)，返回 Top-K 结果..."},
                        new String[]{"TASK_COMPLETED", "【执行完毕】已生成执行报告并归档到分布式存储中。"}
                );

                log.info("🚀 [Supervisor] 开始执行 {} 个阶段的推流", stages.size());

                for (String[] stage : stages) {
                    // ===== 核心中断检查点 =====
                    if (isCancelled.get()) {
                        log.warn("🛑 [Supervisor] 检测到客户端中断信号！立即停止后续阶段执行");
                        log.warn("    TaskId: {}", taskId);
                        log.warn("    当前阶段: {} (已放弃执行)", stage[0]);
                        // 不调用 onCompleted()，让客户端感知到流被中断
                        return;
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        log.warn("🛑 [Supervisor] 线程被中断，任务终止: {}", taskId);
                        Thread.currentThread().interrupt();
                        return;
                    }

                    String stageName = stage[0];
                    String stageDesc = stage[1];

                    // 构造响应帧
                    AgentStreamResponse chunk = AgentStreamResponse.newBuilder()
                            .setTaskId(taskId)
                            .setTimestamp(System.currentTimeMillis())
                            .setStage(stageName)
                            .setDeltaChunk(stageDesc)
                            .build();

                    // 向网关推流
                    responseObserver.onNext(chunk);
                    log.info("[Supervisor] ✓ 推送阶段: [{}] -> {}", stageName, stageDesc);

                    // 模拟每个阶段的耗时（700ms ~ 1.2s）
                    // 虚拟线程在此处休眠时会自动让出底层载体线程，提高并发效率
                    Thread.sleep(1200);
                }

                // 检查最后一次是否被中断
                if (isCancelled.get()) {
                    log.warn("⚠️ [Supervisor] 所有阶段已执行，但任务被标记为已中断");
                    return;
                }

                // 所有阶段推流完毕，通知客户端正常关闭流
                responseObserver.onCompleted();
                log.info("✅ [Supervisor] 任务全流程推流完毕，TaskId: {}", taskId);

            } catch (ExecutionSuspendedException suspendEx) {
                // 🚨 捕获破坏性操作挂起异常
                log.warn("⏸️ [Supervisor] 执行已挂起，等待用户确认: taskId={}", taskId);
                
                ExecutionSnapshot snapshot = suspendEx.getSnapshot();
                
                // 1. 保存快照到 Redis
                if (snapshotRepository != null) {
                    boolean saved = snapshotRepository.saveSnapshot(taskId, snapshot);
                    if (saved) {
                        log.info("✅ [Supervisor] 快照已保存到 Redis: taskId={}", taskId);
                    } else {
                        log.error("❌ [Supervisor] 快照保存失败: taskId={}", taskId);
                    }
                } else {
                    log.warn("⚠️ [Supervisor] SnapshotRepository 未注入，跳过快照保存");
                }
                
                // 2. 推送 SUSPEND_FOR_CONFIRMATION 事件到前端
                try {
                    Map<String, Object> suspendData = new HashMap<>();
                    suspendData.put("execution_id", taskId);
                    suspendData.put("token", snapshot.getConfirmationToken());
                    suspendData.put("operation_type", snapshot.getOperationType().name());
                    suspendData.put("risk_level", snapshot.getRiskLevel().name());
                    suspendData.put("description", snapshot.getOperationDescription());
                    suspendData.put("change_preview", snapshot.getChangePreview());
                    suspendData.put("affected_files", List.of("old_file.py"));
                    suspendData.put("expires_at", snapshot.getExpiresAt().toString());
                    
                    String dataJson = objectMapper.writeValueAsString(suspendData);
                    
                    AgentStreamResponse suspendFrame = AgentStreamResponse.newBuilder()
                            .setTaskId(taskId)
                            .setTimestamp(System.currentTimeMillis())
                            .setStage("SUSPEND_FOR_CONFIRMATION")
                            .setDeltaChunk(dataJson)
                            .build();
                    
                    responseObserver.onNext(suspendFrame);
                    log.info("📤 [Supervisor] 已推送挂起通知到前端");
                    
                    // 3. 平稳关闭流（等待前端通过 /api/v1/task/resume 恢复）
                    responseObserver.onCompleted();
                    log.info("🏁 [Supervisor] SSE 流已平稳关闭，等待用户裁决");
                    
                } catch (Exception jsonEx) {
                    log.error("❌ [Supervisor] 推送挂起通知失败: {}", jsonEx.getMessage(), jsonEx);
                    responseObserver.onError(jsonEx);
                }
                
            } catch (Throwable t) {
                // 🔥 处理异常包装：Spring AOP 或反射可能将异常包装为 UndeclaredThrowableException
                log.warn("⚠️ [Supervisor] 捕获异常: {} ({})", t.getMessage(), t.getClass().getSimpleName());
                
                // 提取真实异常，防范 Spring AOP / 反射包装
                Throwable actual = t;
                int depth = 0;
                while (actual != null && !(actual instanceof ExecutionSuspendedException) && depth < 5) {
                    log.debug("    第 {} 层异常: {} - {}", depth, actual.getClass().getSimpleName(), actual.getMessage());
                    actual = actual.getCause();
                    depth++;
                }
                
                // 检查是否最终提取出了 ExecutionSuspendedException
                if (actual instanceof ExecutionSuspendedException suspendEx) {
                    log.warn("✅ [Supervisor] 成功从异常链中提取出 ExecutionSuspendedException，重新处理");
                    
                    ExecutionSnapshot snapshot = suspendEx.getSnapshot();
                    
                    // 1. 保存快照到 Redis
                    if (snapshotRepository != null) {
                        boolean saved = snapshotRepository.saveSnapshot(taskId, snapshot);
                        if (saved) {
                            log.info("✅ [Supervisor] 快照已保存到 Redis: taskId={}", taskId);
                        }
                    }
                    
                    // 2. 推送 SUSPEND_FOR_CONFIRMATION 事件到前端
                    try {
                        Map<String, Object> suspendData = new HashMap<>();
                        suspendData.put("execution_id", taskId);
                        suspendData.put("token", snapshot.getConfirmationToken());
                        suspendData.put("operation_type", snapshot.getOperationType().name());
                        suspendData.put("risk_level", snapshot.getRiskLevel().name());
                        suspendData.put("description", snapshot.getOperationDescription());
                        suspendData.put("change_preview", snapshot.getChangePreview());
                        suspendData.put("affected_files", List.of("old_file.py"));
                        suspendData.put("expires_at", snapshot.getExpiresAt().toString());
                        
                        String dataJson = objectMapper.writeValueAsString(suspendData);
                        
                        AgentStreamResponse suspendFrame = AgentStreamResponse.newBuilder()
                                .setTaskId(taskId)
                                .setTimestamp(System.currentTimeMillis())
                                .setStage("SUSPEND_FOR_CONFIRMATION")
                                .setDeltaChunk(dataJson)
                                .build();
                        
                        responseObserver.onNext(suspendFrame);
                        log.info("📤 [Supervisor] 已推送挂起通知到前端");
                        responseObserver.onCompleted();
                        log.info("🏁 [Supervisor] SSE 流已平稳关闭，等待用户裁决");
                    } catch (Exception jsonEx) {
                        log.error("❌ [Supervisor] 推送挂起通知失败: {}", jsonEx.getMessage(), jsonEx);
                        responseObserver.onError(jsonEx);
                    }
                } else if (t instanceof InterruptedException) {
                    log.warn("🛑 [Supervisor] 虚拟线程被中断异常: {}", t.getMessage());
                    Thread.currentThread().interrupt();
                } else {
                    log.error("❌ [Supervisor] 推流异常: {} ({})", t.getMessage(), t.getClass().getSimpleName());
                    responseObserver.onError(t);
                }
            } finally {
                // 清理该任务的中断标志位与线程注册
                RUNNING_THREADS.remove(taskId);
                TASK_CANCELLATION_FLAGS.remove(taskId);
                log.info("🏁 [Supervisor] 任务清理完成: {}", taskId);
            }
        });
    }

    @AgentSecured(allowedAgents = {"supervisor"})
    @Override
    public StreamObserver<AgentStreamRequest> biDirectionalStream(StreamObserver<AgentStreamResponse> responseObserver) {
        return new StreamObserver<AgentStreamRequest>() {
            @Override
            public void onNext(AgentStreamRequest request) {
                log.info(">>> [Supervisor] 收到双向流请求");
                log.info("    TaskId: {}", request.getTaskId());
                log.info("    Payload: {}", request.getPayload());
                // 处理双向流的请求
                executeTaskStream(request, responseObserver);
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("❌ [Supervisor] 双向流异常: {}", throwable.getMessage());
                responseObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                log.info("✅ [Supervisor] 双向流已完成");
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * 取消任务：网关在前端 SSE 断连时通过 Dubbo 下发 RST_STREAM。
     * 收到后立即置位中断标志，并唤醒/中断正在执行的线程，实现“立即停止后续执行”。
     */
    @AgentSecured(allowedAgents = {"supervisor"})
    @Override
    public CancelTaskResponse cancelTask(CancelTaskRequest request) {
        String taskId = request.getTaskId();
        log.warn("🛑 [Supervisor] 收到网关下发的 RST_STREAM 中断信号, taskId: {}", taskId);

        AtomicBoolean flag = TASK_CANCELLATION_FLAGS.get(taskId);
        Thread worker = RUNNING_THREADS.get(taskId);
        boolean found = flag != null;
        boolean interrupted = false;

        if (flag != null) {
            flag.set(true);
            log.warn("   中断标志位已置位: taskId={}", taskId);
        } else {
            log.warn("⚠️ [Supervisor] 任务不存在或已结束（无中断标志位）: {}", taskId);
        }

        if (worker != null) {
            // 立即唤醒正在休眠/等待的执行线程，使其在下一行代码处感知中断
            worker.interrupt();
            interrupted = true;
            log.warn("⚡ [Supervisor] 已向执行线程发送中断信号（线程将立即停止后续阶段）: taskId={}", taskId);
        }

        return CancelTaskResponse.newBuilder()
                .setFound(found)
                .setInterrupted(interrupted)
                .setMessage(found
                        ? (interrupted ? "任务已取消，执行线程已立即中断" : "任务已标记取消")
                        : "任务不存在或已结束")
                .setActiveTaskCount(TASK_CANCELLATION_FLAGS.size())
                .build();
    }

    /**
     * 外部中断任务的接口（由网关调用）
     * 用于处理客户端断连时的中断信号
     */
    public static void markTaskCancelled(String taskId) {
        AtomicBoolean flag = TASK_CANCELLATION_FLAGS.get(taskId);
        if (flag != null) {
            flag.set(true);
            Thread worker = RUNNING_THREADS.get(taskId);
            if (worker != null) {
                worker.interrupt();
            }
            log.warn("🛑 [Supervisor] 任务已被外部标记为中断: {}", taskId);
        } else {
            log.warn("⚠️ [Supervisor] 尝试中断不存在的任务: {}", taskId);
        }
    }

    /**
     * 获取任务的中断状态
     */
    public static boolean isTaskCancelled(String taskId) {
        AtomicBoolean flag = TASK_CANCELLATION_FLAGS.get(taskId);
        return flag != null && flag.get();
    }

    /**
     * 获取当前正在执行的任务数
     */
    public static int getActiveTaskCount() {
        return TASK_CANCELLATION_FLAGS.size();
    }
}
