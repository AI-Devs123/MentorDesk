package com.nexusmind.supervisor.evolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.api.quality.JudgeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 进化扫描态仓储（切片 1）+ 关联点独立索引（切片 2）。
 *
 * <p>镜像 {@code com.nexusmind.supervisor.execution.SnapshotRepository} 的实现风格：
 * 用 {@link StringRedisTemplate} + Spring 托管的 {@link ObjectMapper} 持久化 JSON，
 * 全程 null 安全（Redis 未注入或序列化失败仅告警，绝不影响主编排链路）。
 *
 * <p>切片 2 新增两类 key：
 * <ul>
 *   <li>{@code nexusmind:evolution:<taskId>} —— 扫描态（含关联点），TTL 1 天；</li>
 *   <li>{@code nexusmind:knowledge-link:<newDocId>} —— 以新资产 docId 为索引的关联点，
 *       供「下一任务上下文预加载」反查（TTL 7 天）。</li>
 * </ul>
 */
@Repository
public class EvolutionScanRepository {

    private static final Logger log = LoggerFactory.getLogger(EvolutionScanRepository.class);
    private static final String KEY_PREFIX = "nexusmind:evolution:";
    private static final String LINK_PREFIX = "nexusmind:knowledge-link:";
    private static final String QUALITY_PREFIX = "nexusmind:quality:";
    private static final long TTL_SECONDS = 86_400L;       // 进化扫描态保留 1 天
    private static final long LINK_TTL_SECONDS = 604_800L;  // 关联点保留 7 天
    private static final long QUALITY_TTL_SECONDS = 604_800L; // 质量分保留 7 天

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    /** 单参构造：Spring 自动注入托管 ObjectMapper（已注册 JavaTimeModule）。 */
    public EvolutionScanRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── 扫描态（含关联点） ──

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

    // ── 关联点独立索引（切片 2 回灌预加载） ──

    /**
     * 以新资产 docId 为索引落盘关联点，供下次同主题任务反查。
     * newDocId 为 null（journal/tutor/code 任务常无稳定 docId）时静默跳过。
     */
    public void saveLinks(String newDocId, List<KnowledgeAssociation> links) {
        if (redisTemplate == null || newDocId == null || links == null || links.isEmpty()) {
            return;
        }
        try {
            String key = LINK_PREFIX + newDocId;
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(links),
                    LINK_TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("✅ 知识关联点已索引: key={}, size={}", key, links.size());
        } catch (Exception e) {
            log.warn("❌ 知识关联点索引失败(非阻断): newDocId={}, error={}", newDocId, e.getMessage());
        }
    }

    /**
     * 反查某新资产发现的关联点（下一任务预加载用）。无 Redis 或无记录返回空列表。
     */
    public List<KnowledgeAssociation> loadLinksByDocId(String newDocId) {
        if (redisTemplate == null || newDocId == null) {
            return Collections.emptyList();
        }
        try {
            String json = redisTemplate.opsForValue().get(LINK_PREFIX + newDocId);
            if (json == null) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(json, new TypeReference<List<KnowledgeAssociation>>() {});
        } catch (Exception e) {
            log.warn("❌ 知识关联点读取失败(非阻断): newDocId={}, error={}", newDocId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── 切片 4：质量评分（独立 key，与扫描态解耦，异步落库零回归） ──

    /**
     * 持久化一次质量评测结果（切片 4）。使用独立 key，不触碰 EvolutionScanState 既有契约。
     * taskId / result 为空或 Redis 未注入时静默跳过。
     */
    public void saveQuality(String taskId, JudgeResult result) {
        if (redisTemplate == null || taskId == null || result == null) {
            return;
        }
        try {
            String key = QUALITY_PREFIX + taskId;
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result),
                    QUALITY_TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("✅ 质量分已持久化: key={}, score={}", key, result.score());
        } catch (Exception e) {
            log.warn("❌ 质量分持久化失败(非阻断): task={}, error={}", taskId, e.getMessage());
        }
    }

    /**
     * 读取某任务的质量评测结果（切片 4）。无 Redis / 无记录返回 null。
     */
    public JudgeResult loadQuality(String taskId) {
        if (redisTemplate == null || taskId == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(QUALITY_PREFIX + taskId);
            return json == null ? null : objectMapper.readValue(json, JudgeResult.class);
        } catch (Exception e) {
            log.warn("❌ 质量分读取失败(非阻断): task={}, error={}", taskId, e.getMessage());
            return null;
        }
    }
}
