package com.nexusmind.api.observability;

import java.util.Map;

/**
 * Langfuse 在线可观测探针客户端抽象（切片 3）。
 *
 * <p><b>设计取舍（reality-check 结论）：</b>官方 {@code com.langfuse:langfuse-java} 是 GitHub Packages 上的
 * SNAPSHOT（需鉴权才能拉取），Maven Central 上的 {@code com.konfigthis:langfuse-java-sdk} 用的是
 * 2026-11-16 日落的 legacy ingestion API（距本次实现仅 18 天）。二者都无法可靠进入本工程的 Maven 构建，
 * 且本机测试不能依赖真实运行的 Langfuse 服务。故选用「零外部 SDK 依赖」的轻量内置客户端：
 * 仅依赖 JDK 17 自带的 {@code java.net.http.HttpClient} 与工程 BOM 已管理的 Jackson，离线可构建、no-op 可降级、
 * 契合「本地隐私、无厂商锁定」原则。
 *
 * <p>所有方法均为 best-effort：实现类必须保证调用方（AOP 切面 / LLMClient）绝不因上报失败而收到异常。
 */
public interface LfTracingClient {

    /** 创建一条 Trace（对应一次前端任务下发）。traceId 由调用方保证全局唯一。 */
    void trace(String traceId, String name, String inputJson, Map<String, Object> metadata);

    /** 创建并立即关闭一个 Span（对应一次编排入口 / 一次下游 RPC 执行）。 */
    void spanAndClose(String traceId, String spanId, String name,
                      String inputSummary, String outputSummary,
                      long startTimeMs, long endTimeMs, String level);

    /** 创建并立即关闭一个 Generation（对应一次 LLM 调用，含 prompt / completion / token 成本）。 */
    void generationAndClose(String traceId, String generationId, String name,
                            String model, String prompt, String completion,
                            TokenUsage usage, long startTimeMs, long endTimeMs);

    /** true 表示真实上报（HTTP），false 表示 NoOp 降级。 */
    default boolean isEnabled() {
        return true;
    }
}
