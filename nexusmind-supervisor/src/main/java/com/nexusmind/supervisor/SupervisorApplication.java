package com.nexusmind.supervisor;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableDubbo // 激活 Dubbo 注解驱动（扫描 @DubboService）
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true) // 启用 AOP 代理，支持 CGLIB
// 显式扫描 security 包中的 AOP 切面。
// 注意：agent-workers 的核心能力（journal/extraction/llm）不再扫描进本上下文——
// JournalReviewFacade 已改为 @DubboReference 远程引用，
// 执行发生在 agent-workers 进程，本进程只保留 HTTP 入口。
// （曾以 ComponentScan 本地注入 agent 三包，导致执行、落盘、llm 配置全部
//   错位到 supervisor 进程，已废弃。）
@ComponentScan(basePackages = {
        "com.nexusmind.supervisor",
        "com.nexusmind.security"
})
public class SupervisorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SupervisorApplication.class, args);
    }
}