package com.nexusmind.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.api.observability.LfTraceContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLMClient 切片 3 可观测集成测试（纯单元，零 Spring 上下文）。
 *
 * 用 JDK 自带 HttpServer 同时 stub OpenAI 与 Langfuse 两个端点，验证：
 * 1. 真实调用成功后解析出 usage，并在探针启用 + 存在 trace 上下文时向 Langfuse 上报 generation（含 usage）；
 * 2. 探针关闭（默认）时绝不发起上报；
 * 3. 探针启用但无 trace 上下文时也不上报孤儿事件。
 * 复用 {@code LLMClientHttpContractTest} 的反射注入范式（直接 new 的实例 @Value 不生效）。
 */
class LLMClientObservabilityTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private HttpServer openaiServer;
    private HttpServer langfuseServer;
    private int openaiPort;
    private int langfusePort;

    private final List<String> langfuseBodies = new ArrayList<>();
    private volatile CountDownLatch langfuseLatch;

    @BeforeEach
    void startStubs() throws IOException {
        langfuseBodies.clear();

        // OpenAI stub：返回正文 + usage
        openaiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        openaiServer.createContext("/v1/chat/completions", exchange -> {
            String resp = "{\"choices\":[{\"message\":{\"content\":\"reflect content\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
            byte[] body = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        openaiServer.start();
        openaiPort = openaiServer.getAddress().getPort();

        // Langfuse stub：捕获 ingestion 上报
        langfuseServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        langfuseServer.createContext("/api/public/ingestion", exchange -> {
            String b = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            langfuseBodies.add(b);
            byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            if (langfuseLatch != null) {
                langfuseLatch.countDown();
            }
        });
        langfuseServer.start();
        langfusePort = langfuseServer.getAddress().getPort();
    }

    @AfterEach
    void stopStubs() {
        openaiServer.stop(0);
        langfuseServer.stop(0);
        LfTraceContext.clear();
    }

    private LLMClient newClient(boolean langfuseOn) throws Exception {
        LLMClient client = new LLMClient();
        setField(client, "apiKey", "sk-test-key");
        setField(client, "model", "gpt-4o-mini");
        setField(client, "timeoutSeconds", 5);
        setField(client, "baseUrl", "http://127.0.0.1:" + openaiPort + "/v1");
        setField(client, "langfuseEnabled", langfuseOn);
        if (langfuseOn) {
            setField(client, "langfuseHost", "http://127.0.0.1:" + langfusePort);
            setField(client, "langfusePublicKey", "pk");
            setField(client, "langfuseSecretKey", "sk");
        }
        return client;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = LLMClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("启用+有 trace: 真实调用后上报 generation 且 usage 正确透传")
    void reportsGenerationWhenEnabledAndTraced() throws Exception {
        langfuseLatch = new CountDownLatch(2); // generation.create + generation.update
        LLMClient client = newClient(true);
        LfTraceContext.set("trace-obs-1");

        String content = client.chatOrFallback("你是导师", "请提问", 0.7, 1500, () -> "MOCK");

        assertEquals("reflect content", content, "正文必须来自真实 stub（非 Mock）");

        assertTrue(langfuseLatch.await(5, TimeUnit.SECONDS), "必须收到 generation 上报");
        assertEquals(2, langfuseBodies.size(), "应产生 generation.create + update 两条事件");

        JsonNode update = findEvent(langfuseBodies, "generation.update");
        assertTrue(update != null, "必须含 generation.update");
        JsonNode usage = update.get("body").get("usage");
        assertEquals(10, usage.get("input").asInt(), "prompt_tokens 必须透传");
        assertEquals(5, usage.get("output").asInt(), "completion_tokens 必须透传");
        assertEquals(15, usage.get("total").asInt(), "total_tokens 必须透传");

        JsonNode create = findEvent(langfuseBodies, "generation.create");
        assertEquals("gpt-4o-mini", create.get("body").get("model").asText());
        assertEquals("trace-obs-1", create.get("body").get("traceId").asText(), "generation 必须归并到上游 trace");
    }

    @Test
    @DisplayName("探针关闭(默认): 绝不发起 Langfuse 上报")
    void noReportWhenDisabled() throws Exception {
        langfuseLatch = new CountDownLatch(1);
        LLMClient client = newClient(false);
        LfTraceContext.set("trace-obs-2");

        String content = client.chatOrFallback("你是导师", "请提问", 0.7, 1500, () -> "MOCK");
        assertEquals("reflect content", content);

        assertFalse(langfuseLatch.await(1, TimeUnit.SECONDS), "禁用时不应收到任何上报");
        assertTrue(langfuseBodies.isEmpty(), "禁用时 Langfuse 列表必须为空");
    }

    @Test
    @DisplayName("启用但无 trace 上下文: 不上报孤儿 generation")
    void noReportWhenNoTraceContext() throws Exception {
        langfuseLatch = new CountDownLatch(1);
        LLMClient client = newClient(true);
        // 注意：不调用 LfTraceContext.set(...)

        String content = client.chatOrFallback("sys", "user", 0.7, 1500, () -> "MOCK");
        assertEquals("reflect content", content);

        assertFalse(langfuseLatch.await(1, TimeUnit.SECONDS), "无 trace 时不应上报");
        assertTrue(langfuseBodies.isEmpty(), "无 trace 时 Langfuse 列表必须为空");
    }

    private static JsonNode findEvent(List<String> bodies, String type) throws Exception {
        for (String b : bodies) {
            JsonNode root = mapper.readTree(b);
            JsonNode batch = root.get("batch");
            if (batch != null && batch.size() > 0) {
                JsonNode ev = batch.get(0);
                if (type.equals(ev.get("type").asText())) {
                    return ev;
                }
            }
        }
        return null;
    }
}
