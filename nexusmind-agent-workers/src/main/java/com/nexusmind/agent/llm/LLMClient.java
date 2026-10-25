package com.nexusmind.agent.llm;

import com.nexusmind.agent.journal.JournalPromptTemplates;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * LLM 客户端 - OpenAI 兼容 API 集成
 * 支持真实 LLM 调用和 Mock 降级
 *
 * 切片3 泛化说明:
 * - 新增 chat(system, user, temperature, maxTokens) 通用方法，解除与
 *   JournalPromptTemplates 的硬编码耦合，供 TutorAgent 等新 Agent 复用
 * - 新增 llm.base-url 配置，可平滑切换通义千问/DeepSeek 等 OpenAI 兼容端点
 * - callLlmOrFallback 保持原签名与语义（委托新方法），切片2 链路零回归
 */
@Component
public class LLMClient {

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);
    private static final Gson gson = new Gson();

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:gpt-4o-mini}")
    private String model;

    @Value("${llm.timeout-seconds:15}")
    private int timeoutSeconds;

    /** OpenAI 兼容端点 base-url，可配通义千问/DeepSeek/Ollama 等兼容服务 */
    @Value("${llm.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    private final HttpClient httpClient;
    private final AtomicInteger callCounter = new AtomicInteger(0);

    public LLMClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
    }

    /**
     * 调用 LLM 或使用 Mock 降级（journal 链路专用入口，保持向后兼容）
     *
     * 策略:
     * 1. 如果 API Key 已配置，调用真实 LLM
     * 2. 如果 LLM 失败或未配置，返回确定性 Mock 数据
     * 3. 确保不阻塞前端联调
     *
     * @param rawContent 日记原文
     * @return JSON 格式的三要素结果
     */
    public String callLlmOrFallback(String rawContent) {
        return chatOrFallback(
                JournalPromptTemplates.getSystemPrompt(),
                JournalPromptTemplates.getUserPrompt(rawContent),
                0.3,
                2000,
                JournalPromptTemplates::getMockJsonTemplate);
    }

    /**
     * 通用 LLM 对话入口（带 Mock 降级）
     *
     * @param systemPrompt  系统提示词
     * @param userPrompt    用户提示词
     * @param temperature   采样温度（journal 严格抽取 0.3，苏格拉底提问 0.7）
     * @param maxTokens     最大生成 token 数
     * @param mockSupplier  Mock 降级数据供应者（调用方提供各自场景的合规 Mock）
     * @return LLM 响应或 Mock 降级数据
     */
    public String chatOrFallback(String systemPrompt, String userPrompt, double temperature,
                                 int maxTokens, Supplier<String> mockSupplier) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("sk-test")) {
            log.warn("[LLMClient] LLM API Key 未配置，使用 Mock 数据");
            return mockSupplier.get();
        }

        try {
            log.info("[LLMClient] 调用 LLM (第 {} 次), model={}, baseUrl={}", callCounter.incrementAndGet(), model, baseUrl);
            String response = chat(systemPrompt, userPrompt, temperature, maxTokens);
            log.info("[LLMClient] LLM 调用成功");
            return response;
        } catch (Exception e) {
            log.warn("[LLMClient] LLM 调用失败，降级到 Mock: {}", e.getMessage());
            return mockSupplier.get();
        }
    }

    /**
     * 通用 LLM 对话（真实调用，异常上抛由调用方决定降级策略）
     */
    public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("temperature", temperature);
        requestBody.addProperty("max_tokens", maxTokens);

        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        requestBody.add("messages", messages);

        String endpoint = stripTrailingSlash(baseUrl) + "/chat/completions";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM API 错误: " + response.statusCode() + ", endpoint=" + endpoint);
        }

        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        return jsonResponse.getAsJsonArray("choices")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
