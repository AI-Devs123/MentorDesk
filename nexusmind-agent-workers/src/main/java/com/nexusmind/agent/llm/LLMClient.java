package com.nexusmind.agent.llm;

import com.nexusmind.agent.journal.JournalPromptTemplates;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nexusmind.api.observability.HttpLfTracingClient;
import com.nexusmind.api.observability.LfTracingClient;
import com.nexusmind.api.observability.LfTraceContext;
import com.nexusmind.api.observability.NoOpLfTracingClient;
import com.nexusmind.api.observability.TokenUsage;
import com.nexusmind.agent.metrics.LlmCallMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
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

    // ── 切片 3：Langfuse 在线可观测（@Value 本地构建，非 @Autowired；默认 NoOp 降级）──
    @Value("${observability.langfuse.enabled:false}")
    private boolean langfuseEnabled;

    @Value("${observability.langfuse.host:}")
    private String langfuseHost;

    @Value("${observability.langfuse.public-key:}")
    private String langfusePublicKey;

    @Value("${observability.langfuse.secret-key:}")
    private String langfuseSecretKey;

    /** 延迟构建的探针客户端（@Value 在构造后注入，故不能在构造器里 new）。启用前恒为 null。 */
    private volatile LfTracingClient tracingClient;

    // 切片 1 可观测性：LLM 调用指标。required=false 避免无 Spring 上下文（单测直接 new LLMClient）时注入失败。
    @Autowired(required = false)
    private LlmCallMetrics llmCallMetrics;

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
            long llmStart = System.currentTimeMillis();
            ChatResult r = chatWithUsage(systemPrompt, userPrompt, temperature, maxTokens);
            long llmEnd = System.currentTimeMillis();
            log.info("[LLMClient] LLM 调用成功 (prompt={} completion={} total={})",
                    r.usage().promptTokens(), r.usage().completionTokens(), r.usage().totalTokens());
            reportGeneration(systemPrompt, userPrompt, r, llmStart, llmEnd);
            if (llmCallMetrics != null) {
                llmCallMetrics.recordCall(llmEnd - llmStart, r.usage().totalTokens(), true);
            }
            return r.content();
        } catch (Exception e) {
            log.warn("[LLMClient] LLM 调用失败，降级到 Mock: {}", e.getMessage());
            if (llmCallMetrics != null) {
                llmCallMetrics.recordCall(0, 0, false);
            }
            return mockSupplier.get();
        }
    }

    /**
     * 切片 3：真实 LLM 调用成功后，best-effort 向 Langfuse 上报一次 generation 事件。
     *
     * <p>仅在探针启用且存在 trace 上下文时上报——无 trace（如本地无上游编排切面 / 单元测试未 set）
     * 则跳过，避免产生孤儿事件。任何异常（序列化 / 网络 / 探针未就绪）一律吞掉，绝不拖垮调用方。
     */
    private void reportGeneration(String systemPrompt, String userPrompt, ChatResult r,
                                  long startMs, long endMs) {
        try {
            LfTracingClient tc = resolveTracingClient();
            if (!tc.isEnabled()) {
                return;
            }
            String traceId = LfTraceContext.readFromRpc();
            if (traceId == null) {
                traceId = LfTraceContext.getTraceId(); // 同进程回退（本地测试 / 非 RPC 场景）
            }
            if (traceId == null) {
                return; // 没有 trace 上下文，不上报孤儿 generation
            }
            String generationId = UUID.randomUUID().toString();
            String prompt = systemPrompt + "\n---\n" + userPrompt;
            tc.generationAndClose(traceId, generationId, "llm:" + model, model,
                    prompt, r.content(), r.usage(), startMs, endMs);
        } catch (Exception e) {
            log.warn("[LLMClient] generation 上报失败(非阻断): {}", e.getMessage());
        }
    }

    /** 延迟构建 Langfuse 探针：启用且 host 非空 → HttpLfTracingClient，否则 NoOp（默认）。 */
    private LfTracingClient resolveTracingClient() {
        LfTracingClient c = tracingClient;
        if (c == null) {
            synchronized (this) {
                c = tracingClient;
                if (c == null) {
                    if (langfuseEnabled && langfuseHost != null && !langfuseHost.isEmpty()) {
                        c = new HttpLfTracingClient(langfuseHost, langfusePublicKey, langfuseSecretKey);
                    } else {
                        c = new NoOpLfTracingClient();
                    }
                    tracingClient = c;
                }
            }
        }
        return c;
    }

    /**
     * 切片 3 通用 LLM 对话结果：携带响应正文与 token 用量，供可观测层上报 generation。
     */
    public record ChatResult(String content, TokenUsage usage) {
    }

    /**
     * 通用 LLM 对话（真实调用，异常上抛由调用方决定降级策略）。
     *
     * <p>切片 3：除返回正文外，额外解析 OpenAI {@code usage}，使调用方（{@link #chatOrFallback}）
     * 可在不破坏既有 {@link #chat} String 契约的前提下上报 token 成本。
     */
    public ChatResult chatWithUsage(String systemPrompt, String userPrompt, double temperature, int maxTokens) throws Exception {
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
        String content = jsonResponse.getAsJsonArray("choices")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();
        return new ChatResult(content, parseUsage(jsonResponse));
    }

    /**
     * 兼容旧签名：仅返回正文（切片 2 及更早调用方 / 契约测试无需改动）。
     */
    public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) throws Exception {
        return chatWithUsage(systemPrompt, userPrompt, temperature, maxTokens).content();
    }

    /**
     * 从 OpenAI 响应体解析 {@code usage}（prompt_tokens / completion_tokens / total_tokens）。
     * 解析失败或缺失时回退 {@link TokenUsage#ZERO}，绝不因监控字段异常拖垮主链路。
     */
    private TokenUsage parseUsage(JsonObject jsonResponse) {
        try {
            if (jsonResponse.has("usage") && !jsonResponse.get("usage").isJsonNull()) {
                JsonObject u = jsonResponse.getAsJsonObject("usage");
                int prompt = u.has("prompt_tokens") ? u.get("prompt_tokens").getAsInt() : 0;
                int completion = u.has("completion_tokens") ? u.get("completion_tokens").getAsInt() : 0;
                int total = u.has("total_tokens") ? u.get("total_tokens").getAsInt() : (prompt + completion);
                return new TokenUsage(prompt, completion, total);
            }
        } catch (Exception e) {
            log.warn("[LLMClient] usage 解析失败(非阻断): {}", e.getMessage());
        }
        return TokenUsage.ZERO;
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
