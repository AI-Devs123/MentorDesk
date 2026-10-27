package com.nexusmind.agent.curator.chunker;

import org.springframework.stereotype.Component;

/**
 * Token 数量估算器（Java 侧没有 bge-m3 / cl100k 的 tokenizer，只能近似）。
 * <p>
 * 换算依据（经验值，用于分块窗口控制而非计费）：
 * <ul>
 *   <li>中日韩字符：约 <b>1.5 字符 / token</b>（中文字大多一个字符一个 token 上下）；</li>
 *   <li>拉丁字母 / 数字 / 常见符号：约 <b>4 字符 / token</b>；</li>
 *   <li>代码片段符号密度高，按 3 字符 / token 粗估。</li>
 * </ul>
 * 把换算集中在这里，是为了避免「注释说 tokens、变量名说 chars」导致的调参混乱：
 * 上层一律用 {@link #estimate(String)} 得到 token 数，再由 {@link #charsForTokens(int)} 反算字符预算。
 */
@Component
public class TokenEstimator {

    /** 中文/日文/韩文单字对应的字符数。 */
    private static final double CJK_CHARS_PER_TOKEN = 1.5;

    /** 拉丁字母等常规字符对应的字符数。 */
    private static final double LATIN_CHARS_PER_TOKEN = 4.0;

    /**
     * 估算文本的 token 数。
     * <p>
     * 分别统计 CJK 与其余字符，再按各自换算率折算后相加，比单一比例准确得多
     * （中英混排的技术文章单用 4 字符/token 会严重低估）。
     */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        double tokens = cjk / CJK_CHARS_PER_TOKEN + other / LATIN_CHARS_PER_TOKEN;
        return (int) Math.ceil(tokens);
    }

    /**
     * 把 token 预算反算成字符预算（保守取两种字符类型中较大的一方，避免超窗）。
     * <p>
     * 例如 800 tokens → 中文约 1200 字符 / 英文约 3200 字符；
     * 为了让中文内容不超过 token 上限，这里返回按 CJK 口径算出的字符数。
     */
    public int charsForTokens(int tokens) {
        return (int) Math.floor(tokens * CJK_CHARS_PER_TOKEN);
    }

    private boolean isCjk(char c) {
        // 中日韩统一表意文字 + 扩展A + 兼容表意文字 + 假名 + 谚文
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\uF900' && c <= '\uFAFF')
                || (c >= '\u3040' && c <= '\u30FF')
                || (c >= '\uAC00' && c <= '\uD7AF');
    }
}
