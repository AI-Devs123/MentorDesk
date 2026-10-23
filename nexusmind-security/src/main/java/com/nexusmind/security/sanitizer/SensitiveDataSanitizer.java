package com.nexusmind.security.sanitizer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏处理器
 * 
 * 在流式数据推送到前端或存入数据库之前，检测和脱敏以下敏感信息：
 * - API Keys (OpenAI, Anthropic, GitHub 等)
 * - JWT Tokens
 * - 密码和密钥
 * - 数据库连接字符串
 * - AWS/阿里云等云服务凭证
 * 
 * 使用方式：
 * 1. 在 SSE 流推送前调用：content = sanitizer.sanitize(content)
 * 2. 在数据库存储前调用：sanitizer.sanitize(logContent)
 * 3. 在日志记录前调用：sanitizer.sanitize(logMessage)
 */
@Slf4j
@Component
public class SensitiveDataSanitizer {
    
    /**
     * OpenAI API Key 匹配器
     * 格式: sk-xxx... (20+ 字符)
     * 示例: sk-proj-abc123def456...
     */
    private static final Pattern OPENAI_KEY_PATTERN = 
        Pattern.compile("sk-[a-zA-Z0-9\\-]{20,}");
    
    /**
     * Anthropic Claude API Key 匹配器
     * 格式: sk-ant-xxx
     */
    private static final Pattern ANTHROPIC_KEY_PATTERN = 
        Pattern.compile("sk-ant-[a-zA-Z0-9\\-]{16,}");
    
    /**
     * GitHub Token 匹配器
     * 格式: ghp_xxx 或 ghu_xxx 等
     */
    private static final Pattern GITHUB_TOKEN_PATTERN = 
        Pattern.compile("gh[pousr]{1,2}_[a-zA-Z0-9]{36,}");
    
    /**
     * 通用 API Key 匹配器
     * 匹配格式: api_key = "xxx", secret: 'xxx', token="xxx" 等
     */
    private static final Pattern GENERIC_API_KEY_PATTERN = 
        Pattern.compile(
            "(?i)(api[_\\-]?key|secret|token|password|passwd|pwd)\\s*[=:]\\s*[\"']?([a-zA-Z0-9_\\-\\.]{8,})[\"']?",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
        );
    
    /**
     * JWT Token 匹配器
     * 格式: eyJ...eyJ...xxx (三段)
     */
    private static final Pattern JWT_PATTERN = 
        Pattern.compile("eyJ[a-zA-Z0-9\\-_]+\\.eyJ[a-zA-Z0-9\\-_]+\\.[a-zA-Z0-9\\-_]+");
    
    /**
     * AWS AccessKeyId 匹配器
     * 格式: AKIA... (20 字符)
     */
    private static final Pattern AWS_ACCESS_KEY_PATTERN = 
        Pattern.compile("AKIA[0-9A-Z]{16}");
    
    /**
     * AWS SecretAccessKey 匹配器
     * 格式: 40 字符的 base64-like 字符串
     */
    private static final Pattern AWS_SECRET_KEY_PATTERN = 
        Pattern.compile("(?i)(aws[_-]?secret[_-]?access[_-]?key|aws[_-]?secret)[\"']?\\s*[:=]\\s*[\"']?([a-zA-Z0-9/\\+=]{40})[\"']?");
    
    /**
     * 数据库连接字符串匹配器
     * 格式: password=xxx, pwd=xxx
     */
    private static final Pattern DB_PASSWORD_PATTERN = 
        Pattern.compile("(?i)(password|pwd|passwd)[\"']?\\s*=\\s*[\"']?([^\"'\\s;,]+)[\"']?");
    
    /**
     * 阿里云 AccessKeySecret 匹配器
     */
    private static final Pattern ALIYUN_SECRET_PATTERN = 
        Pattern.compile("(?i)(aliyun[_-]?secret|alibaba[_-]?secret)[\"']?\\s*[:=]\\s*[\"']?([a-zA-Z0-9/\\+=]{30,})[\"']?");
    
    /**
     * 信用卡号匹配器 (简单版)
     * 格式: 4111 1111 1111 1111 或连续 16 位数字
     */
    private static final Pattern CREDIT_CARD_PATTERN = 
        Pattern.compile("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b|\\b\\d{16}\\b");
    
    /**
     * 电话号码匹配器 (中国 11 位)
     * 格式: 13800138000 或 138-0013-8000 等
     */
    private static final Pattern PHONE_PATTERN = 
        Pattern.compile("\\b1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4}\\b");
    
    /**
     * 身份证号匹配器 (简化版)
     * 格式: 18 位或 15 位数字
     */
    private static final Pattern ID_CARD_PATTERN = 
        Pattern.compile("\\b\\d{15}(\\d{2}[0-9X])?\\b");
    
