package com.nexusmind.supervisor.observability;

import com.nexusmind.api.observability.HttpLfTracingClient;
import com.nexusmind.api.observability.LfTracingClient;
import com.nexusmind.api.observability.NoOpLfTracingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Langfuse 探针 Bean 装配（切片 3）。
 *
 * <p>与切片 1 的 {@code EvolutionConfig} 同构：用 {@code @Value} 读取外部化配置，按开关装配实现。
 * <ul>
 *   <li>{@code observability.langfuse.enabled=true} 且 {@code host} 非空 → 真实 {@link HttpLfTracingClient}；</li>
 *   <li>否则 → {@link NoOpLfTracingClient}（默认），AOP 切面与 LLMClient 完全不感知可观测层。</li>
 * </ul>
 *
 * <p>默认关闭，契合「本地隐私、无厂商锁定、监控绝不拖垮业务」原则。装配失败时（host 空 / 显式关闭）
 * 一律回退 NoOp，绝不让可观测装配异常阻断 Supervisor 启动。
 */
@Configuration
public class LangfuseConfig {

    @Value("${observability.langfuse.enabled:false}")
    private boolean enabled;

    @Value("${observability.langfuse.host:}")
    private String host;

    @Value("${observability.langfuse.public-key:}")
    private String publicKey;

    @Value("${observability.langfuse.secret-key:}")
    private String secretKey;

    @Bean
    public LfTracingClient lfTracingClient() {
        if (enabled && host != null && !host.isEmpty()) {
            return new HttpLfTracingClient(host, publicKey, secretKey);
        }
        return new NoOpLfTracingClient();
    }
}
