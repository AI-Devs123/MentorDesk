package com.nexusmind.gateway.controller;

import com.nexusmind.agent.facade.AgentStreamRequest;
import com.nexusmind.agent.facade.AgentStreamResponse;
import com.nexusmind.agent.facade.AgentStreamingFacade;
import com.nexusmind.agent.facade.CancelTaskRequest;
import com.nexusmind.agent.facade.CancelTaskResponse;
import com.nexusmind.security.rbac.AgentRbacContext;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class AgentGatewayController {

    private static final Logger log = LoggerFactory.getLogger(AgentGatewayController.class);

    // 全局任务中断标志位映射表：taskId -> isCancelled
    private static final Map<String, AtomicBoolean> TASK_CANCELLATION_FLAGS = new ConcurrentHashMap<>();

    // 注入 Supervisor 暴露的 Dubbo Triple 流式服务
    @DubboReference
    private AgentStreamingFacade agentStreamingFacade;

    @GetMapping(value = "/api/v1/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> streamAgent(
            @RequestParam(name = "prompt", defaultValue = "默认任务") String prompt,
            @RequestParam(name = "agent", defaultValue = "Supervisor") String agent,
            @RequestParam(name = "agentRole", defaultValue = "supervisor") String agentRole) {

        return Flux.<Map<String, Object>>create(sink -> {
            String taskId = "TASK-" + System.currentTimeMillis();
            AtomicBoolean isCancelled = new AtomicBoolean(false);

            // 注册该任务的中断标志位
            TASK_CANCELLATION_FLAGS.put(taskId, isCancelled);

            // 设置 Agent 身份到 RPC 上下文（RBAC 权限检查将使用此身份）
            AgentRbacContext.setCurrentAgent(agentRole);

            log.info(">>> [Gateway] 收到前端 SSE 握手请求, taskId: {}, prompt: {}, agent: {}, agentRole: {}",
                    taskId, prompt, agent, agentRole);
            log.info("📡 [Gateway] 下游 Dubbo Triple 开始调用 Supervisor 流式服务");

            AgentStreamRequest request = AgentStreamRequest.newBuilder()
                    .setTaskId(taskId)
                    .setAgentName(agent)
                    .setPayload(prompt)
                    .build();

            try {
                // 发起 Dubbo Triple 流式调用
                agentStreamingFacade.executeTaskStream(request, new StreamObserver<AgentStreamResponse>() {
                    @Override
                    public void onNext(AgentStreamResponse data) {
                        try {
                            // 检查是否已被取消
                            if (isCancelled.get()) {
                                log.warn("⚠️ [Gateway] 任务已被前端中断，不再向前端推送数据, taskId: {}", taskId);
                                return;
                            }

                            // 推送每帧数据给前端（携带网关侧真实 taskId，便于前端展示与取消）
                            sink.next(Map.of(
                                    "taskId", taskId,
                                    "timestamp", data.getTimestamp(),
                                    "stage", data.getStage(),
                                    "content", data.getDeltaChunk()
                            ));
                            log.debug("[Gateway] 推送阶段数据: [{}] -> {}", data.getStage(), data.getDeltaChunk());
                        } catch (Exception e) {
                            log.error("❌ [Gateway] 推送数据异常: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        log.error("❌ [Gateway] 后端 Triple 推流异常: {} ({})",
                                throwable.getMessage(), throwable.getClass().getSimpleName());

                        // 如果是因为客户端中断导致的，则正常处理
                        if (isCancelled.get()) {
                            log.info("ℹ️ [Gateway] 错误由客户端中断引起 -> 正常关闭 SSE 连接, taskId: {}", taskId);
                            sink.complete();
                        } else {
                            sink.error(throwable);
                        }
                    }

                    @Override
                    public void onCompleted() {
                        if (!isCancelled.get()) {
                            log.info("✅ [Gateway] 后端 Triple 推流完毕，准备关闭 SSE 通道: {}", taskId);
                            sink.complete();
                        } else {
                            log.info("ℹ️ [Gateway] 后端已完成，但客户端已中断，直接关闭连接: {}", taskId);
                        }
                    }
                });

            } catch (Exception e) {
                log.error("❌ [Gateway] 调用 Dubbo 服务异常: {}", e.getMessage(), e);
                sink.error(e);
            }

            // 核心机制：感知前端 SSE 连接断开 -> 向后端 Supervisor 发送 RST_STREAM
            sink.onCancel(() -> {
                log.warn("⚠️ [Gateway] 前端客户端切断 SSE 连接 (HTTP 断开), taskId: {}", taskId);
                log.warn("🛑 [Gateway] 正在向下游 Supervisor 发送中断信号 (RST_STREAM)...");

                // 1) 网关侧置位：不再向前端推送后续数据
                isCancelled.set(true);
                log.info("📤 [Gateway] 网关侧中断标志位已设置: taskId={}", taskId);

                // 2) 跨进程下发 RST_STREAM：调用 Supervisor 的 CancelTask，立即停止其执行线程
                CancelTaskRequest cancelReq = CancelTaskRequest.newBuilder()
                        .setTaskId(taskId)
                        .build();
                Mono.fromCallable(() -> agentStreamingFacade.cancelTask(cancelReq))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(resp -> log.warn(
                                        "✓ [Gateway] RST_STREAM 已送达 Supervisor: taskId={}, found={}, interrupted={}, activeTasks={}",
                                        taskId, resp.isFound(), resp.isInterrupted(), resp.getActiveTaskCount()),
                                err -> log.error("❌ [Gateway] 向 Supervisor 发送 RST_STREAM 失败: {}", err.getMessage()));
            });

            // Flux 层级的取消事件（冗余保护）
        }).doOnCancel(() -> {
            log.info("📊 [Gateway] Flux 响应式管道被取消");
        }).doFinally(signalType -> {
            log.info("🏁 [Gateway] SSE 流程结束 (signalType: {})", signalType);
            // 清理 RBAC 上下文
            AgentRbacContext.clearContext();
        });
    }

    /**
     * 获取任务的中断状态（可用于监控或调试）
     */
    public static AtomicBoolean getTaskCancellationFlag(String taskId) {
        return TASK_CANCELLATION_FLAGS.getOrDefault(taskId, new AtomicBoolean(false));
    }

    /**
     * 判断任务是否仍在网关的任务表中（用于取消接口的存在性校验）
     */
    public static boolean hasTask(String taskId) {
        return TASK_CANCELLATION_FLAGS.containsKey(taskId);
    }

    /**
     * 清理已完成的任务的中断标志
     */
    public static void cleanupTask(String taskId) {
        TASK_CANCELLATION_FLAGS.remove(taskId);
    }
}
