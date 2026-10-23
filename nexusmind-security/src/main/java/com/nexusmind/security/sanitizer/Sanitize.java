package com.nexusmind.security.sanitizer;

import java.lang.annotation.*;

/**
 * 敏感数据脱敏注解
 * 
 * 在方法上标注此注解，所有 String 类型参数在方法执行前会自动进行敏感数据脱敏
 * 
 * 使用场景：
 * 1. 日志记录方法
 * 2. 数据库保存方法
 * 3. 外部 API 调用方法
 * 4. 缓存写入方法
 * 
 * 示例：
 * @Sanitize
 * public void saveLog(String logContent) {
 *     // logContent 中的敏感信息会被自动脱敏
 *     database.save(logContent);
 * }
 * 
 * @Sanitize
 * public void sendAlert(String alertMessage) {
 *     // alertMessage 会在发送前脱敏
 *     notificationService.send(alertMessage);
 * }
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Sanitize {
    
    /**
     * 是否对返回值也进行脱敏
     * 
     * 默认 false - 仅脱敏参数
     * 设置为 true 时，如果返回值是 String，也会进行脱敏
     */
    boolean sanitizeReturnValue() default false;
    
    /**
     * 是否记录脱敏操作到审计日志
     * 
     * 默认 true - 所有脱敏操作都会被记录
     * 用于追踪哪些敏感信息被处理过
     */
    boolean auditLog() default true;
    
    /**
     * 脱敏操作的描述
     * 
     * 用于在日志中表示这个操作的目的，例如 "日志保存" 或 "邮件发送"
     */
    String description() default "";
}
