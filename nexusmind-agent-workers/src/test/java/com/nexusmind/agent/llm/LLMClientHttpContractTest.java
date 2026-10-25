package com.nexusmind.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLMClient 真实 HTTP 路径契约测试
 *
 * 背景: 单元测试里 LLM 调用全部走 mock 供应者，真实 HTTP 路径
 * (请求体构造 / 鉴权头 / base-url 端点 / 非 200 处理) 是单测盲区。
 * 本测试用 JDK 自带 HttpServer 起本地 stub (零新增依赖) 覆盖该盲区。
 */
class LLMClientHttpContractTest {

    private static final Gson gson = new Gson();

    private HttpServer server;
    private int port;

    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String content = "{\"questions\":[{\"conceptA\":\"A\",\"conceptB\":\"B\",\"questionText\":\"Q\"}]}";
            String response = "{\"choices\":[{\"message\":{\"content\":" + quote(content) + "}}]}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    /** 反射注入私有配置字段 (直接 new 的实例 @Value 不生效) */
    private LLMClient newClientWithStub(String baseUrlPath) throws Exception {
        LLMClient client = new LLMClient();
        setField(client, "apiKey", "sk-contract-test");
        setField(client, "model", "gpt-4o-mini");
        setField(client, "timeoutSeconds", 5);
        setField(client, "baseUrl", "http://127.0.0.1:" + port + baseUrlPath);
        return client;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LLMClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    @DisplayName("真实 HTTP 路径: 请求体含 temperature/max_tokens/system+user 消息与 Bearer 鉴权头")
    void shouldSendWellFormedRequestOverHttp() throws Exception {
        LLMClient client = newClientWithStub("/v1");

        String content = client.chat("你是导师", "请提问", 0.7, 1500);

        // 响应解析: 提取 choices[0].message.content
        assertTrue(content.contains("conceptA"), "必须正确提取 OpenAI 响应content");

        JsonObject body = gson.fromJson(capturedBody.get(), JsonObject.class);
        assertEquals("gpt-4o-mini", body.get("model").getAsString());
        assertEquals(0.7, body.get("temperature").getAsDouble(), 0.0001,
                "temperature 必须透传，Tutor 0.7 与 journal 0.3 才有区分");
        assertEquals(1500, body.get("max_tokens").getAsInt());

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("你是导师", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("请提问", messages.get(1).getAsJsonObject().get("content").getAsString());

        assertEquals("Bearer sk-contract-test", capturedAuth.get());
        assertEquals("/v1/chat/completions", capturedPath.get());
    }

    @Test
    @DisplayName("base-url 可切换: 请求命中配置端点而非硬编码 OpenAI (尾斜杠容忍)")
    void shouldRespectConfigurableBaseUrl() throws Exception {
        // 带尾斜杠的 base-url 不应产生双斜杠
        LLMClient client = newClientWithStub("/v1/");

        client.chat("sys", "user", 0.3, 2000);

        assertEquals("/v1/chat/completions", capturedPath.get(),
                "base-url 尾斜杠必须被归一化，否则产生 //chat/completions");
    }

    @Test
    @DisplayName("非 200 响应: chat 抛异常且 chatOrFallback 降级到调用方 Mock")
    void shouldThrowOnNon200AndDegradeToMock() throws Exception {
        HttpServer badServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        badServer.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        badServer.start();
        try {
            LLMClient client = new LLMClient();
            setField(client, "apiKey", "sk-contract-test");
            setField(client, "timeoutSeconds", 5);
            setField(client, "baseUrl", "http://127.0.0.1:" + badServer.getAddress().getPort() + "/v1");

            // chat 直接抛异常
            assertThrows(Exception.class, () -> client.chat("sys", "user", 0.7, 1500));

            // chatOrFallback 捕获后降级
            String fallback = client.chatOrFallback("sys", "user", 0.7, 1500, () -> "MOCK_JSON");
            assertEquals("MOCK_JSON", fallback,
                    "真实调用失败必须降级到调用方 Mock，保证联调不中断");
        } finally {
            badServer.stop(0);
        }
    }

    @Test
    @DisplayName("journal 链路回归: callLlmOrFallback 委托后 system prompt 仍为日记分析专家")
    void shouldDelegateWithJournalSystemPrompt() throws Exception {
        LLMClient client = newClientWithStub("/v1");

        client.callLlmOrFallback("今天打通了 Triple 协议");

        JsonObject body = gson.fromJson(capturedBody.get(), JsonObject.class);
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(messages.get(0).getAsJsonObject().get("content").getAsString().contains("日记分析专家"),
                "委托改造不得改变 journal 链路的 system prompt 内容");
        assertEquals(0.3, body.get("temperature").getAsDouble(), 0.0001,
                "journal 链路温度必须保持 0.3 严格抽取");
        assertEquals(2000, body.get("max_tokens").getAsInt());
    }
}
