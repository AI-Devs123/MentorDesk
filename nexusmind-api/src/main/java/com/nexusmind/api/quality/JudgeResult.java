package com.nexusmind.api.quality;

import java.util.Collections;
import java.util.Map;

/**
 * 质量评测结果（切片 4 内联 LLM-as-Judge 契约）。
 *
 * <p>{@code score} 语义为 0~1 综合质量分（越界由执行侧 clamp，下游无需二次防御）。
 * {@code dimensions} 为分项分（如 coherence / relevance / actionability），允许为空。
 */
public record JudgeResult(
        /** 综合质量分 0~1 */
        double score,
        /** 多维度分项分（维度名 → 0~1） */
        Map<String, Double> dimensions,
        /** 评分理由（中文简述） */
        String rationale,
        /** 实际执行评分的模型标识 */
        String model
) {
    /** 降级常量：未评分 / 评测异常时统一为 0 分，避免污染下游统计。 */
    public static final JudgeResult ZERO =
            new JudgeResult(0.0, Collections.emptyMap(), "未评分（降级）", "n/a");
}
