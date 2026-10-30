package com.nexusmind.gateway.controller;

import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.CodeAnalysisRequest;
import com.nexusmind.api.service.ICodeOrchestrationService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 代码分析 SSE 推流网关（切片5）。
 *
 * <p><b>必须落在 gateway（WebFlux 栈）而非 supervisor（Servlet MVC）：</b>
 * supervisor 不支持 Flux / ServerSentEvent 返回类型（评审 P0-4）；且统一入口承载
 * JWT / RBAC / 黑名单等安全层，不可旁路。
 *
 * <p><b>链路：</b>前端 SSE → 本控制器 → {@code @DubboReference ICodeOrchestrationService}
 * （Supervisor 编排）→ {@code ICodeAnalysisService}（agent-workers 执行）。
 * 阻塞的 unary 编排调用卸载到 boundedElastic，RBAC 上下文在 worker 线程上挂载 / 清理，
 * 与 AgentGatewayController 的 Triple 流式调用同语义。
 */
@RestController
public class CodeAnalysisStreamController {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalysisStreamController.class);

    /** 任务中断标志: taskId -> isCancelled（前端断连后不再向前端推流） */
    private static final Map<String, AtomicBoolean> TASK_CANCELLATION_FLAGS = new ConcurrentHashMap<>();

    // 切片5：Triple 协议强制 fastjson2，规避 Hessian2 反序列化 record DTO 崩溃
    @DubboReference(parameters = {"serialization=fastjson2"})
    private ICodeOrchestrationService codeOrchestrationService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * 代码符号分析异步推流接口。
     * 前端用 EventSource / ReadableStream 消费，AbortController 可取消。
     */
    @GetMapping(value = "/api/v1/code/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> streamCodeAnalysis(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "projectName", required = false) String projectName,
            @RequestParam(name = "projectSubPath", required = false) String projectSubPath,
            @RequestParam(name = "language", defaultValue = "JAVA") String language) {

        // 1. JWT 鉴权与身份提取（与 AgentGatewayController / CognitiveGatewayController 同源）
        // 兼容用户侧契约：projectName 作为 projectSubPath 的别名
        String resolvedProject = (projectName != null && !projectName.isBlank()) ? projectName
                : (projectSubPath != null && !projectSubPath.isBlank()) ? projectSubPath
                : "nexusmind-agent-workers";
        String agentRole = extractAgentRoleFromJwt(authHeader);
        if (agentRole == null) {
            return Flux.just(Map.of(
                    "taskId", taskId == null ? "" : taskId,
                    "timestamp", System.currentTimeMillis(),
                    "stage", "AUTH_ERROR",
                    "content", "【认证错误】无效的 JWT Token 或缺少 role 信息"));
        }

        String userId = extractUserIdFromJwt(authHeader);
        String resolvedTaskId = (taskId == null || taskId.isBlank())
                ? "CODE-" + System.currentTimeMillis() : taskId;
        log.info(">>> [CodeAnalysisGateway] 代码分析流请求, taskId={}, role={}, userId={}, project={}",
                resolvedTaskId, agentRole, userId, resolvedProject);

        AtomicBoolean isCancelled = new AtomicBoolean(false);
        TASK_CANCELLATION_FLAGS.put(resolvedTaskId, isCancelled);

        // 2. 阻塞编排调用卸载到 boundedElastic；RBAC 上下文在 worker 线程上挂载 / 清理
        return Mono.fromCallable(() -> {
                    try {
                        AgentRbacContext.setCurrentAgent(agentRole);
                        CodeAnalysisRequest request =
                                new CodeAnalysisRequest(resolvedTaskId, resolvedProject, language);
                        return codeOrchestrationService.orchestrateCodeAnalysis(request);
                    } finally {
                        AgentRbacContext.clearContext();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany((List<CodeAnalysisProgress> frames) -> Flux.fromIterable(frames)
                        .concatMap(frame -> Mono.just(toSseFrame(frame))
                                .delayElement(Duration.ofMillis(150)))
                        // 收到 cancel 信号后立即终止推流（前端「停止分析」即时生效）
                        .takeWhile(frame -> !isCancelled.get()))
                .doFinally(signalType -> {
                    log.info("[CodeAnalysisGateway] SSE 流程结束 (signal={}), taskId={}", signalType, resolvedTaskId);
                    TASK_CANCELLATION_FLAGS.remove(resolvedTaskId);
                })
                .onErrorResume(throwable -> {
                    log.error("[CodeAnalysisGateway] 编排调用异常: {}", throwable.getMessage());
                    // RBAC 拦截：以友好 SSE 帧返回，而非断流
                    if (throwable instanceof RpcException rpcEx
                            && rpcEx.getCode() == RpcException.FORBIDDEN_EXCEPTION) {
                        // 安全熔断：以 SECURITY_VIOLATION 帧返回，前端做红色专属告警；
                        // content 同时携带 SecurityGuard / AccessDenied 关键词，供前端关键词兜底识别
                        return Flux.just(Map.of(
                                "taskId", resolvedTaskId,
                                "timestamp", System.currentTimeMillis(),
                                "stage", "SECURITY_VIOLATION",
                                "content", "已触发 SecurityGuard 访问控制熔断（AccessDenied）："
                                        + "CodeAgent 试图越权访问非代码沙箱区域（如日记目录/系统私密数据）"));
                    }
                    return Flux.just(Map.of(
                            "taskId", resolvedTaskId,
                            "timestamp", System.currentTimeMillis(),
                            "stage", "ERROR",
                            "content", "【编排错误】" + throwable.getMessage()));
                });
    }

    /**
     * 主动中断入口（前端 AbortController 联动）。
     *
     * <p>前端点击「停止分析」→ 切断 SSE 长连接，并 POST 本端点写入取消标志位。
     * 当前为取消信号入口（标志位已置位，SSE 后续帧不再向前端推流）；
     * worker 侧「按标志在下一个文件读取时安全退出」依赖 Triple 服务端流落地后的闭环，
     * 属切片5 剩余 20%（详见交付说明），此处先打通前端→网关的信号接缝。
     */
    @PostMapping("/api/v1/code/analyze/cancel")
    public Mono<Map<String, Object>> cancelAnalysis(
            @RequestParam(name = "taskId") String taskId) {
        AtomicBoolean flag = TASK_CANCELLATION_FLAGS.get(taskId);
        if (flag != null) {
            flag.set(true);
        }
        log.info("[CodeAnalysisGateway] 收到取消信号, taskId={}, 已置位={}", taskId, flag != null);
        return Mono.just(Map.of(
                "taskId", taskId,
                "cancelled", flag != null,
                "stage", "CANCELLED"));
    }

    /** 将进度帧转译为前端 SSE 友好的 Map（保留 CodeAnalysisProgress 全部字段） */
    private Map<String, Object> toSseFrame(CodeAnalysisProgress frame) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", frame.taskId());
        map.put("timestamp", frame.timestamp());
        map.put("stage", frame.stage());
        map.put("currentFile", frame.currentFile());
        map.put("processedFiles", frame.processedFiles());
        map.put("totalFiles", frame.totalFiles());
        map.put("payloadJson", frame.payloadJson());
        map.put("content", String.format("[%s] %s (%d/%d)",
                frame.stage(),
                frame.currentFile().isEmpty() ? "" : frame.currentFile(),
                frame.processedFiles(), frame.totalFiles()));
        return map;
    }

    private String extractAgentRoleFromJwt(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("[CodeAnalysisGateway] 缺少或无效的 Authorization header");
                return null;
            }
            String role = jwtTokenProvider.getRoleFromToken(authHeader.substring(7));
            if (role == null || role.trim().isEmpty()) {
                return null;
            }
            AgentRole agentRole = AgentRole.fromId(role);
            if (agentRole == null) {
                log.warn("[CodeAnalysisGateway] 无效的 Agent 角色值: {}", role);
                return null;
            }
            return role;
        } catch (Exception e) {
            log.error("[CodeAnalysisGateway] JWT role 提取失败: {}", e.getMessage());
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
