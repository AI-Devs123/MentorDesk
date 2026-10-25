package com.nexusmind.agent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLMClient 回归测试
 *
 * 切片3 泛化改造后，验证 journal 链路原行为零回归:
 * - callLlmOrFallback 签名与 Mock 降级语义保持不变
 * - chatOrFallback 新入口在无 Key 时走调用方 mock 供应者
 */
class LLMClientTest {

    /** apiKey 未注入(null)，等价于未配置场景 */
    private final LLMClient client = new LLMClient();

    @Test
    @DisplayName("回归: callLlmOrFallback 无 Key 时返回 journal Mock (含三要素)")
    void shouldReturnJournalMockWhenNoApiKey() {
        String response = client.callLlmOrFallback("今天打通了 Triple 协议");

        assertTrue(response.contains("breakthroughs"), "journal Mock 必须包含 breakthroughs");
        assertTrue(response.contains("mvpActions"), "journal Mock 必须包含 mvpActions");
    }

    @Test
    @DisplayName("新入口: chatOrFallback 无 Key 时走调用方 mock 供应者")
    void shouldUseCallerMockSupplierWhenNoApiKey() {
        String response = client.chatOrFallback("system", "user", 0.7, 1500,
                () -> "{\"questions\":[{\"conceptA\":\"A\",\"conceptB\":\"B\"}]}");

        assertEquals("{\"questions\":[{\"conceptA\":\"A\",\"conceptB\":\"B\"}]}", response,
                "无 Key 时必须返回调用方提供的 Mock 而非发起网络调用");
    }
}
