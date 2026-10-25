package com.nexusmind.supervisor.persona;

import com.nexusmind.api.dto.JournalEntryDTO;
import com.nexusmind.api.dto.PersonaProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 画像增量演变异步服务 (supervisor 层)
 *
 * 在 DAG 完成日记三要素抽取后，于后台异步演化用户的技能盲区画像，
 * 不阻塞前台 SSE 流向 TutorAgent 推进。
 *
 * 切片4 评审修复项:
 * - 并发竞态: read-modify-write 序列改为 ConcurrentHashMap.compute 原子化
 *   (旧版 getOrCreate -> 改 map -> put 序列在并发 evolve 同一 profileId 时丢更新)
 * - 语义修正: totalReflectiveDays 实为"复盘次数"(同日多次复盘会重复计数)，
 *   按次数语义使用并注释标明；真实"天数"需持久化后按日期去重
 * - 评分模型 MVP 限制: 盲区评分当前为只增模型 (遇阻碍 +0.1)，衰减规则
 *   (同域突破 -0.05 / 时间窗口衰减) 需画像持久化与历史窗口后实现，
 *   此处显式标注而非假装完整
 *
 * 存储说明 (MVP 限制): 内存 ConcurrentHashMap，supervisor 重启后画像归零。
 * 生产环境应对接 Redis/PostgreSQL (supervisor 已具备 data-redis 依赖，
 * Week3 SnapshotRepository 即 Redis 范式)——画像"自进化"的跨重启连续性
 * 依赖该项落地，单独立项。
 */
@Service
public class PersonaProfileService {

    private static final Logger log = LoggerFactory.getLogger(PersonaProfileService.class);

    /** 单领域盲区评分上限 */
    private static final double BLIND_SPOT_CAP = 1.0;

    /** 遭遇阻碍时的盲区增量 */
    private static final double BLIND_SPOT_INCREMENT = 0.1;

    /** 初始盲区基线 (从未记录过的领域) */
    private static final double BLIND_SPOT_BASELINE = 0.3;

    /** 后台演化线程池 (平台线程: JDK 17 编译基线，虚拟线程待 JDK 21 升级后替换) */
    private final java.util.concurrent.ExecutorService evolutionExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "persona-evolution");
                t.setDaemon(true);
                return t;
            });

    // 内存存储画像快照（生产环境可对接 Redis/PostgreSQL）
    private final Map<String, PersonaProfile> profileRepository = new ConcurrentHashMap<>();

    public PersonaProfile getOrCreateProfile(String profileId) {
        return profileRepository.computeIfAbsent(profileId, PersonaProfile::newProfile);
    }

    /**
     * 基于今日阻碍与技术域，异步增量演变认知画像与盲区评分
     *
     * 后台单线程串行执行，compute 原子化保证同一 profileId 的演变不丢更新。
     */
    public void evolvePersonaProfileAsync(String profileId, JournalEntryDTO entry) {
        evolutionExecutor.execute(() -> {
            try {
                PersonaProfile updated = profileRepository.compute(profileId,
                        (id, current) -> evolve(current != null ? current : PersonaProfile.newProfile(id), entry));

                log.info("[PersonaProfile] 用户 [{}] 认知画像更新完成，累计复盘次数: {}, 盲区域统计数: {}",
                        profileId, updated.totalReflectiveDays(), updated.blindSpots().size());

            } catch (Exception e) {
                log.error("[PersonaProfile] 画像异步增量演变失败，profileId: {}", profileId, e);
            }
        });
    }

    /**
     * 纯函数式演变: 基于当前画像与今日条目产出新画像 (不可变)
     */
    PersonaProfile evolve(PersonaProfile current, JournalEntryDTO entry) {
        Map<String, Double> updatedBlindSpots = new HashMap<>(current.blindSpots());

        for (var blocker : entry.extraction().blockers()) {
            String domain = blocker.technicalDomain();
            // 遭遇技术阻碍，盲区度微调上升（上限截断在 1.0）
            double currentScore = updatedBlindSpots.getOrDefault(domain, BLIND_SPOT_BASELINE);
            double nextScore = Math.min(BLIND_SPOT_CAP, currentScore + BLIND_SPOT_INCREMENT);
            updatedBlindSpots.put(domain, Math.round(nextScore * 100.0) / 100.0);
        }

        // 注意: totalReflectiveDays 字段在此按"复盘次数"语义递增 (切片4 评审修正)
        return new PersonaProfile(
                current.profileId(),
                System.currentTimeMillis(),
                current.biases(),
                updatedBlindSpots,
                current.attentionTags(),
                current.totalReflectiveDays() + 1
        );
    }

    /**
     * 同步演变入口 (供测试与 DAG 内联调用，避免异步不确定性)
     */
    public PersonaProfile evolvePersonaProfile(String profileId, JournalEntryDTO entry) {
        return profileRepository.compute(profileId,
                (id, current) -> evolve(current != null ? current : PersonaProfile.newProfile(id), entry));
    }
}
