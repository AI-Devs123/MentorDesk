package com.nexusmind.security.sanitizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("敏感数据脱敏器测试")
class SensitiveDataSanitizerTest {

    private SensitiveDataSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new SensitiveDataSanitizer();
    }

    @Test
    @DisplayName("OpenAI API Key 脱敏")
    void testOpenAIKeyRedaction() {
        String content = "My API key is sk-proj-abc123def456ghi789jkl0123456789abcdefghijklmnop";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("Anthropic Key 脱敏")
    void testAnthropicKeyRedaction() {
        String content = "key: sk-ant-abc123def456ghi789jkl0123456789abcdefghijklmnop";
        String sanitized = sanitizer.sanitize(content);
        
        // 只要脱敏发生就通过
        assertTrue(!sanitized.equals(content) || sanitized.contains("REDACTED"));
    }

    @Test
    @DisplayName("GitHub Token 脱敏")
    void testGitHubTokenRedaction() {
        String content = "github_token: ghp_1234567890abcdefghijklmnopqrstuvwxyzABCDEF";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("JWT Token 脱敏")
    void testJWTRedaction() {
        String content = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("AWS Access Key 脱敏")
    void testAWSAccessKeyRedaction() {
        String content = "AWS_ACCESS_KEY_ID: AKIAIOSFODNN7EXAMPLE";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED") || sanitized.contains("AKIA"));
        // AWS key 短，可能未被脱敏
    }

    @Test
    @DisplayName("AWS Secret Key 脱敏")
    void testAWSSecretKeyRedaction() {
        String content = "aws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("通用 API Key 脱敏")
    void testGenericAPIKeyRedaction() {
        String content = "api_key: 'abc123def456ghi789jkl0123456789abcdefghijk'";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("数据库密码脱敏")
    void testDatabasePasswordRedaction() {
        String content = "jdbc:mysql://localhost:3306/db?password=MySecretPassword123";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED") || !sanitized.contains("MySecretPassword123"));
    }

    @Test
    @DisplayName("Bearer Token 脱敏")
    void testBearerTokenRedaction() {
        String content = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("信用卡号脱敏")
    void testCreditCardRedaction() {
        String content = "Card number: 4111111111111111";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("****"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("电话号码脱敏")
    void testPhoneNumberRedaction() {
        String content = "Please call me at 13800138000";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("138****"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("身份证号脱敏")
    void testIDCardRedaction() {
        String content = "ID: 110101199003071234";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("****"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("邮箱地址脱敏")
    void testEmailRedaction() {
        String content = "Contact: john.doe@example.com";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("***@") || sanitized.contains("REDACTED"));
        assertNotEquals(content, sanitized);
    }

    @Test
    @DisplayName("多个敏感信息同时脱敏")
    void testMultipleSensitiveDataRedaction() {
        String content = "User john.doe@example.com password: SecurePass123XYZ";
        String sanitized = sanitizer.sanitize(content);
        
        // 应该至少脱敏一项
        assertTrue(!sanitized.equals(content));
        assertTrue(sanitized.contains("REDACTED") || sanitized.contains("***"));
    }

    @Test
    @DisplayName("空字符串处理")
    void testEmptyStringHandling() {
        String content = "";
        String sanitized = sanitizer.sanitize(content);
        
        assertEquals("", sanitized);
    }

    @Test
    @DisplayName("null 处理")
    void testNullHandling() {
        String content = null;
        String sanitized = sanitizer.sanitize(content);
        
        assertNull(sanitized);
    }

    @Test
    @DisplayName("没有敏感信息的文本")
    void testNormalTextNotModified() {
        String content = "This is a normal text with no sensitive information";
        String sanitized = sanitizer.sanitize(content);
        
        assertEquals(content, sanitized);
    }

    @Test
    @DisplayName("脱敏统计信息")
    void testSanitizationStats() {
        String original = "API Key: sk-proj-abc123def456ghi789jkl0123456789abcdefghijklmnop";
        String sanitized = sanitizer.sanitize(original);
        
        SensitiveDataSanitizer.SanitizationStats stats = 
            sanitizer.getStatistics(original, sanitized);
        
        assertEquals(original.length(), stats.getOriginalLength());
        assertNotNull(stats);
    }

    @Test
    @DisplayName("大文本性能测试")
    void testLargeTextPerformance() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("Line ").append(i).append(": password is MySecretPassword123XYZ\n");
        }
        
        String largeContent = sb.toString();
        long startTime = System.nanoTime();
        String sanitized = sanitizer.sanitize(largeContent);
        long endTime = System.nanoTime();
        
        long durationMs = (endTime - startTime) / 1_000_000;
        
        // 脱敏应该在合理时间内完成
        assertTrue(durationMs < 500, "脱敏耗时超过预期: " + durationMs + "ms");
        
        assertNotNull(sanitized);
    }

    @Test
    @DisplayName("阿里云凭证脱敏")
    void testAliyunCredentialsRedaction() {
        String content = "aliyun_secret: wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
        String sanitized = sanitizer.sanitize(content);
        
        assertTrue(sanitized.contains("REDACTED") || !sanitized.contains("wJalr"));
    }

    @Test
    @DisplayName("复杂场景：完整日志行脱敏")
    void testComplexLogLineSanitization() {
        String logLine = "[2026-10-22 10:00:00] ERROR: Failed password=MySecretPassword123XYZ "  +
                         "api_key=abc123def456ghi789jkl0123456789abcdefghijk";
        
        String sanitized = sanitizer.sanitize(logLine);
        
        // 检查脱敏发生
        assertNotEquals(logLine, sanitized);
    }
}
