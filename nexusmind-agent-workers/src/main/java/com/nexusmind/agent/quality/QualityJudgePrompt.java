package com.nexusmind.agent.quality;

/**
 * 质量评测 prompt 模板（切片 4）。
 *
 * <p>参考切片 2 的 {@code JournalPromptTemplates} 写法：固化 system 角色与严格 JSON 输出格式，
 * 按 {@code taskType} 切换评分 rubric，并给出 LLM 不可用时的确定性降级 JSON。
 */
public final class QualityJudgePrompt {

    private QualityJudgePrompt() {
    }

    /** 各 taskType 的默认评分维度说明（驱动 LLM-as-Judge 的 rubric）。 */
    private static String rubricFor(String taskType) {
        return switch (taskType == null ? "" : taskType) {
            case "code" -> "代码分析产出：技术性正确性、可执行性、风险识别完整度。";
            case "web_kb" -> "网页萃取产出：信息保真度、结构清晰度、去噪完整度。";
            case "tutor" -> "苏格拉底反思：提问深度、认知冲突张力、引导性。";
            case "journal" -> "日记复盘产出：突破点明确性、阻碍归因深度、行动可执行性。";
            default -> "通用产出：连贯性、相关性、可执行性。";
        };
    }

    public static String systemPrompt(String taskType) {
        return """
                你是一个严格的质量评审裁判（LLM-as-Judge）。请对给定产出进行结构化评分。

                # 评分维度
                """ + rubricFor(taskType) + """

                # 输出格式
                仅返回**有效的纯 JSON 对象**（不要 Markdown 包裹、不要额外文本）：
                {
                  "score": <0~1 之间的浮点数，综合质量分>,
                  "dimensions": { "<维度名>": <0~1 浮点数> },
                  "rationale": "<中文简述评分理由，不超过 60 字>"
                }

                # 约束
                - score 必须在 [0,1]，超出范围将被强制 clamp
                - 不要输出 ```json 标记
                """;
    }

    public static String userPrompt(String contentToJudge) {
        return "请评审以下产出：\n\n" + (contentToJudge == null ? "" : contentToJudge);
    }

    /** LLM 不可用时 chatOrFallback 的降级返回值：明确 0 分，避免污染统计。 */
    public static String fallbackJudgeJson() {
        return "{\"score\":0.0,\"dimensions\":{},\"rationale\":\"LLM 不可用，降级为 0 分\"}";
    }
}
