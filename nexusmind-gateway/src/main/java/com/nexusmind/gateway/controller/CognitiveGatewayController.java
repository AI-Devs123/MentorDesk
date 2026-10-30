package com.nexusmind.gateway.controller;

import com.nexusmind.agent.facade.CognitiveCancelResponse;
import com.nexusmind.agent.facade.CognitiveStreamChunk;
import com.nexusmind.agent.facade.CognitiveStreamRequest;
import com.nexusmind.agent.facade.CognitiveWorkflowFacade;
import com.nexusmind.security.auth.JwtTokenProvider;
import com.nexusmind.security.rbac.AgentRbacContext;
import com.nexusmind.security.rbac.AgentRole;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 认知协同流式网关入口 (Week4 切片4)
 *
 * 复刻 AgentGatewayController 的成熟模式:
 * JWT 提取 role+userId -> RBAC 上下文挂载 -> Dubbo Triple 流式调用
 * -> SSE 推前端 -> 断连时跨进程 RST_STREAM -> doFinally 清理上下文。
 *
 * 端点必须挂 gateway (WebFlux 栈): supervisor 为 Servlet MVC，
 * Flux/ServerSentEvent 返回类型不被支持 (切片4 评审 P0-4)，
 * 且统一入口承载 JWT/RBAC/黑名单等安全层，不可旁路。
 *
 * userId 从 JWT 提取 (不接受可伪造的 X-User-Id header, 评审 P1-4)。
 */
@RestController
public class CognitiveGatewayController {

    private static final Logger log = LoggerFactory.getLogger(CognitiveGatewayController.class);

    /** 任务中断标志: taskId -> isCancelled (网关侧不再向前端推流) */
    private static final Map<String, AtomicBoolean> TASK_CANCELLATION_FLAGS = new ConcurrentHashMap<>();

    // 切片5：Triple 协议强制 fastjson2，规避 Hessian2 反序列化 record DTO 崩溃
    @DubboReference(parameters = {"serialization=fastjson2"})
    private CognitiveWorkflowFacade cognitiveWorkflowFacade;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * 端到端流式认知复盘协同接口
     * 支持前端 ReadableStream 消费与 AbortController 取消
     */
    @GetMapping(value = "/api/v1/cognitive/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> streamCognitiveReview(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("rawContent") String rawContent) {

        return Flux.<Map<String, Object>>create(sink -> {
            String taskId = "COG-" + System.currentTimeMillis();

            // 1. JWT 鉴权与身份提取 (与 AgentGatewayController 同源)
            String agentRole = extractAgentRoleFromJwt(authHeader);
            if (agentRole == null) {
                sink.next(Map.of(
                        "taskId", taskId,
                        "timestamp", System.currentTimeMillis(),
                        "stage", "AUTH_ERROR",
                        "content", "【认证错误】无效的 JWT Token 或缺少 role 信息"));
                sink.complete();
                return;
            }

            String userId = extractUserIdFromJwt(authHeader);
            log.info(">>> [CognitiveGateway] 认知流请求, taskId={}, role={}, userId={}", taskId, agentRole, userId);

            // 2. 网关侧中断标志
            AtomicBoolean isCancelled = new AtomicBoolean(false);
            TASK_CANCELLATION_FLAGS.put(taskId, isCancelled);

            // 3. RBAC 上下文挂载 (AgentRbacDubboFilter 经 attachment 校验 supervisor 角色)
            try {
                AgentRbacContext.setCurrentAgent(agentRole);
            } catch (Exception e) {
                log.error("[CognitiveGateway] RBAC 上下文挂载失败: {}", e.getMessage(), e);
                sink.next(Map.of(
                        "taskId", taskId,
                        "timestamp", System.currentTimeMillis(),
                        "stage", "RBAC_SETUP_ERROR",
                        "content", "【RBAC设置错误】无法设置Agent角色到RPC上下文: " + e.getMessage()));
                sink.complete();
                return;
            }

            // 4. 构造 Dubbo 请求并发起 Triple 流式调用
            CognitiveStreamRequest request = CognitiveStreamRequest.newBuilder()
                    .setTaskId(taskId)
                    .setRawContent(rawContent)
                    .setUserId(userId)
                    .build();

            try {
                cognitiveWorkflowFacade.executeCognitiveStream(request,
                        new org.apache.dubbo.common.stream.StreamObserver<CognitiveStreamChunk>() {

                            @Override
                            public void onNext(CognitiveStreamChunk chunk) {
                                if (isCancelled.get()) {
                                    return;
                                }
                                Map<String, Object> frame = new LinkedHashMap<>();
                                frame.put("taskId", chunk.getTaskId());
                                frame.put("timestamp", System.currentTimeMillis());
                                frame.put("stage", chunk.getAgent() != null ? chunk.getAgent() : "Supervisor");
                                frame.put("status", chunk.getStatus());
                                frame.put("content", chunk.getMessage());

                                if ("data".equals(chunk.getFrameType())) {
                                    frame.put("event", chunk.getEventType());
                                    frame.put("data", chunk.getPayloadJson());
                                }
                                sink.next(frame);
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                log.error("[CognitiveGateway] 下游 Triple 推流异常: {}", throwable.getMessage());
                                if (isCancelled.get()) {
                                    sink.complete();
                                    return;
                                }
                                sink.error(throwable);
                            }

                            @Override
                            public void onCompleted() {
                                if (!isCancelled.get()) {
                                    sink.next(Map.of(
                                            "taskId", taskId,
                                            "timestamp", System.currentTimeMillis(),
                                            "stage", "done",
                                            "content", "WORKFLOW_COMPLETED"));
                                    sink.complete();
                                }
                            }
                        });
            } catch (Exception e) {
                log.error("[CognitiveGateway] 调用 Dubbo 认知流服务异常", e);
                sink.error(e);
            }

            // 5. 前端断连 -> 跨进程 RST_STREAM
            sink.onCancel(() -> {
                log.warn("[CognitiveGateway] 前端切断 SSE 连接, taskId={}, 正在下发 RST_STREAM...", taskId);
                isCancelled.set(true);
                CognitiveCancelResponse resp = Mono.fromCallable(
                                () -> cognitiveWorkflowFacade.cancelCognitiveTask(taskId))
                        .subscribeOn(Schedulers.boundedElastic())
                        .block();
                log.warn("[CognitiveGateway] RST_STREAM 已送达 Supervisor: taskId={}, found={}, interrupted={}",
                        taskId, resp.isFound(), resp.isInterrupted());
            });
        }).doFinally(signal -> {
            log.info("[CognitiveGateway] SSE 流程结束 (signal={})", signal);
            AgentRbacContext.clearContext();
        });
    }

    private String extractAgentRoleFromJwt(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("[CognitiveGateway] 缺少或无效的 Authorization header");
                return null;
            }
            String role = jwtTokenProvider.getRoleFromToken(authHeader.substring(7));
            if (role == null || role.trim().isEmpty()) {
                return null;
            }
            AgentRole agentRole = AgentRole.fromId(role);
            if (agentRole == null) {
                log.warn("[CognitiveGateway] 无效的 Agent 角色值: {}", role);
                return null;
            }
            return role;
        } catch (Exception e) {
            log.error("[CognitiveGateway] JWT role 提取失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractUserIdFromJwt(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return "USER-ANONYMOUS";
            }
            String username = jwtTokenProvider.getUsernameFromToken(authHeader.substring(7));
            return username != null && !username.trim().isEmpty() ? username : "USER-ANONYMOUS";
        } catch (Exception e) {
            return "USER-ANONYMOUS";
        }
    }
}
