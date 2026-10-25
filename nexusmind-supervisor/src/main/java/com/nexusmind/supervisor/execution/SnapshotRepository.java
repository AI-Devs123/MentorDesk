package com.nexusmind.supervisor.execution;

import com.nexusmind.security.destructive.ExecutionSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

/**
 * 执行快照存储库
 * 负责将 ExecutionSnapshot 持久化到 Redis
 */
@Repository
public class SnapshotRepository {
    private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);
    
    private static final String SNAPSHOT_KEY_PREFIX = "nexusmind:snapshot:";
    private static final long DEFAULT_TTL_SECONDS = 3600; // 1 小时
    
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    // 使用 Spring 管理的 ObjectMapper（已注册 JavaTimeModule）。
    // 裸 new ObjectMapper() 无法序列化/反序列化 ExecutionSnapshot 的 Instant 字段，
    // 会导致 validateAndConsumeToken 的 readValue 抛异常、令牌校验假失败。
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 保存快照到 Redis
     * 
     * @param executionId 执行ID
     * @param snapshot 快照对象
     * @return 是否保存成功
     */
    public boolean saveSnapshot(String executionId, ExecutionSnapshot snapshot) {
        if (redisTemplate == null) {
            log.warn("⚠️ Redis未注入，无法保存快照: executionId={}", executionId);
            return false;
        }
        
        try {
            String key = SNAPSHOT_KEY_PREFIX + executionId;
            String json = objectMapper.writeValueAsString(snapshot);
            
            redisTemplate.opsForValue().set(key, json, DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
            
            log.info("✅ 快照已保存到 Redis: key={}, ttl={}s", key, DEFAULT_TTL_SECONDS);
            return true;
            
        } catch (Exception e) {
            log.error("❌ 保存快照失败: executionId={}, error={}", executionId, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 从 Redis 加载快照
     * 
     * @param executionId 执行ID
     * @return 快照对象，不存在则返回 null
     */
    public ExecutionSnapshot loadSnapshot(String executionId) {
        if (redisTemplate == null) {
            log.warn("⚠️ Redis未注入，无法加载快照: executionId={}", executionId);
            return null;
        }
        
        try {
            String key = SNAPSHOT_KEY_PREFIX + executionId;
            String json = redisTemplate.opsForValue().get(key);
            
            if (json == null) {
                log.warn("⚠️ 快照不存在或已过期: key={}", key);
                return null;
            }
            
            ExecutionSnapshot snapshot = objectMapper.readValue(json, ExecutionSnapshot.class);
            log.info("✅ 快照已加载: key={}", key);
            return snapshot;
            
        } catch (Exception e) {
            log.error("❌ 加载快照失败: executionId={}, error={}", executionId, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 删除快照
     * 
     * @param executionId 执行ID
     * @return 是否删除成功
     */
    public boolean deleteSnapshot(String executionId) {
        if (redisTemplate == null) {
            log.warn("⚠️ Redis未注入，无法删除快照: executionId={}", executionId);
            return false;
        }
        
        try {
            String key = SNAPSHOT_KEY_PREFIX + executionId;
            Boolean deleted = redisTemplate.delete(key);
            
            if (Boolean.TRUE.equals(deleted)) {
                log.info("✅ 快照已删除: key={}", key);
                return true;
            } else {
                log.warn("⚠️ 快照不存在: key={}", key);
                return false;
            }
            
        } catch (Exception e) {
            log.error("❌ 删除快照失败: executionId={}, error={}", executionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 丢弃/清除快照（用户拒绝或回滚时调用）
     * 确保快照被彻底清理，防止残留
     * 
     * @param executionId 执行ID
     * @return 是否清理成功
     */
    public boolean discardSnapshot(String executionId) {
        return deleteSnapshot(executionId);
    }

    /**
     * 校验令牌并消费快照（单次防重放）
     * 
     * @param executionId 执行ID
     * @param token 确认令牌
     * @return 校验是否通过
     */
    public boolean validateAndConsumeToken(String executionId, String token) {
        if (redisTemplate == null) {
            log.warn("⚠️ Redis未注入，无法校验令牌: executionId={}", executionId);
            return false;
        }
        
        try {
            String key = SNAPSHOT_KEY_PREFIX + executionId;
            String json = redisTemplate.opsForValue().get(key);
            
            if (json == null) {
                log.warn("⚠️ 快照不存在或已过期，校验失败: key={}", key);
                return false;
            }
            
            ExecutionSnapshot snapshot = objectMapper.readValue(json, ExecutionSnapshot.class);
            
            // 校验令牌是否一致
            if (token != null && token.equals(snapshot.getConfirmationToken())) {
                // 校验成功立即删除快照，防止二次重放破坏
                redisTemplate.delete(key);
                log.info("✅ 令牌校验成功，快照已消费: executionId={}", executionId);
                return true;
            } else {
                log.warn("❌ 令牌不匹配或无效: executionId={}", executionId);
                return false;
            }
            
        } catch (Exception e) {
            log.error("❌ 校验令牌失败: executionId={}, error={}", executionId, e.getMessage(), e);
            return false;
        }
    }
}
