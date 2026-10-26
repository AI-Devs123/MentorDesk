package com.nexusmind.agent;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
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
 *
 * .env 加载：在 Spring 启动前经 dotenv-java 的 Dotenv.configure().load() 读取仓库根目录（或模块上级）的
 * .env，注入为系统属性，使 application.yml 的 ${LLM_API_KEY:...}/${LLM_MODEL:...}/${LLM_BASE_URL:...}
 * 占位符直接解析到 .env 的值，无需命令行硬编码密钥。
 * 选择编程式注入（而非 spring.factories 的 EnvironmentPostProcessor）是因为本模块会被 supervisor 作为
 * 依赖打进 fat-jar，全局 spring.factories 会泄漏到 supervisor 启动时去加载该类而报 ClassNotFoundException。
 * 优先级保护：已有系统属性或 OS 环境变量时不覆盖（OS env > .env > yml 默认值）。
 */
@SpringBootApplication
@EnableDubbo // 激活 Dubbo 注解驱动（扫描 @DubboService，导出 journal 等核心能力服务）
@ComponentScan(basePackages = {
        "com.nexusmind.agent",
        "com.nexusmind.security"
})
public class AgentWorkersApplication {

    public static void main(String[] args) {
        // 在 Spring 启动前把 .env 注入为系统属性（仅本模块，绝不泄漏给依赖方）
        loadDotenvToSystemProperties();
        SpringApplication.run(AgentWorkersApplication.class, args);
    }

    private static void loadDotenvToSystemProperties() {
        try {
            // 优先从当前工作目录读取 .env（jar 从仓库根目录启动时命中）
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().ignoreIfMalformed().load();
            // 兼容 IDE 从模块目录启动（CWD=模块，.env 在仓库根目录=上级目录）
            if (dotenv.entries().isEmpty()) {
                dotenv = Dotenv.configure().directory("../").ignoreIfMissing().ignoreIfMalformed().load();
            }
            for (DotenvEntry entry : dotenv.entries()) {
                String key = entry.getKey();
                // 已有系统属性或 OS 环境变量时不覆盖（保证 OS env > .env > yml 默认值）
                if (System.getProperty(key) == null && System.getenv(key) == null) {
                    System.setProperty(key, entry.getValue());
                }
            }
        } catch (Exception ex) {
            // .env 可选：缺失或异常时静默降级为 application.yml 默认值（api-key=sk-test → Mock）
        }
    }
}
