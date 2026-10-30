package com.nexusmind.agent.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * non-web 模块指标外推（切片 1 可观测性基座）。
 *
 * <p>agent-workers 是纯 {@code spring-boot-starter} 进程（无 HTTP 端口），无法被 Prometheus
 * 主动 scrape，故周期性把 {@link PrometheusMeterRegistry} 的 scrape 文本 POST 到 PushGateway 的
 * HTTP 接口（{@code PUT/POST /metrics/job/<job>/instance/<instance>}）。
 *
 * <p>实现不依赖任何 prometheus 客户端 pushgateway 库（其版本与 micrometer 1.13 的
 * prometheus-metrics 新模型长期不兼容，极易踩坑），而是直接复用 JDK {@link HttpClient}，
 * 版本无关、零额外依赖。
 *
 * <p>默认关闭（{@code nexusmind.metrics.push.enabled=false}）：本地无 Prometheus / PushGateway
 * 时绝不报错，业务零侵入。部署到可观测环境时置 {@code NEXUSMIND_PUSH_ENABLED=true} 即可启用。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "nexusmind.metrics.push", name = "enabled", havingValue = "true")
public class MetricsPushConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsPushConfig.class);

    private final PrometheusMeterRegistry registry;
    private final HttpClient httpClient;
    private final String gatewayAddress;
    private final String job;

    public MetricsPushConfig(PrometheusMeterRegistry registry,
                             @Value("${nexusmind.metrics.push.gateway:localhost:9091}") String gatewayAddress,
                             @Value("${nexusmind.metrics.push.job:${spring.application.name}}") String job) {
        this.registry = registry;
        this.gatewayAddress = gatewayAddress;
        this.job = job;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Scheduled(fixedDelayString = "${nexusmind.metrics.push.step-millis:60000}")
    public void push() {
        try {
            // Micrometer 1.13 的 PrometheusMeterRegistry.scrape() 直接产出 Prometheus 文本格式
            String payload = registry.scrape();
            if (payload == null || payload.isEmpty()) {
                return;
            }
            String instance = instanceName();
            String url = String.format("http://%s/metrics/job/%s/instance/%s",
                    gatewayAddress, encode(job), encode(instance));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "text/plain; version=0.0.4")
                    .PUT(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                log.debug("[MetricsPush] pushed metrics to PushGateway (job={}, instance={})", job, instance);
            } else {
                log.warn("[MetricsPush] PushGateway rejected push: status={}, job={}", status, job);
            }
        } catch (Exception e) {
            // best-effort：推送失败（PushGateway 未起 / 网络抖动）绝不拖垮业务
            log.warn("[MetricsPush] push failed (job={}): {}", job, e.getMessage());
        }
    }

    /** PushGateway 路径段不允许 '/' 与 '%'，做安全转义避免注入/截断。 */
    private static String encode(String s) {
        return s == null ? "" : s.replace("/", "_").replace("%", "_");
    }

    private String instanceName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
