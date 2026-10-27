package com.nexusmind.agent.curator.cleaner;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * URL 脱水器：剥离追踪参数，剔除危险协议，正确保留 path / 端口 / fragment。
 * <p>
 * 修正了初版 {@code getScheme()+"://"+getAuthority()+getPath()} 拼串写法的四个缺陷：
 * <ol>
 *   <li><b>fragment 被丢弃</b>——{@code #section-2} 这类锚点是文档定位的关键，必须保留；</li>
 *   <li><b>相对链接被打碎</b>——用 {@code new URI(scheme, authority, path, query, fragment)}
 *       五参构造器重组，而非字符串拼接；</li>
 *   <li><b>通用词误删</b>——{@code ref} / {@code source} / {@code from} 是常见业务参数，
 *       不再一刀切，只删带明确追踪语义的键（{@code utm_*} / {@code spm} / {@code gclid} 等）；</li>
 *   <li><b>空查询残留</b>——清洗后 query 变空时把 {@code ?} 也去掉。</li>
 * </ol>
 */
@Component
public class UrlSanitizer {

    /** 精确匹配即删除的追踪参数键（小写）。 */
    private static final Set<String> TRACKING_KEYS_EXACT = new HashSet<>(Arrays.asList(
            "gclid", "fbclid", "msclkid", "yclid", "dclid", "igshid", "mc_cid", "mc_eid",
            "spm", "scm", "_t", "share_token", "share_source", "from_source",
            "ref_src", "ref_url", "__biz", "scene", "clicktime"
    ));

    /** 前缀匹配即删除的追踪参数键（如 utm_source / utm_medium / utm_campaign）。 */
    private static final String[] TRACKING_KEY_PREFIXES = {"utm_", "hmsr", "hmpl", "hmcu", "hmkw", "hmci"};

    /**
     * 清洗单个 URL。无法解析时原样返回，不抛异常（抓取管道不应因单个坏链接中断）。
     *
     * @param rawUrl  原始地址
     * @param baseUri 基准地址，用于补全相对链接；为空则不做补全
     * @return 清洗后的绝对地址；入参为空时返回空串
     */
    public String sanitize(String rawUrl, String baseUri) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String trimmed = rawUrl.trim();
        if (isDangerous(trimmed)) {
            return "";
        }
        URI uri = toUri(trimmed, baseUri);
        if (uri == null) {
            // 解析失败时保守返回原文（已排除危险协议），避免整篇文档因一条坏链失败
            return trimmed;
        }
        return rebuild(uri);
    }

    /** 把原始地址解析为绝对 URI：优先自身，失败则按 baseUri 补全。 */
    private URI toUri(String url, String baseUri) {
        try {
            URI parsed = new URI(url);
            if (parsed.isAbsolute()) {
                return parsed;
            }
            if (baseUri != null && !baseUri.isBlank()) {
                URI base = new URI(baseUri);
                return base.resolve(parsed);
            }
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    /** 依据清洗后的 query 重建 URI，保留 scheme / authority / path / fragment。 */
    private String rebuild(URI uri) {
        String cleanQuery = cleanQuery(uri.getRawQuery());
        try {
            return new URI(
                    uri.getScheme(),
                    uri.getRawAuthority(),
                    uri.getRawPath(),
                    cleanQuery,
                    uri.getRawFragment()
            ).toASCIIString();
        } catch (Exception e) {
            // 极端情况（非法字符）回退到原始字符串的查询串替换
            return uri.toString();
        }
    }

    /**
     * 过滤追踪参数，保持其余参数原有顺序并去重。
     */
    String cleanQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase();
            if (!isTrackingKey(key)) {
                kept.add(pair);
            }
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private boolean isTrackingKey(String lowerKey) {
        if (TRACKING_KEYS_EXACT.contains(lowerKey)) {
            return true;
        }
        for (String prefix : TRACKING_KEY_PREFIXES) {
            if (lowerKey.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 危险协议判定：javascript / vbscript / 非图片 data。 */
    public boolean isDangerous(String url) {
        String v = url.replaceAll("[\\s\\u0000-\\u001f]", "").toLowerCase();
        if (v.startsWith("javascript:") || v.startsWith("vbscript:")) {
            return true;
        }
        return v.startsWith("data:") && !v.startsWith("data:image/");
    }
}
