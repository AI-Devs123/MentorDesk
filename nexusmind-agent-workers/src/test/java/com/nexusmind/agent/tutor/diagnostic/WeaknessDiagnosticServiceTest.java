package com.nexusmind.agent.tutor.diagnostic;

import com.nexusmind.agent.tutor.diagnostic.model.ConfusionAnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WeaknessDiagnosticService 单元测试
 *
 * 重点覆盖切片3 评审修复点:
 * - 子串误报回归 (mono->monolithic, flux->influx)
 * - 中文关键词匹配 (\b 对汉字无效的分策略)
 * - hitFrequency 真实频次语义
 * - 双侧命中触发规则
 * - 默认域名一致性
 */
class WeaknessDiagnosticServiceTest {

    private final WeaknessDiagnosticService service = new WeaknessDiagnosticService();

    @Test
    @DisplayName("双侧同时命中：Virtual Thread + WebFlux 触发 Concurrency 混淆对")
    void shouldMatchWhenBothSidesPresent() {
        ConfusionAnalysisResult result = service.analyzeText(
                "排查 virtual thread 取消信号时对比了 webflux 的 doOnCancel 机制");

        assertEquals(1, result.matchedPairs().size());
        var pair = result.matchedPairs().get(0);
        assertEquals("Concurrency", pair.domain());
        assertEquals("Virtual Thread", pair.conceptA());
        // hitsA=1 (virtual thread) + hitsB=1 (webflux) = 2，真实频次
        assertEquals(2, pair.hitFrequency());
        assertEquals("Concurrency", result.topDomain());
        assertTrue(result.confusionConfidence() >= 0.4);
    }

    @Test
    @DisplayName("子串误报回归：monolithic 不得命中 mono 关键词")
    void shouldNotMatchMonolithicAsMono() {
        // A 侧 virtual thread 在场，B 侧仅 monolithic(含 mono 子串) + reactor
        // 正确实现: hitsB=1 (reactor)，monolithic 不匹配 -> pairHits=2
        // 退化的 contains 实现: hitsB=2 -> pairHits=3
        ConfusionAnalysisResult result = service.analyzeText(
                "在 monolithic 架构里用 reactor 模式处理 virtual thread 调度");

        assertEquals(1, result.matchedPairs().size());
        assertEquals(2, result.matchedPairs().get(0).hitFrequency(),
                "monolithic 中的 mono 子串不得计入命中");
    }

    @Test
    @DisplayName("子串误报回归：influxdb 不得命中 flux 关键词")
    void shouldNotMatchInfluxdbAsFlux() {
        // A 侧 virtual thread 在场，B 侧仅 influxdb(含 flux 子串)，无其他 B 侧词
        // 正确实现: hitsB=0 -> 双侧规则不触发
        ConfusionAnalysisResult result = service.analyzeText(
                "virtual thread 写 influxdb 的压测数据");

        assertTrue(result.matchedPairs().isEmpty(),
                "influxdb 中的 flux 子串不得触发 WebFlux 混淆对");
        assertEquals(0.0, result.confusionConfidence());
    }

    @Test
    @DisplayName("中文关键词匹配：虚拟线程 + 响应式 正常触发")
    void shouldMatchChineseKeywords() {
        ConfusionAnalysisResult result = service.analyzeText(
                "今天同时试了虚拟线程和响应式 webflux 两种方案，都很困惑");

        assertEquals(1, result.matchedPairs().size());
        // hitsA=1 (虚拟线程) + hitsB=2 (响应式, webflux) = 3
        assertEquals(3, result.matchedPairs().get(0).hitFrequency());
    }

    @Test
    @DisplayName("真实频次语义：同一关键词多次出现应累计计数")
    void shouldCountRepeatedOccurrences() {
        ConfusionAnalysisResult result = service.analyzeText(
                "webflux webflux webflux 都和 virtual thread 做了对比");

        // hitsA=1, hitsB=3 -> 4
        assertEquals(4, result.matchedPairs().get(0).hitFrequency(),
                "同一关键词出现多次应累计，而非每种只计一次");
    }

    @Test
    @DisplayName("单侧高频不触发：仅出现 WebFlux 无对比另一方")
    void shouldNotTriggerOnSingleSide() {
        ConfusionAnalysisResult result = service.analyzeText(
                "今天全在调 webflux 的背压问题，试了 reactor 的各种操作符");

        assertTrue(result.matchedPairs().isEmpty(),
                "辨析题需要对比双方在场，单侧高频不构成混淆对");
        assertEquals(WeaknessDiagnosticService.DEFAULT_DOMAIN, result.topDomain());
    }

    @Test
    @DisplayName("空输入与 null 输入：返回空匹配 + 统一默认域 + 0.0 置信度")
    void shouldHandleEmptyAndNullInput() {
        for (String input : new String[]{null, "", "   "}) {
            ConfusionAnalysisResult result = service.analyzeText(input);
            assertTrue(result.matchedPairs().isEmpty());
            assertEquals("General Architecture", result.topDomain());
            assertEquals(0.0, result.confusionConfidence());
        }
    }

    @Test
    @DisplayName("多对命中时 topDomain 取命中强度最高域")
    void shouldPickTopDomainByHitStrength() {
        // Concurrency: virtual thread + webflux + reactor (4 hits)
        // Storage: append-only + 覆盖写 (2 hits)
        ConfusionAnalysisResult result = service.analyzeText(
                "virtual thread 调度 webflux reactor 流，同时思考 append-only 与覆盖写的取舍");

        assertEquals(2, result.matchedPairs().size());
        assertEquals("Concurrency", result.topDomain());
    }
}
