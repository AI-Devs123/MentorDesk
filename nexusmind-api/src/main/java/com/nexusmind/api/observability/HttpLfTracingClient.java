package com.nexusmind.api.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 轻量 Langfuse 客户端：将 trace / span / generation 事件以 batch ingestion 协议 POST 到
 * {@code {host}/api/public/ingestion}（Basic Auth = publicKey:secretKey）。
 *
 * <p><b>异步非阻塞铁律：</b>所有上报走 {@link HttpClient#sendAsync} + 后台 daemon 线程池，绝不阻塞业务线程
 * （SSE 主链路）。这正呼应切片 2 前反复强调的「旁路异步采样评测」原则——同步内联打分/上报会雪崩 TTFT。
 *
 * <p><b>best-effort：</b>网络 / 序列化异常一律吞掉并告警，绝不向上抛。监控永不拖垮业务。
 */
public class HttpLfTracingClient implements LfTracingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLfTracingClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final URI ingestUri;
    private final String authHeader;

    public HttpLfTracingClient(String host, String publicKey, String secretKey) {
        this(host, publicKey, secretKey, defaultExecutor());
    }

    public HttpLfTracingClient(String host, String publicKey, String secretKey, Executor executor) {
        String base = (host != null && host.endsWith("/")) ? host.substring(0, host.length() - 1) : host;
        this.ingestUri = URI.create(base + "/api/public/ingestion");
        String cred = (publicKey == null ? "" : publicKey) + ":" + (secretKey == null ? "" : secretKey);
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    private static Executor defaultExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "langfuse-flush");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void trace(String traceId, String name, String inputJson, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", traceId);
        body.put("name", name);
        if (inputJson != null) {
            body.put("input", inputJson);
        }
        if (metadata != null) {
            body.put("metadata", metadata);
        }
        emit("trace.create", traceId, body);
    }

    @Override
    public void spanAndClose(String traceId, String spanId, String name,
                             String inputSummary, String outputSummary,
                             long startTimeMs, long endTimeMs, String level) {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("id", spanId);
        create.put("traceId", traceId);
        create.put("name", name);
        if (inputSummary != null) {
            create.put("input", inputSummary);
        }
        create.put("startTime", iso(startTimeMs));
        emit("span.create", traceId, create);

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("id", spanId);
        update.put("traceId", traceId);
        if (outputSummary != null) {
            update.put("output", outputSummary);
        }
        update.put("endTime", iso(endTimeMs));
        update.put("level", level);
        emit("span.update", traceId, update);
    }

    @Override
    public void generationAndClose(String traceId, String generationId, String name,
                                   String model, String prompt, String completion,
                                   TokenUsage usage, long startTimeMs, long endTimeMs) {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("id", generationId);
        create.put("traceId", traceId);
        create.put("name", name);
        create.put("model", model);
        if (prompt != null) {
            create.put("input", prompt);
        }
        create.put("startTime", iso(startTimeMs));
        emit("generation.create", traceId, create);

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("id", generationId);
        update.put("traceId", traceId);
        if (completion != null) {
            update.put("output", completion);
        }
        update.put("endTime", iso(endTimeMs));
        Map<String, Object> usageMap = new LinkedHashMap<>();
        usageMap.put("input", usage.promptTokens());
        usageMap.put("output", usage.completionTokens());
        usageMap.put("total", usage.totalTokens());
        update.put("usage", usageMap);
        emit("generation.update", traceId, update);
    }

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private void emit(String type, String traceId, Map<String, Object> body) {
        try {
            List<Object> batch = new ArrayList<>();
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("id", UUID.randomUUID().toString());
            ev.put("type", type);
            ev.put("timestamp", Instant.now().toString());
            ev.put("body", body);
            batch.add(ev);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batch", batch);

            String json = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(ingestUri)
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(3))
                    .build();

            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            log.warn("[Langfuse] 上报失败(非阻断): type={}, traceId={}, err={}",
                                    type, traceId, err.getMessage());
                        } else if (resp.statusCode() >= 400) {
                            log.warn("[Langfuse] 上报被拒: type={}, traceId={}, http={}",
                                    type, traceId, resp.statusCode());
                        }
                    });
        } catch (Exception e) {
            log.warn("[Langfuse] 序列化/上报异常(非阻断): type={}, traceId={}, err={}",
                    type, traceId, e.getMessage());
        }
    }
}
