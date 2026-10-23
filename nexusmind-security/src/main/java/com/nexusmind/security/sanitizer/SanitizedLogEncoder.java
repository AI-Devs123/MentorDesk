package com.nexusmind.security.sanitizer;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Logback 日志编码器 - 自动脱敏日志输出中的敏感信息
 * 
 * 配置方式 (logback-spring.xml)：
 * <encoder>
 *     <pattern>
 *         %d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
 *     </pattern>
 *     <charset>UTF-8</charset>
 * </encoder>
 * 
 * 与 SensitiveDataSanitizer 配合使用，在日志写入磁盘前进行脱敏
 */
@Slf4j
public class SanitizedLogEncoder extends ClassicConverter {
    
    private static SensitiveDataSanitizer sanitizer;
    
    /**
     * 注入 Sanitizer (通过 Spring 上下文)
     */
    public static void setSanitizer(SensitiveDataSanitizer sanitizerBean) {
        sanitizer = sanitizerBean;
    }
    
    /**
     * 将日志消息转换为脱敏后的字符串
     */
    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        
        if (sanitizer != null && message != null) {
            try {
                return sanitizer.sanitize(message);
            } catch (Exception e) {
                // 脱敏失败时返回原日志（不中断日志记录）
                log.warn("日志脱敏失败，使用原文本", e);
                return message;
            }
        }
        
        return message;
    }
}
