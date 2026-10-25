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

/**
 * LLM 客户端 - OpenAI API 集成
 * 支持真实 LLM 调用和 Mock 降级
 */
@Component
public class LLMClient {
    
    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final Gson gson = new Gson();
    
    @Value("${llm.api-key:}")
    private String apiKey;
    
    @Value("${llm.model:gpt-4o-mini}")
    private String model;
    
    @Value("${llm.timeout-seconds:15}")
    private int timeoutSeconds;
    
    private final HttpClient httpClient;
    private final AtomicInteger callCounter = new AtomicInteger(0);
    
    public LLMClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
    }
    
    /**
     * 调用 LLM 或使用 Mock 降级
     * 
     * 策略:
     * 1. 如果 API Key 已配置，调用真实 LLM (15s 超时)
     * 2. 如果 LLM 失败或未配置，返回确定性 Mock 数据
     * 3. 确保不阻塞前端联调
     * 
     * @param rawContent 日记原文
     * @return JSON 格式的三要素结果
     */
    public String callLlmOrFallback(String rawContent) {
        // 检查 API Key 是否配置
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("sk-test")) {
            log.warn("⚠️ [LLMClient] LLM API Key 未配置，使用 Mock 数据");
            return getMockResponse();
        }
        
        try {
            log.info("🤖 [LLMClient] 调用 LLM (第 {} 次)...", callCounter.incrementAndGet());
            
            String systemPrompt = JournalPromptTemplates.getSystemPrompt();
            String userPrompt = JournalPromptTemplates.getUserPrompt(rawContent);
            
            String response = callOpenAI(systemPrompt, userPrompt);
            log.info("✅ [LLMClient] LLM 调用成功");
            
            return response;
            
        } catch (Exception e) {
            log.warn("⚠️ [LLMClient] LLM 调用失败，降级到 Mock: {}", e.getMessage());
            return getMockResponse();
        }
    }
    
    /**
     * 调用 OpenAI API
     */
    private String callOpenAI(String systemPrompt, String userPrompt) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("temperature", 0.3);
        requestBody.addProperty("max_tokens", 2000);
        
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
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API 错误: " + response.statusCode());
        }
        
        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        String content = jsonResponse.getAsJsonArray("choices")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();
        
        return content;
    }
    
    /**
     * 获取确定性 Mock 响应 (用于降级)
     */
    private String getMockResponse() {
        return JournalPromptTemplates.getMockJsonTemplate();
    }
}
