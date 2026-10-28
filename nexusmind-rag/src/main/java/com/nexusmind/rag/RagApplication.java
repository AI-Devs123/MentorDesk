package com.nexusmind.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NexusMind 混合检索微服务（Week 7 切片 1 骨架）。
 *
 * <p>当前阶段仅承载 Lucene 双引擎与 EmbeddingClient，未暴露 HTTP/Dubbo 端点。
 * {@code spring.main.web-application-type=none} 让上下文保持轻量，避免无谓的 Netty 绑定；
 * 待切片 5（D3）接入 Triple RPC 时再改为 reactive/web 并注册 HybridSearchService Provider。
 */
@SpringBootApplication
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
