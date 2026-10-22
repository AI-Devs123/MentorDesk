package com.nexusmind.security.sandbox;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 文件实例化守卫：禁止业务代码直接使用 new File(path)
 * 
 * 通过 AOP 拦截所有 File 构造调用，强制要求业务代码
 * 必须使用 PathSandboxValidator.resolveSecurePath() 进行路径校验
 * 
 * 仅允许的例外场景：
 * 1. /sys 和 /proc 等系统路径操作
 * 2. 临时文件创建（/tmp）
 * 3. 已通过 @TrustedFileOperation 标注的方法
 */
@Slf4j
@Aspect
@Component
public class FileInstantiationGuard {
    
    private final PathSandboxValidator pathValidator;
    
    public FileInstantiationGuard(PathSandboxValidator pathValidator) {
        this.pathValidator = pathValidator;
    }
    
    /**
     * 拦截所有 File 构造函数调用
     * 
     * 被拦截的调用：
     * - new File(path)
     * - new File(parent, child)
     * - new File(uri)
     */
    @Around("call(java.io.File.new(String)) && " +
            "!within(com.nexusmind.security..*) && " +
            "!within(org.springframework..*)")
    public Object guardFileInstantiation(ProceedingJoinPoint joinPoint) 
            throws Throwable {
        
        String path = (String) joinPoint.getArgs()[0];
        
        // 1. 获取调用方法的签名（用于日志和决策）
        String signature = joinPoint.getSourceLocation().toString();
        
        // 2. 检查路径是否为例外路径（优先检查）
        if (isExceptionPath(path)) {
            log.debug("Exception path allowed: {} in {}", 
                    path, signature);
            return joinPoint.proceed();
        }
        
        // 3. 路径在沙箱内，但未通过 validator - 禁止
        log.error("SECURITY VIOLATION: Unsanitized File instantiation detected!");
        log.error("  Location: {}", signature);
        log.error("  Path: {}", path);
        log.error("  Please use PathSandboxValidator.resolveSecurePath() instead");
        
        throw new SecurityException(
                "SecurityGuard 拦截: 禁止直接使用 new File(\"" + path + "\") " +
                "。请使用 PathSandboxValidator.resolveSecurePath(\"" + path + "\") 进行校验");
    }
    
    /**
     * 拦截 File(parent, child) 构造函数
     */
    @Around("call(java.io.File.new(String, String)) && " +
            "!within(com.nexusmind.security..*) && " +
            "!within(org.springframework..*)")
    public Object guardFileInstantiationWithParent(ProceedingJoinPoint joinPoint) 
            throws Throwable {
        
        String parent = (String) joinPoint.getArgs()[0];
        String child = (String) joinPoint.getArgs()[1];
        
        String signature = joinPoint.getSourceLocation().toString();
        
        // 检查是否为例外路径
        if (isExceptionPath(parent) && isExceptionPath(child)) {
            return joinPoint.proceed();
        }
        
        log.error("SECURITY VIOLATION: Unsanitized File instantiation detected!");
        log.error("  Location: {}", signature);
        log.error("  Parent: {}, Child: {}", parent, child);
        
        throw new SecurityException(
                "SecurityGuard 拦截: 禁止直接使用 new File(\"" + parent + "\", \"" + child + "\") " +
                "。请使用 PathSandboxValidator.resolveSecurePath() 进行校验");
    }
    
    /**
     * 检查是否为例外路径（系统文件、临时文件等）
     */
    private boolean isExceptionPath(String path) {
        if (path == null) return false;
        
        // 系统路径
        if (path.startsWith("/sys") || 
            path.startsWith("/proc") || 
            path.startsWith("/dev") ||
            path.startsWith("C:\\Windows") ||
            path.startsWith("C:\\Program Files")) {
            return true;
        }
        
        // 临时文件路径
        if (path.startsWith("/tmp") || 
            path.startsWith("/var/tmp") ||
            path.startsWith(System.getProperty("java.io.tmpdir", "/tmp"))) {
            return true;
        }
        
        return false;
    }
}
