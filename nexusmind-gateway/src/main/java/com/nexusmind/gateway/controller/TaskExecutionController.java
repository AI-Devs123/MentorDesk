package com.nexusmind.gateway.controller;

import com.nexusmind.task.TaskExecutionService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    @DubboReference
    private TaskExecutionService taskExecutionService;
    
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    
    private static final String REDIS_CONFIRM_KEY_PREFIX = "agent:confirm:";
    private static final long TOKEN_EXPIRY_MINUTES = 3;

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
}
