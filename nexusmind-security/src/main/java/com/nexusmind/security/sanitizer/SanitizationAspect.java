package com.nexusmind.security.sanitizer;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 敏感数据脱敏 AOP 切面
 * 
 * 在以下场景自动脱敏敏感数据：
 * 1. SSE 流推送到前端 (AgentStreamResponse)
 * 2. 数据写入数据库
 * 3. 日志记录时
 * 
 * 非侵入式设计 - 无需修改业务代码，通过注解和切面自动处理
 */
@Slf4j
@Aspect
@Component
public class SanitizationAspect {
    
    @Autowired(required = false)
    private SensitiveDataSanitizer sanitizer;
    
    /**
     * 切点1: 拦截所有 StreamObserver.onNext() 调用
     * 用于在 SSE 推流前脱敏 AgentStreamResponse
     */
    @Pointcut("execution(void org.apache.dubbo.common.stream.StreamObserver.onNext(*))")
    public void streamObserverOnNext() {
    }
    
    /**
     * 切点2: 拦截所有带 @Sanitize 注解的方法
     * 用于手动指定脱敏的业务方法
     */
    @Pointcut("@annotation(com.nexusmind.security.sanitizer.Sanitize)")
    public void sanitizeAnnotated() {
    }
    
    /**
     * 在 StreamObserver.onNext() 前脱敏响应数据
     * 
     * 处理流程：
     * 1. 获取 onNext 的参数 (AgentStreamResponse)
     * 2. 如果是 AgentStreamResponse，脱敏其 deltaChunk
     * 3. 执行原方法
     * 4. 记录脱敏操作
     */
    @Around("streamObserverOnNext()")
    public Object sanitizeStreamResponse(ProceedingJoinPoint joinPoint) throws Throwable {
        if (sanitizer == null) {
            log.warn("SensitiveDataSanitizer bean not found, skipping sanitization");
            return joinPoint.proceed();
        }
        
        Object[] args = joinPoint.getArgs();
        
        try {
            // 尝试脱敏 AgentStreamResponse
            if (args.length > 0) {
                Object response = args[0];
                
                if (response != null && response.getClass().getSimpleName().equals("AgentStreamResponse")) {
                    // 通过反射获取 deltaChunk (避免硬依赖 protobuf 生成的类)
                    try {
                        String deltaChunk = (String) response.getClass()
                            .getMethod("getDeltaChunk")
                            .invoke(response);
                        
                        if (deltaChunk != null && !deltaChunk.isEmpty()) {
                            String sanitized = sanitizer.sanitize(deltaChunk);
                            
                            // 如果内容被修改，设置回去
                            if (!sanitized.equals(deltaChunk)) {
                                // AgentStreamResponse 是不可变的，需要通过 toBuilder() 重建
                                log.debug("SSE 流响应已脱敏 - 原长度: {}, 脱敏后长度: {}", 
                                    deltaChunk.length(), sanitized.length());
                                
                                // 对于 protobuf message，使用 toBuilder 重建
                                Object builder = response.getClass().getMethod("toBuilder").invoke(response);
                                builder.getClass().getMethod("setDeltaChunk", String.class).invoke(builder, sanitized);
                                args[0] = builder.getClass().getMethod("build").invoke(builder);
                            }
                        }
                    } catch (NoSuchMethodException e) {
                        log.debug("AgentStreamResponse 不包含 getDeltaChunk 方法，跳过脱敏");
                    }
                }
            }
        } catch (Exception e) {
            log.error("SSE 流数据脱敏异常，继续执行原方法", e);
            // 脱敏失败时不中断流程，继续推流
        }
        
        return joinPoint.proceed(args);
    }
    
    /**
     * 处理 @Sanitize 注解的方法
     * 
     * 使用方式：
     * @Sanitize
     * public void saveLog(String logContent) {
     *     // 此方法的参数会自动脱敏
     * }
     */
    @Around("sanitizeAnnotated()")
    public Object sanitizeAnnotatedMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        if (sanitizer == null) {
            return joinPoint.proceed();
        }
        
        Object[] args = joinPoint.getArgs();
        
        try {
            // 脱敏所有 String 类型的参数
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof String) {
                    String original = (String) args[i];
                    String sanitized = sanitizer.sanitize(original);
                    
                    if (!sanitized.equals(original)) {
                        log.debug("@Sanitize 注解方法参数已脱敏: {}", joinPoint.getSignature().getName());
                        args[i] = sanitized;
                    }
                }
            }
        } catch (Exception e) {
            log.error("@Sanitize 注解脱敏异常", e);
        }
        
        return joinPoint.proceed(args);
    }
}
