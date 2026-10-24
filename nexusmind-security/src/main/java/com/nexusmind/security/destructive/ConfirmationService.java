package com.nexusmind.security.destructive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 破坏性操作确认服务
 * 负责查询快照、验证Token、执行确认操作
 */
@Service
public class ConfirmationService {
    
    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);
    private static final String SNAPSHOT_KEY_PREFIX = "nexusmind:snapshot:";
    private static final String CONFIRMATION_TOKEN_PREFIX = "nexusmind:token:";
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 根据Token查询待确认的操作快照
     */
    public Map<String, Object> querySnapshotByToken(String token) {
        try {
            String tokenKey = CONFIRMATION_TOKEN_PREFIX + token;
            String executionId = redisTemplate.opsForValue().get(tokenKey);
            
            if (executionId == null) {
                log.warn("⚠️ [ConfirmationService] Token不存在或已过期: {}", token);
                return null;
            }
            
            String snapshotKey = SNAPSHOT_KEY_PREFIX + executionId;
            String snapshotJson = redisTemplate.opsForValue().get(snapshotKey);
            
            if (snapshotJson == null) {
                log.warn("⚠️ [ConfirmationService] 快照不存在: key={}", snapshotKey);
                return null;
            }
            
            Map<String, Object> snapshot = objectMapper.readValue(snapshotJson, Map.class);
            log.info("✓ [ConfirmationService] 快照查询成功: executionId={}", executionId);
            return snapshot;
            
        } catch (Exception e) {
            log.error("❌ [ConfirmationService] 查询快照失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 根据executionId查询快照（后端调试用）
     */
    public Map<String, Object> querySnapshotById(String executionId) {
        try {
            String snapshotKey = SNAPSHOT_KEY_PREFIX + executionId;
            String snapshotJson = redisTemplate.opsForValue().get(snapshotKey);
            
            if (snapshotJson == null) {
                log.warn("⚠️ [ConfirmationService] 快照不存在: key={}", snapshotKey);
                return null;
            }
            
            Map<String, Object> snapshot = objectMapper.readValue(snapshotJson, Map.class);
            return snapshot;
            
        } catch (Exception e) {
            log.error("❌ [ConfirmationService] 查询快照失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 确认操作：用户提交Token后，生成有效的操作Token供后续执行
     */
    public Map<String, Object> confirmOperation(String token, String userDecision) {
        try {
            Map<String, Object> snapshot = querySnapshotByToken(token);
            if (snapshot == null) {
                log.warn("⚠️ [ConfirmationService] Token无效或过期: {}", token);
                Map<String, Object> errorResp = new HashMap<>();
                errorResp.put("success", false);
                errorResp.put("message", "Token无效或已过期");
                return errorResp;
            }
            
            String executionId = (String) snapshot.get("executionId");
            
            if ("APPROVED".equalsIgnoreCase(userDecision)) {
                log.info("✅ [ConfirmationService] 用户批准操作: executionId={}", executionId);
                
                // 生成执行Token，允许后续请求带着此Token执行
                String executionToken = "exec-" + System.currentTimeMillis();
                String executionTokenKey = "nexusmind:exec-token:" + executionToken;
                redisTemplate.opsForValue().set(executionTokenKey, executionId, 3600, TimeUnit.SECONDS);
                
                // 更新快照状态
                snapshot.put("status", "APPROVED");
                snapshot.put("approvedAt", System.currentTimeMillis());
                String snapshotKey = SNAPSHOT_KEY_PREFIX + executionId;
                redisTemplate.opsForValue().set(snapshotKey, objectMapper.writeValueAsString(snapshot), 3600, TimeUnit.SECONDS);
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "操作已批准");
                response.put("executionToken", executionToken);
                response.put("executionId", executionId);
                return response;
            } else {
                log.info("❌ [ConfirmationService] 用户拒绝操作: executionId={}", executionId);
                
                // 更新快照状态为拒绝
                snapshot.put("status", "REJECTED");
                snapshot.put("rejectedAt", System.currentTimeMillis());
                String snapshotKey = SNAPSHOT_KEY_PREFIX + executionId;
                redisTemplate.opsForValue().set(snapshotKey, objectMapper.writeValueAsString(snapshot), 3600, TimeUnit.SECONDS);
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "操作已拒绝");
                response.put("executionId", executionId);
                return response;
            }
            
        } catch (Exception e) {
            log.error("❌ [ConfirmationService] 确认操作失败: {}", e.getMessage());
            Map<String, Object> errorResp = new HashMap<>();
            errorResp.put("success", false);
            errorResp.put("message", "确认失败: " + e.getMessage());
            return errorResp;
        }
    }
    
    /**
     * 获取所有待确认的操作快照（后端调试用）
     */
    public Map<String, Object> getAllPendingSnapshots() {
        try {
            Set<String> keys = redisTemplate.keys(SNAPSHOT_KEY_PREFIX + "*");
            Map<String, Object> allSnapshots = new HashMap<>();
            
            if (keys != null) {
                for (String key : keys) {
                    String snapshotJson = redisTemplate.opsForValue().get(key);
                    if (snapshotJson != null) {
                        Map<String, Object> snapshot = objectMapper.readValue(snapshotJson, Map.class);
                        allSnapshots.put(key, snapshot);
                    }
                }
            }
            
            log.info("✓ [ConfirmationService] 查询所有快照成功，共{}条", allSnapshots.size());
            return allSnapshots;
            
        } catch (Exception e) {
            log.error("❌ [ConfirmationService] 查询所有快照失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