    /**
     * 邮箱地址匹配器
     * 用于部分脱敏（保留开头和结尾）
     */
    private static final Pattern EMAIL_PATTERN = 
        Pattern.compile("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b");
    
    /**
     * Bearer Token 匹配器 (HTTP Authorization)
     */
    private static final Pattern BEARER_TOKEN_PATTERN = 
        Pattern.compile("(?i)(bearer|basic|token)\\s+([a-zA-Z0-9\\-._~+/]+=*)");
    
    /**
     * 脱敏内容
     * 
     * @param content 原始文本内容
     * @return 脱敏后的文本内容
     */
    public String sanitize(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        
        try {
            String result = content;
            
            // 1. 脱敏 API Keys (最高优先级)
            result = sanitizeOpenAIKey(result);
            result = sanitizeAnthropicKey(result);
            result = sanitizeGitHubToken(result);
            
            // 2. 脱敏密钥和凭证
            result = sanitizeJWT(result);
            result = sanitizeAWSCredentials(result);
            result = sanitizeAliyunCredentials(result);
            
            // 3. 脱敏通用密钥
            result = sanitizeGenericSecret(result);
            result = sanitizeDBPassword(result);
            result = sanitizeBearerToken(result);
            
            // 4. 脱敏个人身份信息 (PII)
            result = sanitizeCreditCard(result);
            result = sanitizePhoneNumber(result);
            result = sanitizeIDCard(result);
            result = sanitizeEmail(result);
            
            // 记录脱敏操作（如果有变化）
            if (!result.equals(content)) {
                log.debug("敏感数据已脱敏 - 原长度: {}, 脱敏后长度: {}", 
                    content.length(), result.length());
            }
            
            return result;
        } catch (Exception e) {
            log.error("敏感数据脱敏异常", e);
            // 发生异常时返回原文本（保持可用性）
            return content;
        }
    }
    
