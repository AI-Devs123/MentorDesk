package com.nexusmind.agent.curator.fetcher;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import com.nexusmind.security.SsrfProtectionValidator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 动态渲染通道（Render Path）。针对 SPA / 反爬页面用 Playwright 无头 Chromium 渲染。
 * <p>
 * 安全要点：
 * <ul>
 *   <li>导航前对目标 URL 做一次 SSRF 准入；</li>
 *   <li>通过 {@code page.route} 对「每一个」请求（含重定向后的请求）做 SSRF 复核，命中内网立即 abort，防重定向逃逸；</li>
 *   <li>屏蔽 image / media / font 子资源以加速并降低内存占用；</li>
 *   <li>对返回正文做体积上限，防 OOM。</li>
 * </ul>
 */
@Component
public class PlaywrightRenderFetcher {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightRenderFetcher.class);

    private static final int NAV_TIMEOUT_MS = 15000;
    private static final int MAX_CONTENT_CHARS = 5_000_000; // ~5MB 字符上限
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final SsrfProtectionValidator ssrfValidator;
    private Playwright playwright;
    private Browser browser;

    public PlaywrightRenderFetcher(SsrfProtectionValidator ssrfValidator) {
        this.ssrfValidator = ssrfValidator;
    }

    @PostConstruct
    public void init() {
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage")));
        log.info("PlaywrightRenderFetcher 无头浏览器已初始化");
    }

    /**
     * 渲染并抓取目标页面正文。
     *
     * @param targetUrl 目标链接
     * @return 渲染后的完整 HTML 文本（已限制体积）
     */
    public String renderAndFetch(String targetUrl) {
        ssrfValidator.validateUrl(targetUrl); // 入口准入

        Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                .setUserAgent(USER_AGENT)
                .setViewportSize(1280, 800);
        try (Page page = browser.newContext(ctxOpts).newPage()) {
            // 路由级防护：每个请求（含重定向后）都做 SSRF 复核；资源类直接 abort 降耗
            page.route("**/*", (Route route) -> {
                String reqUrl = route.request().url();
                try {
                    ssrfValidator.validateUrl(reqUrl);
                } catch (SecurityException se) {
                    log.warn("Playwright 路由拦截内网/非法请求: {}", reqUrl);
                    route.abort();
                    return;
                }
                String type = route.request().resourceType();
                if ("image".equals(type) || "media".equals(type) || "font".equals(type)) {
                    route.abort();
                } else {
                    route.resume();
                }
            });
            page.onDialog(Dialog::dismiss); // 自动关闭弹窗，避免渲染挂起

            page.navigate(targetUrl, new Page.NavigateOptions()
                    .setTimeout(NAV_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(1000); // 等待客户端渲染主文本块

            String content = page.content();
            if (content != null && content.length() > MAX_CONTENT_CHARS) {
                throw new IllegalStateException(
                        "渲染正文超出安全上限 (" + MAX_CONTENT_CHARS + " 字符)，已中止（可能遭遇超大页面/注入）");
            }
            return content;
        }
    }

    @PreDestroy
    public void destroy() {
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception e) {
                log.warn("关闭 Playwright browser 失败", e);
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception e) {
                log.warn("关闭 Playwright 实例失败", e);
            }
        }
    }
}
