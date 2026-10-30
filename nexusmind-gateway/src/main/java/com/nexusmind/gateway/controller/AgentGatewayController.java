package com.nexusmind.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.facade.AgentStreamRequest;
import com.nexusmind.agent.facade.AgentStreamResponse;
import com.nexusmind.agent.facade.AgentStreamingFacade;
import com.nexusmind.agent.facade.CancelTaskRequest;
import com.nexusmind.agent.facade.CancelTaskResponse;
import com.nexusmind.security.auth.JwtTokenProvider;
import com.nexusmind.security.destructive.DestructiveOperationType;
import com.nexusmind.security.destructive.ExecutionSnapshot;
import com.nexusmind.security.destructive.RiskLevel;
import com.nexusmind.security.rbac.AgentRbacContext;
import com.nexusmind.security.rbac.AgentRole;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.DigestUtils;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class AgentGatewayController {

    private static final Logger log = LoggerFactory.getLogger(AgentGatewayController.class);

    // 全局任务中断标志位映射表：taskId -> isCancelled
    private static final Map<String, AtomicBoolean> TASK_CANCELLATION_FLAGS = new ConcurrentHashMap<>();

    // 注入 Supervisor 暴露的 Dubbo Triple 流式服务
    // 切片5：Triple 协议强制 fastjson2，规避 Hessian2 反序列化 record DTO 崩溃
    @DubboReference(parameters = {"serialization=fastjson2"})
    private AgentStreamingFacade agentStreamingFacade;
    
    // 注入 JWT Token 提供者
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    
    // 注入 Redis Template，用于第3层Token确认机制
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    // 注入 Jackson（Spring 管理的实例，已注册 JavaTimeModule，可正确序列化 Instant 字段）
    @Autowired
    private ObjectMapper objectMapper;

    // Redis Key前缀：agent:confirm:{userId}:{token}
    private static final String REDIS_CONFIRM_KEY_PREFIX = "agent:confirm:";

    // Supervisor 侧 SnapshotRepository 的快照键前缀（resume 校验依赖，两边必须一致）
    private static final String SUPERVISOR_SNAPSHOT_KEY_PREFIX = "nexusmind:snapshot:";
    
    // Token有效期（3分钟）
    private static final long TOKEN_EXPIRY_MINUTES = 3;

    @GetMapping(value = "/api/v1/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> streamAgent(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(name = "prompt", defaultValue = "默认任务") String prompt,
            @RequestParam(name = "agent", defaultValue = "Supervisor") String agent,
            @RequestParam(name = "agentRole", required = false) String agentRoleParam,  // 废弃，仅用于兼容
            @RequestParam(name = "token", required = false) String authToken,              // 身份鉴权Token（废弃）
            @RequestParam(name = "confirmToken", required = false) String confirmToken) {  // 高危操作确认Token

        return Flux.<Map<String, Object>>create(sink -> {
            String taskId = "TASK-" + System.currentTimeMillis();
            
            // ✅ 第 1 步：从 Authorization header 中提取并解析 JWT Token
            log.info("📥 [Gateway] 开始提取 JWT Token，authHeader 是否存在: {}", authHeader != null);
            if (authHeader != null) {
                log.info("📥 [Gateway] authHeader 内容: {}...", authHeader.substring(0, Math.min(50, authHeader.length())));
            }
            
            String agentRole = extractAgentRoleFromJwt(authHeader);
            if (agentRole == null) {
                log.error("🛑 [Gateway] 无法从 JWT Token 中提取 Agent 角色");
                sink.next(Map.of(
                        "taskId", taskId,
                        "timestamp", System.currentTimeMillis(),
                        "stage", "AUTH_ERROR",
                        "content", "【认证错误】无效的 JWT Token 或缺少 role 信息"
                ));
                sink.complete();
                return;
            }
            
            log.info("🔐 [Gateway] 从 JWT 中提取的 Agent 角色: {}", agentRole);
            log.info("🔐 [Gateway] Agent 角色类型: {}, 是否为 null: {}, 是否为空字符串: {}", 
                    agentRole != null ? agentRole.getClass().getSimpleName() : "null",
                    agentRole == null,
                    agentRole != null && agentRole.trim().isEmpty());
            
            // 1. 提取用户ID（从JWT中提取）
            String userId = extractUserIdFromJwt(authHeader);
            
            // 2. 🚨【第2层防护】前置安全网关检查：拦截绝对禁止的高危黑名单操作
            SecurityCheckResult checkResult = performSecurityGatewayCheck(prompt);
            if (!checkResult.isAllowed) {
                log.warn("🛑 [Gateway-Layer2] 黑名单拦截: {}", checkResult.reason);
                sink.next(Map.of(
                        "taskId", taskId,
                        "timestamp", System.currentTimeMillis(),
                        "stage", "LAYER2_BLACKLIST_BLOCKED",
                        "content", checkResult.reason
                ));
                sink.complete();
                return;
            }
            
            // 3. 🔐【第3层防护】破坏性操作Token确认机制
            boolean isDestructive = isDestructiveButNotBlacklisted(prompt);
            if (isDestructive) {
                if (confirmToken == null || confirmToken.trim().isEmpty()) {
                    // 场景A: 破坏性操作，未带确认令牌 -> 生成Token，挂起操作
                    TokenGenerationResult tokenResult = generateConfirmToken(userId, prompt);
                    if (tokenResult != null) {
                        log.warn("🛑 [Gateway-Layer3] 破坏性操作需要确认: prompt={}", prompt);

                        // 【Resume 联调】按 Supervisor 的快照键格式补写执行快照。
                        // 挂起发生在网关层、Supervisor 从未参与，若不补写，
                        // 核准时 SnapshotRepository.validateAndConsumeToken 找不到快照 → TOKEN_INVALID
                        saveSupervisorSnapshot(taskId, tokenResult, prompt);

                        Map<String, Object> confirmData = new HashMap<>();
                        confirmData.put("taskId", taskId);
                        confirmData.put("timestamp", System.currentTimeMillis());
                        confirmData.put("stage", "SUSPEND_FOR_CONFIRMATION");
                        confirmData.put("execution_id", taskId);
                        confirmData.put("token", tokenResult.token);
                        confirmData.put("promptHash", tokenResult.promptHash);
                        confirmData.put("operation_type", "DELETE_FILE");
                        confirmData.put("risk_level", "HIGH");
                        confirmData.put("description", "【破坏性操作确认】" + prompt);
                        confirmData.put("affected_files", List.of("old_file.py"));
                        confirmData.put("expires_at", java.time.Instant.now().plusSeconds(TOKEN_EXPIRY_MINUTES * 60).toString());
                        confirmData.put("content", "【第3层：破坏性操作确认】操作 [" + prompt + "] 需要二次确认。请在 " + TOKEN_EXPIRY_MINUTES + " 分钟内携带 Token 重试。");
                        sink.next(confirmData);
                    } else {
                        sink.next(Map.of(
                                "taskId", taskId,
                                "timestamp", System.currentTimeMillis(),
                                "stage", "LAYER3_TOKEN_GENERATION_FAILED",
                                "content", "【第3层：系统错误】Token生成失败（Redis未就绪），操作已阻断"
                        ));
                    }
                    sink.complete();
                    return;
                } else {
                    // 场景B: 携带了确认令牌 -> 直接转发给 Supervisor（验证交由 Supervisor 端 resumeTask 接口处理）
                    log.info("🎫 [Gateway-Layer3] 已携带确认Token，将在 /api/v1/task/resume 接口验证: token={}", confirmToken);
                }
            }
            
            AtomicBoolean isCancelled = new AtomicBoolean(false);

            // 注册该任务的中断标志位
            TASK_CANCELLATION_FLAGS.put(taskId, isCancelled);

            // 设置 Agent 身份到 RPC 上下文（从 JWT 中提取的真实 role）
            try {
                log.info("🔧 [Gateway] 尝试将 agentRole 设置到 RpcContext: {}", agentRole);
                AgentRbacContext.setCurrentAgent(agentRole);
                log.info("✅ [Gateway] AgentRole 已成功设置到 RpcContext");
                
                // 验证是否成功设置
                String verifyRole = AgentRbacContext.getCurrentAgent();
                log.info("✅ [Gateway] 验证: RpcContext 中当前 Agent: {}", verifyRole);
            } catch (Exception e) {
                log.error("❌ [Gateway] 设置 AgentRole 到 RpcContext 失败: {}", e.getMessage(), e);
                sink.next(Map.of(
                        "taskId", taskId,
                        "timestamp", System.currentTimeMillis(),
                        "stage", "RBAC_SETUP_ERROR",
                        "content", "【RBAC设置错误】无法设置Agent角色到RPC上下文: " + e.getMessage()
                ));
                sink.complete();
                return;
            }

            log.info(">>> [Gateway] 收到前端 SSE 握手请求, taskId: {}, prompt: {}, agent: {}, jwtRole: {}, userId: {}",
                    taskId, prompt, agent, agentRole, userId);
            log.info("📡 [Gateway] 下游 Dubbo Triple 开始调用 Supervisor 流式服务");

            AgentStreamRequest request = AgentStreamRequest.newBuilder()
                    .setTaskId(taskId)
                    .setAgentName(agent)
                    .setPayload(prompt)
                    .setConfirmationToken(confirmToken != null ? confirmToken : "")
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
                            // 检查是否为RBAC拦截（RpcException且FORBIDDEN）
                            if (throwable instanceof org.apache.dubbo.rpc.RpcException) {
                                org.apache.dubbo.rpc.RpcException rpcEx = (org.apache.dubbo.rpc.RpcException) throwable;
                                if (rpcEx.getCode() == org.apache.dubbo.rpc.RpcException.FORBIDDEN_EXCEPTION) {
                                    // RBAC拦截，以友好的SSE消息返回
                                    log.warn("🛑 [Gateway] RBAC拦截: {}", throwable.getMessage());
                                    sink.next(Map.of(
                                            "taskId", taskId,
                                            "timestamp", System.currentTimeMillis(),
                                            "stage", "RBAC_ACCESS_DENIED",
                                            "content", "【RBAC访问拒绝】" + throwable.getMessage()
                                    ));
                                    sink.complete();
                                    return;
                                }
                            }
                            // 其他异常，使用原有处理
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

    /**
     * 安全网关检查：前置拦截高危操作
     * 【第2层】仅拦截绝对禁止的恶意指令（永不放行）
     */
    private SecurityCheckResult performSecurityGatewayCheck(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return new SecurityCheckResult(true, "");
        }

        String lowerPrompt = prompt.toLowerCase();
        
        // 第2层黑名单：绝对禁止的系统级高危指令（永不放行，无需确认）
        String[] absoluteBlacklist = {
            "drop", "truncate", "rm ", "destroy",
            "kill ", "terminate", "unlink", "erase", "wipe", "shutdown"
        };
        
        for (String keyword : absoluteBlacklist) {
            if (lowerPrompt.contains(keyword)) {
                String reason = "【第2层：黑名单拦截】操作包含绝对禁止的指令关键词 [" + keyword.trim() + "]，已被永久阻断";
                log.warn("🛑 [Gateway-Layer2] {}", reason);
                return new SecurityCheckResult(false, reason);
            }
        }

        return new SecurityCheckResult(true, "");
    }

    /**
     * 安全检查结果
     */
    private static class SecurityCheckResult {
        boolean isAllowed;
        String reason;

        SecurityCheckResult(boolean isAllowed, String reason) {
            this.isAllowed = isAllowed;
            this.reason = reason;
        }
    }
    
    /**
     * 【第3层防护】检查是否为破坏性操作（但不在黑名单中）
     * 这些操作需要Token二次确认
     */
    private boolean isDestructiveButNotBlacklisted(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return false;
        }
        
        String lowerPrompt = prompt.toLowerCase();
        
        // 高危黑名单关键词（第2层已拦截）
        String[] blacklistKeywords = {
            "drop", "truncate", "rm ", "destroy",
            "kill ", "terminate", "unlink", "erase", "wipe"
        };
        
        // 先检查是否命中黑名单，如果命中则不属于"需要确认"类别（会被第2层直接拦截）
        for (String keyword : blacklistKeywords) {
            if (lowerPrompt.contains(keyword)) {
                return false; // 是黑名单操作，不走Token确认流程
            }
        }
        
        // 破坏性操作关键词（需要Token确认）
        String[] destructiveKeywords = {
            "delete", "remove "
        };
        
        for (String keyword : destructiveKeywords) {
            if (lowerPrompt.contains(keyword)) {
                return true; // 是破坏性操作但不在黑名单，需要确认
            }
        }
        
        return false;
    }
    
    /**
     * 【第3层防护】生成确认Token并存入Redis
     * 
     * @param userId 用户ID
     * @param prompt 操作提示词
     * @return 生成的Token
     */
    private TokenGenerationResult generateConfirmToken(String userId, String prompt) {
        if (redisTemplate == null) {
            log.warn("⚠️ [Gateway-Layer3] Redis未注入，无法生成Token");
            return null;
        }
        
        try {
            // 1. 生成Token
            String token = "CONFIRM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            
            // 2. 计算prompt的MD5摘要
            String promptHash = DigestUtils.md5DigestAsHex(prompt.getBytes(StandardCharsets.UTF_8));
            
            // 3. 构建Redis Key: agent:confirm:{token} （简化版，token本身已具有唯一性）
            String redisKey = REDIS_CONFIRM_KEY_PREFIX + token;
            
            // 4. 存入Redis，值为prompt的MD5，TTL=3分钟
            redisTemplate.opsForValue().set(redisKey, promptHash, TOKEN_EXPIRY_MINUTES, TimeUnit.MINUTES);
            
            log.info("🔑 [Gateway-Layer3] 确认Token已生成: userId={}, token={}, promptHash={}, ttl={}min", 
                userId, token, promptHash, TOKEN_EXPIRY_MINUTES);
            
            return new TokenGenerationResult(token, promptHash);
            
        } catch (Exception e) {
            log.error("❌ [Gateway-Layer3] 生成Token失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 【第3层防护·Resume 联调】按 Supervisor 的键格式补写执行快照
     *
     * 背景：破坏性操作的挂起发生在网关层（未调用 Supervisor），而核准时
     * Supervisor 侧 TaskExecutionServiceImpl.resumeTask 通过
     * SnapshotRepository.validateAndConsumeToken 校验
     * nexusmind:snapshot:{executionId} 中的 confirmationToken。
     * 两侧状态不互通会导致核准必然返回 TOKEN_INVALID，
     * 故在挂起时按 Supervisor 的键格式与类型补写快照（TTL 与确认 Token 一致）。
     */
    private void saveSupervisorSnapshot(String taskId, TokenGenerationResult tokenResult, String prompt) {
        if (redisTemplate == null) {
            log.warn("⚠️ [Gateway-Layer3] Redis未注入，跳过快照补写");
            return;
        }

        try {
            ExecutionSnapshot snapshot = new ExecutionSnapshot();
            snapshot.setTaskId(taskId);
            // 网关第3层只识别 delete/remove 类关键词，操作类型即文件删除
            snapshot.setOperationType(DestructiveOperationType.FILE_DELETE);
            snapshot.setRiskLevel(RiskLevel.HIGH);
            snapshot.setOperationDescription("【破坏性操作确认】" + prompt);
            // 网关层不做文件级解析，直接透传原始 prompt 作为变更预览
            snapshot.setChangePreview("prompt: " + prompt);
            snapshot.setConfirmationToken(tokenResult.token);
            snapshot.setExpiresAt(Instant.now().plusSeconds(TOKEN_EXPIRY_MINUTES * 60));

            String snapshotKey = SUPERVISOR_SNAPSHOT_KEY_PREFIX + taskId;
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(snapshotKey, json, TOKEN_EXPIRY_MINUTES, TimeUnit.MINUTES);

            log.info("🧾 [Gateway-Layer3] 执行快照已补写: key={}, token={}, ttl={}min",
                    snapshotKey, tokenResult.token, TOKEN_EXPIRY_MINUTES);
        } catch (Exception e) {
            log.error("❌ [Gateway-Layer3] 快照补写失败: taskId={}", taskId, e);
        }
    }

    /**
     * 从 Authorization header 中提取 JWT Token 并解析出 Agent 角色
     * 
     * @param authHeader Authorization header 值（格式: Bearer <token>）
     * @return Agent 角色（如 "supervisor"），如果解析失败返回 null
     */
    private String extractAgentRoleFromJwt(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("🔴 [Gateway] 缺少或无效的 Authorization header");
                return null;
            }
            
            String token = authHeader.substring(7);
            log.debug("🔍 [Gateway] 提取的 Bearer token: {}...", token.substring(0, Math.min(50, token.length())));
            
            String role = jwtTokenProvider.getRoleFromToken(token);
            
            log.info("🔍 [Gateway] JWT getRoleFromToken() 返回: {}", role);
            
            if (role == null || role.trim().isEmpty()) {
                log.warn("🔴 [Gateway] JWT Token 中缺少 role 字段或为空");
                return null;
            }
            
            log.info("✅ [Gateway] 成功从 JWT 中提取 role: '{}' (长度: {})", role, role.length());
            
            // 验证 role 是否为有效的 AgentRole
            AgentRole agentRole = AgentRole.fromId(role);
            if (agentRole == null) {
                log.warn("🔴 [Gateway] 无效的 Agent 角色值: '{}' (不能转换为 AgentRole)", role);
                return null;
            }
            
            log.info("✅ [Gateway] 角色值有效: {} -> AgentRole.{}", role, agentRole.name());
            return role;
        } catch (Exception e) {
            log.error("🔴 [Gateway] 从 JWT 中提取 role 失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 从 Authorization header 中提取 JWT Token 并解析出用户名
     * 
     * @param authHeader Authorization header 值（格式: Bearer <token>）
     * @return 用户名，如果解析失败返回 "USER-ANONYMOUS"
     */
    private String extractUserIdFromJwt(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return "USER-ANONYMOUS";
            }
            
            String token = authHeader.substring(7);
            String username = jwtTokenProvider.getUsernameFromToken(token);
            
            return username != null && !username.trim().isEmpty() ? username : "USER-ANONYMOUS";
        } catch (Exception e) {
            log.debug("[Gateway] 从 JWT 中提取用户名失败: {}", e.getMessage());
            return "USER-ANONYMOUS";
        }
    }
    
    /**
     * @deprecated 已废弃，改为从 Authorization header 中提取 JWT Token
     */
    @Deprecated(since = "1.0.0")
    private String extractUserId(@RequestParam(name = "token", required = false) String authToken) {
        // ⚠️ 演示用临时实现
        // 生产环境应：
        // 1. 从 Authorization header 中获取 JWT Token
        // 2. 验证 JWT 签名
        // 3. 从 Token 的 claims 中提取 userId
        // 4. 验证 userId 的权限范围
        
        if (authToken != null && !authToken.isEmpty()) {
            return "USER-DEV-" + Math.abs(authToken.hashCode() % 10000);
        }
        return "USER-ANONYMOUS";
    }
    
    /**
     * Token生成结果（包含token和promptHash）
     */
    private static class TokenGenerationResult {
        String token;
        String promptHash;
        
        TokenGenerationResult(String token, String promptHash) {
            this.token = token;
            this.promptHash = promptHash;
        }
    }
    
    /**
     * Token验证结果
     */
    private static class TokenValidationResult {
        boolean isValid;
        String message;
        
        TokenValidationResult(boolean isValid, String message) {
            this.isValid = isValid;
            this.message = message;
        }
    }
}
