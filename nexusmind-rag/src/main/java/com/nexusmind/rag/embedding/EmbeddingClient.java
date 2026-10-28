package com.nexusmind.rag.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 进程内 / 远程 Embedding 客户端（切片 0 最小补齐）。
 *
 * <p>零开销 WebClient 接入任意兼容 OpenAI 规范的 {@code /v1/embeddings} 端点
 * （本地 Ollama 的 nomic-embed-text、远程 API 等）。
 *
 * <p><b>防静默降级</b>：当未配置真实端点时，默认<b>拒绝</b>返回伪向量——
 * 否则生产环境会在"看起来正常"的情况下用随机向量做语义检索，污染 Week8 自进化知识关联。
 * 仅在显式 {@code nexusmind.embedding.mock=true}（测试/演示）时才允许确定性伪向量兜底。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int MOCK_DIM = 512;

    private final WebClient webClient;
    private final String model;
    private final boolean enabled;
    private final boolean mockAllowed;

    public EmbeddingClient(
            @Value("${nexusmind.embedding.base-url:}") String baseUrl,
            @Value("${nexusmind.embedding.api-key:}") String apiKey,
            @Value("${nexusmind.embedding.model:bge-small-zh-v1.5}") String model,
            @Value("${nexusmind.embedding.mock:false}") boolean mock) {
        this.model = model;
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.mockAllowed = mock;
        if (!enabled) {
            log.warn("[EmbeddingClient] 未配置 nexusmind.embedding.base-url，向量化通道禁用。"
                    + (mockAllowed ? " 已显式开启 mock 降级（仅测试/演示）。"
                                   : " 非测试环境请配置真实端点，否则语义检索不可用。"));
        }
        if (enabled) {
            this.webClient = WebClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .build();
        } else {
            this.webClient = null;
        }
    }

    /**
     * 文本特征向量提取。
     *
     * @throws IllegalStateException 未配置端点且未显式开启 mock 时，拒绝静默降级
     */
    public float[] embed(String text) {
        if (!enabled) {
            if (!mockAllowed) {
                throw new IllegalStateException(
                        "EmbeddingClient 未配置真实端点且未显式开启 mock（nexusmind.embedding.mock=true）。"
                                + "拒绝静默降级——语义检索不可用，请配置 nexusmind.embedding.base-url 或显式开启 mock。");
            }
            return generateDeterministicMockVector(text, MOCK_DIM);
        }
        try {
            Map<?, ?> response = webClient.post()
                    .uri("/v1/embeddings")
                    .bodyValue(Map.of("input", text, "model", model))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));

            List<?> data = (List<?>) response.get("data");
            Map<?, ?> first = (Map<?, ?>) data.get(0);
            List<?> embedding = (List<?>) first.get("embedding");

            float[] vec = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vec[i] = ((Number) embedding.get(i)).floatValue();
            }
            return vec;
        } catch (Exception e) {
            if (mockAllowed) {
                log.warn("[EmbeddingClient] 真实 embedding 调用失败，回退 mock：{}", e.getMessage());
                return generateDeterministicMockVector(text, MOCK_DIM);
            }
            throw new IllegalStateException("embedding 端点调用失败且未开启 mock 降级: " + e.getMessage(), e);
        }
    }

    /**
     * 确定性伪向量（仅测试/演示）：以文本 hashCode 作种子，保证同一文本映射稳定，便于断言可复现。
     */
    private float[] generateDeterministicMockVector(String text, int dim) {
        float[] vec = new float[dim];
        Random r = new Random(text.hashCode());
        float norm = 0f;
        for (int i = 0; i < dim; i++) {
            vec[i] = r.nextFloat() - 0.5f;
            norm += vec[i] * vec[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dim; i++) {
            vec[i] /= norm;
        }
        return vec;
    }
}
