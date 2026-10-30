package com.nexusmind.gateway.controller;

import com.nexusmind.api.dto.WebCurateProgress;
import com.nexusmind.api.dto.WebCurateRequest;
import com.nexusmind.api.service.IWebCuratorOrchestrationService;
import com.nexusmind.security.auth.JwtTokenProvider;
import com.nexusmind.security.rbac.AgentRbacContext;
import com.nexusmind.security.rbac.AgentRole;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网页策展 SSE 推流网关（Week 6 切片5）。
 *
 * <p><b>必须落在 gateway（WebFlux 栈）而非 supervisor（Servlet MVC）：</b>
 * supervisor 不支持 {@code Flux} / {@code ServerSentEvent} 返回类型；且统一入口承载
 * JWT / RBAC / 熔断等安全层，不可旁路。与 {@link CodeAnalysisStreamController} 同构。
 *
 * <p><b>链路：</b>前端 SSE → 本控制器 → {@code @DubboReference IWebCuratorOrchestrationService}
 * （Supervisor 编排）→ {@code IWebCuratorService}（agent-workers 执行）。
 * 阻塞的 unary 编排调用卸载到 boundedElastic，RBAC 上下文在 worker 线程上挂载 / 清理。
 *
 * <p><b>双重角色门禁：</b>除 JWT 有效性外，本控制器还显式校验 role ∈ {supervisor, webCurator}
 * （对应切片 4 新增的 {@code WEB_INGEST} 权限）。未在白名单内直接下发 SECURITY_VIOLATION 帧，
 * 不等到 Dubbo Provider 端才发现——前端可立即红色告警而不是看到一条普通错误。
 */
@RestController
public class CurateStreamController {

    private static final Logger log = LoggerFactory.getLogger(CurateStreamController.class);

    /** 与 @AgentSecured(allowedAgents) 保持一致：仅编排中枢与网页策展主体可发起入库 */
    private static final Set<String> ALLOWED_ROLES = Set.of("supervisor", "webCurator");

    /** 任务中断标志: taskId -> isCancelled（前端断连后不再向前端推流） */
    private static final Map<String, AtomicBoolean> TASK_CANCELLATION_FLAGS = new ConcurrentHashMap<>();

