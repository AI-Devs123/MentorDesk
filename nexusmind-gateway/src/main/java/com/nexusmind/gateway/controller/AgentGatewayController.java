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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
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
    @DubboReference
    private AgentStreamingFacade agentStreamingFacade;
    
    // 注入 Redis Template，用于第3层Token确认机制
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    
    // Redis Key前缀：agent:confirm:{userId}:{token}
    private static final String REDIS_CONFIRM_KEY_PREFIX = "agent:confirm:";
    
    // Token有效期（3分钟）
    private static final long TOKEN_EXPIRY_MINUTES = 3;

    @GetMapping(value = "/api/v1/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> streamAgent(
            @RequestParam(name = "prompt", defaultValue = "默认任务") String prompt,
            @RequestParam(name = "agent", defaultValue = "Supervisor") String agent,
            @RequestParam(name = "agentRole", defaultValue = "supervisor") String agentRole,
            @RequestParam(name = "token", required = false) String authToken,              // 身份鉴权Token
            @RequestParam(name = "confirmToken", required = false) String confirmToken) {  // 高危操作确认Token

        return Flux.<Map<String, Object>>create(sink -> {
            String taskId = "TASK-" + System.currentTimeMillis();
            
            // 1. 提取用户ID（待第1层完成后从JWT中提取）
            String userId = extractUserId(authToken);
            
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

            // 设置 Agent 身份到 RPC 上下文（RBAC 权限检查将使用此身份）
            AgentRbacContext.setCurrentAgent(agentRole);

            log.info(">>> [Gateway] 收到前端 SSE 握手请求, taskId: {}, prompt: {}, agent: {}, agentRole: {}, authToken: {}, confirmToken: {}",
                    taskId, prompt, agent, agentRole, 
                    authToken != null ? "****" : "null",
                    confirmToken != null ? "****" : "null");
            log.info("📡 [Gateway] 下游 Dubbo Triple 开始调用 Supervisor 流式服务");

            AgentStreamRequest request = AgentStreamRequest.newBuilder()
                    .setTaskId(taskId)
                    .setAgentName(agent)
                    .setPayload(prompt)
                    .setConfirmationToken(confirmToken != null ? confirmToken : "")
                    .build();

            try {
                // authToken已通过query参数传递，Dubbo Filter会从Invocation中提取
                // confirmToken通过AgentStreamRequest传递到后端
                if (authToken != null && !authToken.isEmpty()) {
                    log.info("🔑 [Gateway] 身份Token参数已接收");
                }
                if (confirmToken != null && !confirmToken.isEmpty()) {
                    log.info("🎫 [Gateway] 确认Token参数已接收");
                }
                
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
     * 【第3层防护】验证Token并一次性消费
     * 
     * @param userId 用户ID
     * @param token 确认Token
     * @param prompt 操作提示词
     * @return 验证结果
     */
    private TokenValidationResult validateAndConsumeToken(String userId, String token, String prompt) {
        if (redisTemplate == null) {
            log.warn("⚠️ [Gateway-Layer3] Redis未注入，无法验证Token");
            return new TokenValidationResult(false, "Redis未就绪，无法验证Token");
        }
        
        if (token == null || token.isEmpty()) {
            return new TokenValidationResult(false, "confirmToken参数为空");
        }
        
        try {
            // 1. 构建Redis Key
            String redisKey = REDIS_CONFIRM_KEY_PREFIX + userId + ":" + token;
            
            // 2. 从Redis获取存储的promptHash
            String storedPromptHash = redisTemplate.opsForValue().get(redisKey);
            
            if (storedPromptHash == null) {
                log.warn("⚠️ [Gateway-Layer3] Token不存在或已过期: userId={}, token={}", userId, token);
                return new TokenValidationResult(false, 
                    "confirmToken [" + token + "] 不存在、已过期或已被使用（有效期" + TOKEN_EXPIRY_MINUTES + "分钟）");
            }
            
            // 3. 计算当前prompt的MD5并校验
            String currentPromptHash = DigestUtils.md5DigestAsHex(prompt.getBytes(StandardCharsets.UTF_8));
            
            if (!storedPromptHash.equals(currentPromptHash)) {
                log.warn("⚠️ [Gateway-Layer3] Prompt不匹配: userId={}, token={}, stored={}, current={}", 
                    userId, token, storedPromptHash, currentPromptHash);
                return new TokenValidationResult(false, 
                    "confirmToken与当前操作内容不匹配（Token是针对其他操作签发的）");
            }
            
            // 4. 验证通过，原子删除Token（一次性消费，防止重放攻击）
            Boolean deleted = redisTemplate.delete(redisKey);
            
            if (Boolean.TRUE.equals(deleted)) {
                log.info("✅ [Gateway-Layer3] Token验证通过并已消费: userId={}, token={}", userId, token);
                return new TokenValidationResult(true, "Token验证通过");
            } else {
                log.warn("⚠️ [Gateway-Layer3] Token删除失败（可能已被消费）: userId={}, token={}", userId, token);
                return new TokenValidationResult(false, "Token可能已被使用（一次性消费机制）");
            }
            
        } catch (Exception e) {
            log.error("❌ [Gateway-Layer3] Token验证异常: {}", e.getMessage(), e);
            return new TokenValidationResult(false, "Token验证异常: " + e.getMessage());
        }
    }
    
    /**
     * 提取用户ID
     * TODO: 第 1 层身份认证（JWT）完成后，升级为从 Token 中提取真实 userId
     * 当前版本：临时实现（演示用），仅为测试环境使用
     * 计划升级版本：2.0.0
     * 
     * @deprecated 该临时实现不应在生产环境中使用
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
