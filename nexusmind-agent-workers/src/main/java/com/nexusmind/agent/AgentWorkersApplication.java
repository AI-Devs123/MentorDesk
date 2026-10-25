package com.nexusmind.agent;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Agent Workers 应用启动类
 * 作为独立的 Dubbo 服务提供者，向 Nacos 注册
 *
 * 启动方式：
 * - 开发环境: mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"
 * - 生产环境: java -jar target/nexusmind-agent-workers-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod
 */
@SpringBootApplication
@EnableDubbo // 激活 Dubbo 注解驱动（扫描 @DubboService，导出 journal 等核心能力服务）
@ComponentScan(basePackages = {
        "com.nexusmind.agent",
        "com.nexusmind.security"
})
public class AgentWorkersApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentWorkersApplication.class, args);
    }
}
