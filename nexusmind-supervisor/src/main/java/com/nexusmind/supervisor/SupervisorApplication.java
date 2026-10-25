package com.nexusmind.supervisor;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableDubbo // 激活 Dubbo 注解驱动（扫描 @DubboService）
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true) // 启用 AOP 代理，支持 CGLIB
// 显式扫描 security 包中的 AOP 切面；并引入 agent-workers 的 journal 能力链
// （JournalReviewServiceImpl 及其依赖 LLMClient / TagExtractor / JournalStructuredParser，
//   供 JournalReviewFacade 按 IJournalReviewService 契约注入）。
// 只加 journal / extraction / llm 三个子包：若直接扫 com.nexusmind.agent 全包，
// 会把 AgentWorkersApplication（@SpringBootApplication，嵌套启动类）一起拉进本上下文。
@ComponentScan(basePackages = {
        "com.nexusmind.supervisor",
        "com.nexusmind.security",
        "com.nexusmind.agent.journal",
        "com.nexusmind.agent.extraction",
        "com.nexusmind.agent.llm"
})
public class SupervisorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SupervisorApplication.class, args);
    }
}