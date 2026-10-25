package com.nexusmind.agent.tutor.diagnostic.model;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 混淆概念诊断结果载体 (TutorAgent 只读诊断链路)
 *
 * 由 WeaknessDiagnosticService 在 LLM 推理前对日记文本做微秒级扫描产出，
 * 用于驱动苏格拉底辨析题直击薄弱点。不可变设计，对齐切片1 DTO 风格。
 */
public record ConfusionAnalysisResult(
        List<MatchedConfusionPair> matchedPairs,
        String topDomain,
        double confusionConfidence
) implements Serializable {

    public ConfusionAnalysisResult {
        Objects.requireNonNull(matchedPairs, "matchedPairs 不能为空");
        Objects.requireNonNull(topDomain, "topDomain 不能为空");
        matchedPairs = List.copyOf(matchedPairs);
        if (confusionConfidence < 0.0 || confusionConfidence > 1.0) {
            throw new IllegalArgumentException("confusionConfidence 必须在 [0.0, 1.0] 区间，当前: " + confusionConfidence);
        }
    }

    /**
     * 单条命中的混淆概念对
     *
     * @param conceptA               概念 A
     * @param conceptB               概念 B
     * @param domain                 所属技术域
     * @param coreDifferenceSummary  两概念的核心差异摘要 (预置知识库文案)
     * @param hitFrequency           该对关键词在文本中的总出现次数 (A 侧 + B 侧，真实频次)
     */
    public record MatchedConfusionPair(
            String conceptA,
            String conceptB,
            String domain,
            String coreDifferenceSummary,
            int hitFrequency
    ) implements Serializable {

        public MatchedConfusionPair {
            Objects.requireNonNull(conceptA, "conceptA 不能为空");
            Objects.requireNonNull(conceptB, "conceptB 不能为空");
            Objects.requireNonNull(domain, "domain 不能为空");
            Objects.requireNonNull(coreDifferenceSummary, "coreDifferenceSummary 不能为空");
            if (hitFrequency < 0) {
                throw new IllegalArgumentException("hitFrequency 不能为负数");
            }
        }
    }
}
