package com.nexusmind.gateway.controller;

import com.nexusmind.security.rbac.AgentRbacContext;
import com.nexusmind.task.TaskExecutionService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 任务执行控制器 (Gateway REST 入口)
 * 接收前端请求，验证 token，转发到 Supervisor 的 Dubbo 服务
 */
@RestController
@RequestMapping("/api/v1/task")
public class TaskExecutionController {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionController.class);

    // 切片5：Triple 协议强制 fastjson2，规避 Hessian2 反序列化 record DTO 崩溃
    @DubboReference(parameters = {"serialization=fastjson2"})
    private TaskExecutionService taskExecutionService;
    
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private RedissonClient redissonClient;
    
    private static final String REDIS_CONFIRM_KEY_PREFIX = "agent:confirm:";
    private static final long TOKEN_EXPIRY_MINUTES = 3;
    private static final String LOCK_KEY_PREFIX = "nexusmind:lock:task:";

    /**
     * 恢复任务并执行破坏性操作
     * 
     * 请求体:
     * {
     *   "execution_id": "xxx",
     *   "token": "yyy",
     *   "promptHash": "zzz"
     * }
     * 
     * @param payload 包含 execution_id、token 和 promptHash
     * @return 操作结果
     */
    @PostMapping("/resume")
    public ResponseEntity<?> resumeTask(@RequestBody Map<String, String> payload) {
        String executionId = payload.get("execution_id");
        String token = payload.get("token");
        String promptHash = payload.get("promptHash");

        log.info("📡 [Gateway] 收到恢复请求: executionId={}, token={}", executionId, token);

        // 参数校验
        if (executionId == null || executionId.trim().isEmpty() || 
            token == null || token.trim().isEmpty()) {
            Map<String, Object> errorResult = Map.of(
                "status", "ERROR",
                "code", "PARAM_MISSING",
                "message", "缺少 execution_id 或 token"
            );
            return ResponseEntity.badRequest().body(errorResult);
        }

        // 设置 Agent 身份到 RPC 上下文（RBAC 权限检查将使用此身份）
        AgentRbacContext.setCurrentAgent("supervisor");

        try {
            // 【切片 5】分布式锁防连击：同一 execution_id 禁止并发重复提交
            if (redissonClient != null) {
                String lockKey = LOCK_KEY_PREFIX + executionId;
                RLock lock = redissonClient.getLock(lockKey);
                try {
                    // 尝试获取锁，2 秒超时，锁定 5 秒后自动释放
                    boolean acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
                    if (!acquired) {
                        log.warn("⚠️ [Gateway] 检测到重复快速点击，拒绝处理: executionId={}", executionId);
                        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                            "status", "ERROR",
                            "code", "DUPLICATE_REQUEST",
                            "message", "正在处理中，请勿重复快速提交！"
                        ));
                    }

                    try {
                        return resumeTaskInternal(executionId, token, promptHash);
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("❌ [Gateway] 分布式锁获取中断: {}", e.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "status", "ERROR",
                        "code", "LOCK_ERROR",
                        "message", "处理中断"
                    ));
                }
            } else {
                // 降级处理：Redisson 未配置时，直接处理而不进行分布式锁防护
                // 这种情况下无法防止并发重复提交，仅用于开发/测试环境
                log.warn("⚠️ [Gateway] Redisson 未配置，分布式锁防连击功能不可用");
                return resumeTaskInternal(executionId, token, promptHash);
            }
        } finally {
            // 清理 RBAC 上下文
            AgentRbacContext.clearContext();
        }
    }

    /**
     * 恢复任务的核心逻辑（已剥离出来便于分布式锁包装）
     */
    private ResponseEntity<?> resumeTaskInternal(String executionId, String token, String promptHash) {
        // 【关键】在 Gateway 层验证 token
        String validationResult = validateAndConsumeConfirmToken(executionId, token, promptHash);
        if (!validationResult.equals("OK")) {
            Map<String, Object> errorResult = Map.of(
                "status", "ERROR",
                "code", "TOKEN_INVALID",
                "message", validationResult
            );
            return ResponseEntity.badRequest().body(errorResult);
        }

        // 调用 Dubbo 服务
        Map<String, Object> result = taskExecutionService.resumeTask(executionId, token);

        // 根据结果返回相应的 HTTP 状态码
        String status = (String) result.get("status");
        String code = (String) result.get("code");
        
        if ("SUCCESS".equals(status)) {
            // ✅ 成功
            return ResponseEntity.ok(result);
        } else if ("ERROR".equals(status)) {
            // ❌ 错误处理
            if ("PARAM_MISSING".equals(code) || "TOKEN_INVALID".equals(code)) {
                // 参数缺失或令牌无效 → 400
                return ResponseEntity.badRequest().body(result);
            } else {
                // 其他错误 → 500
                return ResponseEntity.status(500).body(result);
            }
        } else {
            // WARNING 或其他状态 → 200
            return ResponseEntity.ok(result);
        }
    }
    
    /**
     * 验证并消费确认令牌
     * 从 Gateway 的 Redis 中查找并删除 token
     */
    private String validateAndConsumeConfirmToken(String executionId, String token, String promptHash) {
        if (redisTemplate == null) {
            log.warn("⚠️ [TaskExecutionController] Redis未注入，无法验证Token");
            return "Redis未就绪";
        }
        
        try {
            // Redis key 格式：agent:confirm:{token}
            String redisKey = REDIS_CONFIRM_KEY_PREFIX + token;
            String storedPromptHash = redisTemplate.opsForValue().get(redisKey);
            
            if (storedPromptHash == null) {
                log.warn("⚠️ [TaskExecutionController] Token不存在或已过期: token={}", token);
                return "令牌无效、已过期或已被消费";
            }
            
            // 如果前端提供了 promptHash，验证是否匹配
            if (promptHash != null && !promptHash.isEmpty() && !storedPromptHash.equals(promptHash)) {
                log.warn("⚠️ [TaskExecutionController] PromptHash不匹配: token={}, stored={}, current={}", 
                    token, storedPromptHash, promptHash);
                return "操作内容与确认令牌不匹配";
            }
            
            // 验证通过，删除 token（一次性消费）
            Boolean deleted = redisTemplate.delete(redisKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("✅ [TaskExecutionController] Token验证通过并已消费: token={}", token);
                return "OK";
            } else {
                log.warn("⚠️ [TaskExecutionController] Token删除失败（可能已被消费）: token={}", token);
                return "令牌可能已被使用";
            }
            
        } catch (Exception e) {
            log.error("❌ [TaskExecutionController] Token验证异常: {}", e.getMessage(), e);
            return "Token验证异常: " + e.getMessage();
        }
    }

    /**
     * 中止/拒绝任务执行
     * 
     * 请求体:
     * {
     *   "execution_id": "xxx"
     * }
     * 
     * 功能：
     * 1. 调用 Supervisor 的 Dubbo 服务销毁 Redis 快照
     * 2. 标记任务被取消，阻断后续 DAG 流转
     * 3. 返回熔断响应，确保沙箱文件完好无损
     * 
     * @param payload 包含 execution_id
     * @return 操作结果
     */
    @PostMapping("/abort")
    public ResponseEntity<?> abortTask(@RequestBody Map<String, String> payload) {
        String executionId = payload.get("execution_id");

        log.warn("🛑 [Gateway] 收到任务中止拒绝请求: executionId={}", executionId);

        // 参数校验
        if (executionId == null || executionId.trim().isEmpty()) {
            Map<String, Object> errorResult = Map.of(
                "status", "ERROR",
                "code", "PARAM_MISSING",
                "message", "缺少 execution_id"
            );
            log.error("❌ [Gateway] 参数校验失败: {}", errorResult);
            return ResponseEntity.badRequest().body(errorResult);
        }

        // 设置 Agent 身份到 RPC 上下文（RBAC 权限检查将使用此身份）
        AgentRbacContext.setCurrentAgent("supervisor");

        try {
            // 【切片 5】分布式锁防连击：防止前端快速连续点击"拒绝"按钮导致重复中止
            if (redissonClient != null) {
                String lockKey = LOCK_KEY_PREFIX + "abort:" + executionId;
                RLock lock = redissonClient.getLock(lockKey);
                try {
                    boolean acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
                    if (!acquired) {
                        log.warn("⚠️ [Gateway] 检测到重复快速点击拒绝，拒绝处理: executionId={}", executionId);
                        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                            "status", "ERROR",
                            "code", "DUPLICATE_ABORT",
                            "message", "正在处理拒绝请求，请勿重复点击！"
                        ));
                    }

                    try {
                        return abortTaskInternal(executionId);
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("❌ [Gateway] 分布式锁获取中断: {}", e.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "status", "ERROR",
                        "code", "LOCK_ERROR",
                        "message", "处理中断"
                    ));
                }
            } else {
                return abortTaskInternal(executionId);
            }
        } finally {
            // 清理 RBAC 上下文
            AgentRbacContext.clearContext();
        }
    }

    /**
     * 中止任务的核心逻辑（已剥离出来便于分布式锁包装）
     */
    private ResponseEntity<?> abortTaskInternal(String executionId) {
        try {
            // 调用 Dubbo 服务
            Map<String, Object> result = taskExecutionService.abortTask(executionId);

            // 根据结果返回相应的 HTTP 状态码
            String status = (String) result.get("status");

            if ("ABORTED".equals(status)) {
                // ✅ 成功中止
                log.info("✅ [Gateway] 任务已成功中止: executionId={}", executionId);
                return ResponseEntity.ok(result);
            } else if ("ERROR".equals(status)) {
                // ❌ 错误处理
                String code = (String) result.get("code");
                if ("PARAM_MISSING".equals(code)) {
                    // 参数缺失 → 400
                    return ResponseEntity.badRequest().body(result);
                } else {
                    // 其他错误 → 500
                    return ResponseEntity.status(500).body(result);
                }
            } else {
                // 其他状态 → 200
                return ResponseEntity.ok(result);
            }

        } catch (Exception e) {
            log.error("❌ [Gateway] 处理中止请求异常: {}", e.getMessage(), e);
            Map<String, Object> errorResult = Map.of(
                "status", "ERROR",
                "code", "GATEWAY_ERROR",
                "message", "网关处理异常: " + e.getMessage()
            );
            return ResponseEntity.status(500).body(errorResult);
        }
    }
}
