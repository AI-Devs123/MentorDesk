package com.nexusmind.supervisor.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

/**
 * 进化扫描态仓储（切片 1）。
 *
 * <p>镜像 {@code com.nexusmind.supervisor.execution.SnapshotRepository} 的实现风格：
 * 用 {@link StringRedisTemplate} + Spring 托管的 {@link ObjectMapper} 持久化 JSON，
 * 全程 null 安全（Redis 未注入或序列化失败仅告警，绝不影响主编排链路）。
 */
@Repository
public class EvolutionScanRepository {

    private static final Logger log = LoggerFactory.getLogger(EvolutionScanRepository.class);
    private static final String KEY_PREFIX = "nexusmind:evolution:";
    private static final long TTL_SECONDS = 86_400L; // 进化扫描态保留 1 天

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    /** 单参构造：Spring 自动注入托管 ObjectMapper（已注册 JavaTimeModule）。 */
    public EvolutionScanRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void save(EvolutionScanState state) {
        if (redisTemplate == null) {
            log.debug("⚠️ Redis 未注入，跳过进化扫描态持久化: task={}", state.taskId());
            return;
        }
        try {
            String key = KEY_PREFIX + state.taskId();
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(state),
                    TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("✅ 进化扫描态已持久化: key={}", key);
        } catch (Exception e) {
            log.warn("❌ 进化扫描态持久化失败(非阻断): task={}, error={}", state.taskId(), e.getMessage());
        }
    }

    public EvolutionScanState load(String taskId) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + taskId);
            return json == null ? null : objectMapper.readValue(json, EvolutionScanState.class);
        } catch (Exception e) {
            log.warn("❌ 进化扫描态读取失败(非阻断): task={}, error={}", taskId, e.getMessage());
            return null;
        }
    }
}