    // 切片5：Triple 协议强制 fastjson2，规避 Hessian2 反序列化 record DTO 崩溃
    @DubboReference(parameters = {"serialization=fastjson2"})
    private IWebCuratorOrchestrationService webCuratorOrchestrationService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * 网页萃取入库异步推流接口。
     * 前端用 fetch + ReadableStream 消费（原生 EventSource 无法附加 Authorization 头），
     * AbortController 可取消。
     */
    @GetMapping(value = "/api/v1/curate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> streamCurate(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(name = "url") String url,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "forceRender", defaultValue = "false") boolean forceRender) {

        String resolvedTaskId = (taskId == null || taskId.isBlank())
                ? "WEB-" + System.currentTimeMillis() : taskId;

        // 1. JWT 鉴权与身份提取
        String agentRole = extractAgentRoleFromJwt(authHeader);
        if (agentRole == null) {
            return Flux.just(Map.of(
                    "taskId", resolvedTaskId,
                    "timestamp", "",
                    "stage", "AUTH_ERROR",
                    "agentName", "SecurityGuard",
                    "content", "【认证错误】无效的 JWT Token 或缺少 role 信息"));
        }

        // 2. 角色白名单门禁（WEB_INGEST 权限的网关侧前置校验）
        if (!ALLOWED_ROLES.contains(agentRole)) {
            log.warn("[CurateGateway] 角色越权: role={}, taskId={}", agentRole, resolvedTaskId);
            return Flux.just(Map.of(
                    "taskId", resolvedTaskId,
                    "timestamp", "",
                    "stage", "SECURITY_VIOLATION",
                    "agentName", "SecurityGuard",
                    "content", "已触发 SecurityGuard 访问控制熔断（AccessDenied）："
                            + "当前角色 [" + agentRole + "] 不具备 WEB_INGEST 权限，"
                            + "仅 supervisor / webCurator 可发起网页入库"));
        }

        String userId = extractUserIdFromJwt(authHeader);
        log.info(">>> [CurateGateway] 网页策展流请求, taskId={}, role={}, userId={}, url={}",
                resolvedTaskId, agentRole, userId, url);

        AtomicBoolean isCancelled = new AtomicBoolean(false);
        TASK_CANCELLATION_FLAGS.put(resolvedTaskId, isCancelled);

        // 3. 阻塞编排调用卸载到 boundedElastic；RBAC 上下文在 worker 线程上挂载 / 清理
        return Mono.fromCallable(() -> {
                    try {
                        AgentRbacContext.setCurrentAgent(agentRole);
                        WebCurateRequest request =
                                new WebCurateRequest(resolvedTaskId, url, forceRender);
                        return webCuratorOrchestrationService.orchestrateWebCurate(request);
                    } finally {
                        AgentRbacContext.clearContext();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany((List<WebCurateProgress> frames) -> Flux.fromIterable(frames)
                        .concatMap(frame -> Mono.just(toSseFrame(frame))
                                .delayElement(Duration.ofMillis(150)))
                        // 收到 cancel 信号后立即终止推流（前端「停止」即时生效）
                        .takeWhile(frame -> !isCancelled.get()))
                .doFinally(signalType -> {
                    log.info("[CurateGateway] SSE 流程结束 (signal={}), taskId={}",
                            signalType, resolvedTaskId);
                    TASK_CANCELLATION_FLAGS.remove(resolvedTaskId);
                })
                .onErrorResume(throwable -> {
                    log.error("[CurateGateway] 编排调用异常: {}", throwable.getMessage());
                    // RBAC 拦截：以友好 SSE 帧返回，而非断流
                    if (throwable instanceof RpcException rpcEx
                            && rpcEx.getCode() == RpcException.FORBIDDEN_EXCEPTION) {
                        return Flux.just(Map.of(
                                "taskId", resolvedTaskId,
                                "timestamp", "",
                                "stage", "SECURITY_VIOLATION",
                                "agentName", "SecurityGuard",
                                "content", "已触发 SecurityGuard 访问控制熔断（AccessDenied）："
                                        + "当前角色无权调用 WebCurator 入库服务"));
                    }
                    return Flux.just(Map.of(
                            "taskId", resolvedTaskId,
                            "timestamp", "",
                            "stage", "ERROR",
                            "agentName", "Supervisor",
                            "content", "【编排错误】" + throwable.getMessage()));
                });
    }

    /**
     * 主动中断入口（前端 AbortController 联动）。
     *
     * <p>前端点击「停止」→ 切断 SSE 长连接，并 POST 本端点写入取消标志位，
     * 使网关停止向该连接推流（编排调用本身在 boundedElastic 上继续跑完，不做强制线程中断）。
     */
    @PostMapping("/api/v1/curate/cancel")
    public Mono<Map<String, Object>> cancelCurate(
            @RequestParam(name = "taskId") String taskId) {
        AtomicBoolean flag = TASK_CANCELLATION_FLAGS.get(taskId);
        if (flag != null) {
            flag.set(true);
        }
        log.info("[CurateGateway] 收到取消信号, taskId={}, 已置位={}", taskId, flag != null);
        return Mono.just(Map.of(
                "taskId", taskId,
                "cancelled", flag != null,
                "stage", "CANCELLED"));
    }

    /**
     * 将进度帧转译为前端 SSE 友好的 Map。
     *
     * <p>{@code content} 拼成设计稿样式的单行日志 {@code [10:14:02] WebCuratorAgent → ...}，
     * 前端控制台可直接渲染；结构化字段另存，供 WebCurateResultCard 精确取用。
     */
    private Map<String, Object> toSseFrame(WebCurateProgress frame) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", frame.taskId());
        map.put("timestamp", frame.timestamp());
        map.put("stage", frame.stage());
        map.put("agentName", frame.agentName());
        map.put("message", frame.message());
        map.put("payloadJson", frame.payloadJson());
        map.put("content", String.format("[%s] %s → %s",
                frame.timestamp(), frame.agentName(), frame.message()));
        return map;
    }

    private String extractAgentRoleFromJwt(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("[CurateGateway] 缺少或无效的 Authorization header");
                return null;
            }
            String role = jwtTokenProvider.getRoleFromToken(authHeader.substring(7));
            if (role == null || role.trim().isEmpty()) {
                return null;
            }
            AgentRole agentRole = AgentRole.fromId(role);
            if (agentRole == null) {
                log.warn("[CurateGateway] 无效的 Agent 角色值: {}", role);
                return null;
            }
            return role;
        } catch (Exception e) {
            log.error("[CurateGateway] JWT role 提取失败: {}", e.getMessage());
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