    /**
     * 脱敏 OpenAI API Key
     */
    private String sanitizeOpenAIKey(String content) {
        Matcher matcher = OPENAI_KEY_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            // 保留前 5 个字符和后 4 个字符
            String masked = original.substring(0, Math.min(5, original.length())) + 
                           "***[REDACTED_OPENAI_KEY]***" +
                           (original.length() > 4 ? original.substring(original.length() - 4) : "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏 Anthropic Claude API Key
     */
    private String sanitizeAnthropicKey(String content) {
        Matcher matcher = ANTHROPIC_KEY_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String masked = "sk-ant-***[REDACTED_ANTHROPIC_KEY]***";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏 GitHub Token
     */
    private String sanitizeGitHubToken(String content) {
        Matcher matcher = GITHUB_TOKEN_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            // 保留前缀 (3-4 个字符)
            String prefix = original.substring(0, Math.min(4, original.length()));
            String masked = prefix + "***[REDACTED_GITHUB_TOKEN]***";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏 JWT Token
     */
    private String sanitizeJWT(String content) {
        Matcher matcher = JWT_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String masked = "eyJ***[REDACTED_JWT]***";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏 AWS 凭证
     */
    private String sanitizeAWSCredentials(String content) {
        // 脱敏 Access Key ID
        Matcher accessKeyMatcher = AWS_ACCESS_KEY_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (accessKeyMatcher.find()) {
            String original = accessKeyMatcher.group();
            String masked = original.substring(0, 4) + "***[REDACTED_AWS_KEY]***";
            accessKeyMatcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        accessKeyMatcher.appendTail(sb);
        content = sb.toString();
        
        // 脱敏 Secret Access Key
        Matcher secretKeyMatcher = AWS_SECRET_KEY_PATTERN.matcher(content);
        sb = new StringBuffer();
        while (secretKeyMatcher.find()) {
            String masked = "aws_secret_access_key: ***[REDACTED_AWS_SECRET]***";
            secretKeyMatcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        secretKeyMatcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏阿里云凭证
     */
    private String sanitizeAliyunCredentials(String content) {
        Matcher matcher = ALIYUN_SECRET_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String masked = "aliyun_secret: ***[REDACTED_ALIYUN_SECRET]***";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏通用密钥 (api_key, secret, token, password 等)
     */
    private String sanitizeGenericSecret(String content) {
        Matcher matcher = GENERIC_API_KEY_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String keyName = matcher.group(1);
            String masked = keyName + ": ***[REDACTED]***";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏数据库密码
     */
    private String sanitizeDBPassword(String content) {
        Matcher matcher = DB_PASSWORD_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String keyName = matcher.group(1);
            String masked = keyName + "=***[REDACTED_DB_PASSWORD]***";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏 Bearer Token (HTTP Authorization 头)
     */
    private String sanitizeBearerToken(String content) {
        Matcher matcher = BEARER_TOKEN_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String authType = matcher.group(1);
            String masked = authType + " ***[REDACTED_AUTH_TOKEN]***";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏信用卡号
     */
    private String sanitizeCreditCard(String content) {
        Matcher matcher = CREDIT_CARD_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            // 只保留最后 4 位
            String last4 = original.replaceAll("[^0-9]", "");
            if (last4.length() >= 4) {
                last4 = last4.substring(last4.length() - 4);
            }
            String masked = "****-****-****-" + last4;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏电话号码
     */
    private String sanitizePhoneNumber(String content) {
        Matcher matcher = PHONE_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String digits = original.replaceAll("[^0-9]", "");
            if (digits.length() >= 11) {
                // 格式: 138****8000
                String masked = digits.substring(0, 3) + "****" + digits.substring(7);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏身份证号
     */
    private String sanitizeIDCard(String content) {
        Matcher matcher = ID_CARD_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            if (original.length() >= 8) {
                // 只保留前 6 位和后 2 位
                String masked = original.substring(0, 6) + "****" + original.substring(original.length() - 2);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 脱敏邮箱地址 (部分脱敏)
     */
    private String sanitizeEmail(String content) {
        Matcher matcher = EMAIL_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String email = matcher.group();
            // 格式: u***@example.com
            int atIndex = email.indexOf('@');
            if (atIndex > 1) {
                String localPart = email.substring(0, 1) + "***";
                String domain = email.substring(atIndex);
                String masked = localPart + domain;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 获取脱敏统计信息（用于审计和监控）
     */
    public SanitizationStats getStatistics(String original, String sanitized) {
        SanitizationStats stats = new SanitizationStats();
        stats.setOriginalLength(original != null ? original.length() : 0);
        stats.setSanitizedLength(sanitized != null ? sanitized.length() : 0);
        stats.setWasSanitized(!original.equals(sanitized));
        
        // 统计各类脱敏项
        if (OPENAI_KEY_PATTERN.matcher(original).find()) {
            stats.setFoundOpenAIKeys(true);
        }
        if (JWT_PATTERN.matcher(original).find()) {
            stats.setFoundJWTTokens(true);
        }
        if (AWS_ACCESS_KEY_PATTERN.matcher(original).find()) {
            stats.setFoundAWSKeys(true);
        }
        if (CREDIT_CARD_PATTERN.matcher(original).find()) {
            stats.setFoundCreditCards(true);
        }
        if (PHONE_PATTERN.matcher(original).find()) {
            stats.setFoundPhoneNumbers(true);
        }
        
        return stats;
    }
    
    /**
     * 脱敏统计信息
     */
    public static class SanitizationStats {
        private int originalLength;
        private int sanitizedLength;
        private boolean wasSanitized;
        private boolean foundOpenAIKeys;
        private boolean foundJWTTokens;
        private boolean foundAWSKeys;
        private boolean foundCreditCards;
        private boolean foundPhoneNumbers;
        
        // Getters and Setters
        public int getOriginalLength() { return originalLength; }
        public void setOriginalLength(int originalLength) { this.originalLength = originalLength; }
        
        public int getSanitizedLength() { return sanitizedLength; }
        public void setSanitizedLength(int sanitizedLength) { this.sanitizedLength = sanitizedLength; }
        
        public boolean isWasSanitized() { return wasSanitized; }
        public void setWasSanitized(boolean wasSanitized) { this.wasSanitized = wasSanitized; }
        
        public boolean isFoundOpenAIKeys() { return foundOpenAIKeys; }
        public void setFoundOpenAIKeys(boolean foundOpenAIKeys) { this.foundOpenAIKeys = foundOpenAIKeys; }
        
        public boolean isFoundJWTTokens() { return foundJWTTokens; }
        public void setFoundJWTTokens(boolean foundJWTTokens) { this.foundJWTTokens = foundJWTTokens; }
        
        public boolean isFoundAWSKeys() { return foundAWSKeys; }
        public void setFoundAWSKeys(boolean foundAWSKeys) { this.foundAWSKeys = foundAWSKeys; }
        
        public boolean isFoundCreditCards() { return foundCreditCards; }
        public void setFoundCreditCards(boolean foundCreditCards) { this.foundCreditCards = foundCreditCards; }
        
        public boolean isFoundPhoneNumbers() { return foundPhoneNumbers; }
        public void setFoundPhoneNumbers(boolean foundPhoneNumbers) { this.foundPhoneNumbers = foundPhoneNumbers; }
    }
}
