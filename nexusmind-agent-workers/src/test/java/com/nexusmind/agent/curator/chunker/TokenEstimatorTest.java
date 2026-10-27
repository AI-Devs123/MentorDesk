package com.nexusmind.agent.curator.chunker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Token 估算器测试：验证中英混排的估算与反算。
 */
class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    @DisplayName("纯中文按 1.5 字符/token 估算")
    void shouldEstimatePureChinese() {
        String chinese = "协议帧结构分析";  // 7 个汉字
        int tokens = estimator.estimate(chinese);
        // 7 / 1.5 ≈ 4.67 → 向上取整 5
        org.junit.jupiter.api.Assertions.assertEquals(5, tokens);
    }

    @Test
    @DisplayName("纯英文按 4 字符/token 估算")
    void shouldEstimatePureEnglish() {
        String english = "abcdefgh";  // 8 个字符
        org.junit.jupiter.api.Assertions.assertEquals(2, estimator.estimate(english));
    }

    @Test
    @DisplayName("中英混排综合估算，不应严重低估")
    void shouldEstimateMixedText() {
        String mixed = "Dubbo 3 Triple 协议深度剖析";  // 约 16 拉丁 + 6 中文
        int tokens = estimator.estimate(mixed);
        org.junit.jupiter.api.Assertions.assertTrue(tokens >= 6 && tokens <= 16,
                "混排估算应落在合理区间, 实际: " + tokens);
    }

    @Test
    @DisplayName("charsForTokens 按中文保守口径反算")
    void shouldConvertTokensToChars() {
        // 800 tokens → 1200 字符（中文口径，保守）
        org.junit.jupiter.api.Assertions.assertEquals(1200, estimator.charsForTokens(800));
        org.junit.jupiter.api.Assertions.assertEquals(150, estimator.charsForTokens(100));
    }

    @Test
    @DisplayName("空值返回 0")
    void shouldReturnZeroForEmpty() {
        org.junit.jupiter.api.Assertions.assertEquals(0, estimator.estimate(null));
        org.junit.jupiter.api.Assertions.assertEquals(0, estimator.estimate(""));
    }
}
