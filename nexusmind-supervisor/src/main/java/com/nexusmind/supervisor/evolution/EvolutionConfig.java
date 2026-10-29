package com.nexusmind.supervisor.evolution;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 知识进化异步执行器配置（切片 1）。
 *
 * <p>对齐 {@code CognitiveWorkflowFacadeImpl#dagExecutor} 的既有约定：使用
 * {@link Executors#newCachedThreadPool}（JDK 17 基线，平台线程），每个扫描一轻量线程，
 * 不占用 Dubbo / Netty 业务线程池，彻底与 SSE 推流主链路解耦。
 *
 * <p>设计取舍：项目编译基线是 JDK 17（{@code maven.compiler.source/target=17}），
 * 故不采用 JDK 21 的 {@code newVirtualThreadPerTaskExecutor()}，保持与现有代码一致、
 * 可在本机 JDK 17 下编译通过。后置扫描为低频 fire-and-forget 任务，cached 线程池足够。
 */
@Configuration
public class EvolutionConfig {

    @Bean(name = "evolutionExecutor")
    public Executor evolutionExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "evolution-scan");
            t.setDaemon(true);
            return t;
        });
    }
}
