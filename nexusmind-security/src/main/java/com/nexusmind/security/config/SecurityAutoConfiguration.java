package com.nexusmind.security.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 安全模块自动配置
 * 
 * 启用以下功能：
 * 1. PathSandboxValidator - 路径沙箱校验
 * 2. FileInstantiationGuard - 文件实例化守卫（AOP）
 * 3. SecureFileOperationService - 安全文件操作服务
 */
@Slf4j
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@ComponentScan(basePackages = "com.nexusmind.security")
public class SecurityAutoConfiguration {
    
    public SecurityAutoConfiguration() {
        log.info("SecurityAutoConfiguration initialized - All security components activated");
    }
}
