package com.nexusmind.api.quality;

/**
 * 质量评测请求（切片 4 内联 LLM-as-Judge 契约）。
 *
 * <p>由 supervisor 在任务完成钩子里构造，经 Dubbo Triple 传往 agent-workers 的
 * {@code QualityJudgeServiceImpl} 执行评分（与 LLMClient 同进程，复用其 base-url/api-key/降级）。
 */
public record JudgeRequest(
        /** 任务类型，驱动评分 rubric 选择：journal / code / web_kb / tutor */
        String taskType,
        /** 待评分的产出正文 */
        String contentToJudge,
        /** 可选的自定义评分维度说明（为空则使用 taskType 默认 rubric） */
        String rubric,
        /** 附加上下文（JSON 字符串，如原始 prompt / 检索上下文），供 judge 参考 */
        String contextJson
) {
}
