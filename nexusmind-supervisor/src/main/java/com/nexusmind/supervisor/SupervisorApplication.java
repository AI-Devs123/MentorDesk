package com.nexusmind.supervisor;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableDubbo // 激活 Dubbo 注解驱动（扫描 @DubboService）
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true) // 启用 AOP 代理，支持 CGLIB
@ComponentScan(basePackages = {"com.nexusmind.supervisor", "com.nexusmind.security"}) // 显式扫描 security 包中的 AOP 切面
public class SupervisorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SupervisorApplication.class, args);
    }
}