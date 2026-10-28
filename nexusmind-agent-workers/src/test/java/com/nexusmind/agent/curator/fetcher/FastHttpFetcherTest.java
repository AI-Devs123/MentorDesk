package com.nexusmind.agent.curator.fetcher;

import com.nexusmind.security.SsrfProtectionValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FastHttpFetcher 端到端测试。
 * <p>
 * 思路：用 JDK 内置 {@link HttpServer} 在 127.0.0.1 起一个桩服务（放行桩校验器绕过 SSRF，仅验证抓取逻辑本身），
 * 覆盖：正常拉取、重定向上限熔断、相对/协议相对 Location 正确 resolve 跟随、超大响应体体积熔断。
 */
class FastHttpFetcherTest {

    /** 放行桩：绕过安全校验，仅测抓取器的重定向/体积/解析逻辑。 */
    private static final class PermissiveValidator extends SsrfProtectionValidator {
        @Override
        public void validateUrl(String targetUrl) {
            // no-op：放行一切，避免桩服务绑定在 127.0.0.1 被拦
        }

        @Override
        public java.util.List<InetAddress> resolveAllowedAddresses(String host) throws SecurityException {
            try {
                return java.util.Arrays.asList(InetAddress.getAllByName(host));
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException("stub resolve failed", e);
            }
        }
    }

    private HttpServer server;
    private int port;
    private FastHttpFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = "<html><body><h1>Hello NexusMind</h1></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("RagApplicationContent-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        // 相对路径重定向：/a -> 302 Location: /page
        server.createContext("/a", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // 重定向环：/loop -> 302 Location: /loop（用于触发重定向上限熔断）
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // 超大响应：/big -> 6MB 文本（超 5MB 上限，应触发体积熔断）
        server.createContext("/big", exchange -> {
            byte[] body = new byte[6 * 1024 * 1024];
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
        fetcher = new FastHttpFetcher(new PermissiveValidator());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    @Test
    @DisplayName("正常拉取返回正文")
    void shouldFetchNormalPage() {
        String html = assertDoesNotThrow(() -> fetcher.fetchHtml(base() + "/page"));
        assertTrue(html.contains("Hello NexusMind"), "应返回页面正文, 实际: " + html);
    }

    @Test
    @DisplayName("相对路径 Location 302 应被正确 resolve 并跟随")
    void shouldFollowRelativeRedirect() {
        String html = assertDoesNotThrow(() -> fetcher.fetchHtml(base() + "/a"));
        assertTrue(html.contains("Hello NexusMind"), "应跟随 /a -> /page 重定向并返回正文, 实际: " + html);
    }

    @Test
    @DisplayName("重定向环超过上限应熔断（防 StackOverflow / 死循环）")
    void shouldBreakRedirectLoop() {
        SecurityException ex = assertThrows(SecurityException.class,
                () -> fetcher.fetchHtml(base() + "/loop"));
        assertTrue(ex.getMessage().contains("重定向次数超出安全上限"),
                "期望命中重定向上限熔断, 实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("响应体超过 5MB 应触发体积熔断")
    void shouldAbortOversizeBody() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> fetcher.fetchHtml(base() + "/big"));
        assertTrue(ex.getMessage().contains("页面大小超出安全上限"),
                "期望命中体积熔断, 实际: " + ex.getMessage());
    }
}
