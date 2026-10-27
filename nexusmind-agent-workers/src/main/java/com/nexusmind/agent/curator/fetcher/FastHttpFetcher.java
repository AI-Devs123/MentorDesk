package com.nexusmind.agent.curator.fetcher;

import com.nexusmind.security.SsrfProtectionValidator;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 轻量级快速通道抓取器（Fast Path）。
 * <p>
 * 针对静态 SSR / 博客 / 文档：使用 OkHttp 直连，并通过自定义 {@link Dns} 把 SSRF 核准的公网 IP 钉死，
 * 从连接层消除 DNS 重绑绕过窗口。禁止自动跟随重定向，对每个 3xx Location 重新做 SSRF 复核
 * （含相对 / 协议相对路径 resolve），并强制 5MB 体积熔断。
 */
@Component
public class FastHttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(FastHttpFetcher.class);

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_BODY_BYTES = 5L * 1024 * 1024; // 5MB 物理熔断
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) NexusMind-Curator/1.0";

    private final SsrfProtectionValidator ssrfValidator;
    private final OkHttpClient client;

    public FastHttpFetcher(SsrfProtectionValidator ssrfValidator) {
        this.ssrfValidator = ssrfValidator;
        // 自定义 Dns：连接时调用 SSRF 校验器解析并钉死已核准的公网 IP（防 DNS 重绑）。
        // 返回的是已审计的具体 InetAddress 对象，OkHttp 直接拿它建连，不会再重新解析。
        Dns pinnedDns = host -> {
            try {
                return ssrfValidator.resolveAllowedAddresses(host);
            } catch (SecurityException e) {
                // Dns 接口签名不允许受检异常；转译为运行时异常，由上层拦截
                throw new RuntimeException(e.getMessage(), e);
            }
        };
        this.client = new OkHttpClient.Builder()
                .dns(pinnedDns)
                .followRedirects(false) // 重定向由本类手动复核，禁止自动跳转
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 抓取目标 URL 的原始 HTML。
     *
     * @param targetUrl 目标链接
     * @return 去噪前的原始 HTML 文本（已限制体积）
     */
    public String fetchHtml(String targetUrl) throws Exception {
        ssrfValidator.validateUrl(targetUrl); // 入口一次性准入
        String currentUrl = targetUrl;
        int redirects = 0;
        while (true) {
            Request request = new Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (++redirects > MAX_REDIRECTS) {
                        throw new SecurityException(
                                "SecurityGuard 拦截: 重定向次数超出安全上限 (" + MAX_REDIRECTS + ")");
                    }
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IllegalStateException("收到重定向状态码但缺少 Location 头");
                    }
                    // 用 OkHttp 的 HttpUrl.resolve 正确处理相对 / 协议相对重定向
                    HttpUrl next = HttpUrl.get(currentUrl).resolve(location);
                    if (next == null) {
                        throw new SecurityException("SecurityGuard 拦截: 非法重定向目标 -> " + location);
                    }
                    String nextUrl = next.toString();
                    ssrfValidator.validateUrl(nextUrl); // 重定向目标重新 SSRF 复核（防 302 逃逸内网）
                    currentUrl = nextUrl;
                    log.info("Fast Path 跟随重定向 ({}/{}): {}", redirects, MAX_REDIRECTS, nextUrl);
                    continue;
                }
                if (code != 200) {
                    throw new IllegalStateException("HTTP 请求异常，状态码: " + code + " @ " + currentUrl);
                }
                String contentType = response.header("Content-Type");
                if (contentType != null) {
                    String ct = contentType.toLowerCase();
                    if (!ct.contains("text/html") && !ct.contains("xml") && !ct.contains("html")) {
                        throw new IllegalArgumentException("非 HTML 网页内容，类型为: " + contentType);
                    }
                }
                return readBodyWithLimit(response);
            }
        }
    }

    /**
     * 流式读取响应体并强制体积上限，避免超大文件 / 视频流导致 OOM。
     */
    private String readBodyWithLimit(Response response) throws Exception {
        ResponseBody body = response.body();
        if (body == null) {
            throw new IllegalStateException("响应体为空");
        }
        BufferedSource source = body.source();
        Buffer buffer = new Buffer();
        while (source.read(buffer, 8192) != -1) {
            if (buffer.size() > MAX_BODY_BYTES) {
                source.close();
                throw new IllegalStateException(
                        "页面大小超出安全上限 (" + MAX_BODY_BYTES + " 字节)，已中止抓取");
            }
        }
        return buffer.readString(StandardCharsets.UTF_8);
    }
}
