package com.nexusmind.api.observability;

/**
 * LLM token 用量（切片 3）。字段与 OpenAI {@code usage} 对齐。
 *
 * <p>置于 api 共享包：{@code LfTracingClient} 接口与 agent-workers 的 {@code LLMClient} 都依赖它，
 * 若放在 agent-workers 则会导致 api 反向依赖 agent-workers（破坏模块边界）。
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

    /** 未解析到用量（Mock 降级 / 模型未返回 usage）时的零值。 */
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);
}
