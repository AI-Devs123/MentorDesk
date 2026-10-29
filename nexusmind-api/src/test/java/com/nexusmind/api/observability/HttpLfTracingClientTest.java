package com.nexusmind.api.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpLfTracingClient 在线上报契约测试。
 *
 * 用 JDK 自带 HttpServer 起本地 stub（零新增依赖），验证：
 * 1. 事件以 batch ingestion 协议 POST 到 {@code {host}/api/public/ingestion}；
 * 2. 鉴权头为 Basic(publicKey:secretKey)；
 * 3. 单次调用产生 create + update 两类事件，且 generation.update 携带正确的 usage；
 * 4. 同步调用绝不抛异常（best-effort 铁律）。
 */
class HttpLfTracingClientTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private int port;

    private final List<String> capturedBodies = new ArrayList<>();
    private final List<String> capturedAuth = new ArrayList<>();
    private volatile CountDownLatch latch;

    @BeforeEach
    void startStub() throws IOException {
        capturedBodies.clear();
        capturedAuth.clear();
        latch = new CountDownLatch(5); // trace + span.create + span.update + generation.create + generation.update
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler handler = exchange -> {
            capturedAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBodies.add(body);
            byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            latch.countDown();
        };
        server.createContext("/api/public/ingestion", handler);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private HttpLfTracingClient client() {
        return new HttpLfTracingClient("http://127.0.0.1:" + port, "pk-123", "sk-456");
    }

    @Test
    @DisplayName("启用态: isEnabled 为 true，且上报鉴权头为 Basic(pk:sk)")
    void enabledAndAuthHeader() throws Exception {
        HttpLfTracingClient c = client();
        assertTrue(c.isEnabled(), "Http 实现必须 isEnabled=true");

        c.trace("t1", "orchestration", null, null);
        c.spanAndClose("t1", "s1", "span", "in", "out", 1L, 2L, "DEFAULT");
        c.generationAndClose("t1", "g1", "llm:gpt-4o-mini", "gpt-4o-mini",
                "prompt", "completion", new TokenUsage(10, 5, 15), 3L, 4L);

        latch.await(5, TimeUnit.SECONDS);
        assertEquals(5, capturedBodies.size(), "应产生 5 个上报事件");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("pk-123:sk-456".getBytes(StandardCharsets.UTF_8));
        assertTrue(capturedAuth.stream().allMatch(expected::equals), "所有请求必须携带正确的 Basic 鉴权头");
    }

    @Test
    @DisplayName("事件协议: batch 内含 trace/span/generation 的 create+update，且 generation.update 携带 usage")
    void batchPayloadStructure() throws Exception {
        HttpLfTracingClient c = client();
        c.trace("trace-x", "orchestration", null, null);
        c.spanAndClose("trace-x", "span-x", "spanName", "in", "out", 1L, 2L, "DEFAULT");
        c.generationAndClose("trace-x", "gen-x", "llm:gpt-4o-mini", "gpt-4o-mini",
                "sys---user", "the completion", new TokenUsage(12, 7, 19), 3L, 4L);

        latch.await(5, TimeUnit.SECONDS);

        List<String> types = new ArrayList<>();
        JsonNode generationUpdate = null;
        JsonNode generationCreate = null;
        for (String body : capturedBodies) {
            JsonNode root = mapper.readTree(body);
            JsonNode batch = root.get("batch");
            assertNotNull(batch, "payload 必须含 batch 数组");
            assertEquals(1, batch.size(), "每个 emit 应单独成批（一次一个事件）");
            JsonNode ev = batch.get(0);
            types.add(ev.get("type").asText());
            if ("generation.update".equals(ev.get("type").asText())) {
                generationUpdate = ev.get("body");
            }
            if ("generation.create".equals(ev.get("type").asText())) {
                generationCreate = ev.get("body");
            }
        }

        assertTrue(types.contains("trace.create"), "必须含 trace.create");
        assertTrue(types.contains("span.create"), "必须含 span.create");
        assertTrue(types.contains("span.update"), "必须含 span.update");
        assertTrue(types.contains("generation.create"), "必须含 generation.create");
        assertTrue(types.contains("generation.update"), "必须含 generation.update");

        assertNotNull(generationCreate, "应捕获到 generation.create");
        assertEquals("gpt-4o-mini", generationCreate.get("model").asText());
        assertEquals("sys---user", generationCreate.get("input").asText());

        assertNotNull(generationUpdate, "应捕获到 generation.update");
        JsonNode usage = generationUpdate.get("usage");
        assertNotNull(usage, "generation.update 必须携带 usage");
        assertEquals(12, usage.get("input").asInt(), "prompt_tokens 必须透传");
        assertEquals(7, usage.get("output").asInt(), "completion_tokens 必须透传");
        assertEquals(19, usage.get("total").asInt(), "total_tokens 必须透传");
    }

    @Test
    @DisplayName("NoOp 实现: isEnabled 为 false，且调用绝不抛异常")
    void noOpIsDisabledAndSafe() {
        LfTracingClient noop = new NoOpLfTracingClient();
        assertEquals(false, noop.isEnabled(), "NoOp 必须 isEnabled=false");
        // 同步调用不得抛异常（best-effort 铁律的退化验证）
        noop.trace("t", "n", null, null);
        noop.spanAndClose("t", "s", "n", "i", "o", 1L, 2L, "DEFAULT");
        noop.generationAndClose("t", "g", "llm:x", "x", "p", "c", TokenUsage.ZERO, 1L, 2L);
    }
}
