package com.nexusmind.agent.curator.fetcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 双通道聚合抓取入口。
 * <p>
 * 1) {@code forceRender=true} 直接走 Playwright 渲染通道；
 * 2) 否则先走 Fast Path；若命中 SPA 空壳（含挂载点且正文过短）或发生非安全类异常，自动降级到 Playwright。
 * <p>
 * 关键约束：安全类异常（{@link SecurityException}）绝不降级绕过，直接向上抛出。
 */
@Service
public class HybridWebFetcher {

    private static final Logger log = LoggerFactory.getLogger(HybridWebFetcher.class);

    private final FastHttpFetcher fastHttpFetcher;
    private final PlaywrightRenderFetcher playwrightFetcher;

    public HybridWebFetcher(FastHttpFetcher fastHttpFetcher, PlaywrightRenderFetcher playwrightFetcher) {
        this.fastHttpFetcher = fastHttpFetcher;
        this.playwrightFetcher = playwrightFetcher;
    }

    /**
     * 抓取网页并输出原始 HTML。
     *
     * @param targetUrl   目标链接
     * @param forceRender 是否强制走无头浏览器渲染
     * @return 原始 HTML 文本
     */
    public String fetch(String targetUrl, boolean forceRender) throws Exception {
        if (forceRender) {
            log.info("触发动态渲染通道抓取: {}", targetUrl);
            return playwrightFetcher.renderAndFetch(targetUrl);
        }
        try {
            String html = fastHttpFetcher.fetchHtml(targetUrl);
            if (isSpaFallbackRequired(html)) {
                log.warn("检测到疑似 SPA 空页面，自动降级至 Playwright 渲染: {}", targetUrl);
                return playwrightFetcher.renderAndFetch(targetUrl);
            }
            return html;
        } catch (SecurityException se) {
            // 安全拦截不得降级绕过，直接抛出
            throw se;
        } catch (Exception e) {
            log.warn("快速通道抓取异常，尝试无头浏览器降级: {}", e.getMessage());
            return playwrightFetcher.renderAndFetch(targetUrl);
        }
    }

    /**
     * 启发式判断是否需降级到无头浏览器：含典型 SPA 挂载点且正文体积过小（< 5KB），主内容大概率在客户端异步渲染。
     */
    private boolean isSpaFallbackRequired(String html) {
        if (html == null) {
            return true;
        }
        boolean hasRootApp = html.contains("id=\"app\"")
                || html.contains("id=\"root\"")
                || html.contains("__NEXT_DATA__")
                || html.contains("id=\"__next\"");
        return hasRootApp && html.length() < 5000;
    }
}
