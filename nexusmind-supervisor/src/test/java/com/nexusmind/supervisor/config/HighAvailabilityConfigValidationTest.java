package com.nexusmind.supervisor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 5 高可用配置校验（离线）：4 个模块的 {@code application.yml} 必须包含优雅停机 + 探针 + 消费端韧性。
 *
 * <p>运行期 CWD 为 supervisor 模块根目录：其余模块 yml 用 {@code ..} 上溯读取。
 * rag 为 non-web（{@code web-application-type: none}），无 HTTP server，故只校验上下文级
 * {@code lifecycle.timeout-per-shutdown-phase}，不校验 server.shutdown / 探针。
 */
class HighAvailabilityConfigValidationTest {

    private static final Path GATEWAY_YML =
            Paths.get("..", "nexusmind-gateway", "src", "main", "resources", "application.yml");
    private static final Path SUPERVISOR_YML =
            Paths.get("src", "main", "resources", "application.yml");
    private static final Path AGENT_YML =
            Paths.get("..", "nexusmind-agent-workers", "src", "main", "resources", "application.yml");
    private static final Path RAG_YML =
            Paths.get("..", "nexusmind-rag", "src", "main", "resources", "application.yml");

    private PropertySource<?> load(Path p) throws Exception {
        assertTrue(Files.exists(p), "缺失 yml: " + p.toAbsolutePath());
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("cfg", new FileSystemResource(p));
        return sources.get(0);
    }

    @Test
    void gatewayHasGracefulShutdownAndProbes() throws Exception {
        PropertySource<?> gw = load(GATEWAY_YML);
        assertEquals("graceful", String.valueOf(gw.getProperty("server.shutdown")),
                "gateway 未配置优雅停机 server.shutdown=graceful");
        assertEquals("30s", String.valueOf(gw.getProperty("spring.lifecycle.timeout-per-shutdown-phase")),
                "gateway 未配置优雅停机宽限期");
        assertEquals("true", String.valueOf(gw.getProperty("management.endpoint.health.probes.enabled")),
                "gateway 未开启存活/就绪探针");
        assertEquals("true", String.valueOf(gw.getProperty("management.endpoint.health.livenessstate.enabled")),
                "gateway 未开启 liveness 探针");
        assertEquals("true", String.valueOf(gw.getProperty("management.endpoint.health.readinessstate.enabled")),
                "gateway 未开启 readiness 探针");
    }

    @Test
    void supervisorHasGracefulShutdownAndProbes() throws Exception {
        PropertySource<?> sv = load(SUPERVISOR_YML);
        assertEquals("graceful", String.valueOf(sv.getProperty("server.shutdown")),
                "supervisor 未配置优雅停机 server.shutdown=graceful");
        assertEquals("30s", String.valueOf(sv.getProperty("spring.lifecycle.timeout-per-shutdown-phase")),
                "supervisor 未配置优雅停机宽限期");
        assertEquals("true", String.valueOf(sv.getProperty("management.endpoint.health.probes.enabled")),
                "supervisor 未开启存活/就绪探针");
        assertEquals("true", String.valueOf(sv.getProperty("management.endpoint.health.livenessstate.enabled")),
                "supervisor 未开启 liveness 探针");
        assertEquals("true", String.valueOf(sv.getProperty("management.endpoint.health.readinessstate.enabled")),
                "supervisor 未开启 readiness 探针");
    }

    @Test
    void agentWorkersHasGracefulShutdownAndProbes() throws Exception {
        PropertySource<?> aw = load(AGENT_YML);
        assertEquals("graceful", String.valueOf(aw.getProperty("server.shutdown")),
                "agent-workers 未配置优雅停机 server.shutdown=graceful");
        assertEquals("30s", String.valueOf(aw.getProperty("spring.lifecycle.timeout-per-shutdown-phase")),
                "agent-workers 未配置优雅停机宽限期");
        assertEquals("true", String.valueOf(aw.getProperty("management.endpoint.health.probes.enabled")),
                "agent-workers 未开启存活/就绪探针");
    }

    @Test
    void ragHasContextLifecycleTimeoutOnly() throws Exception {
        // rag 为 non-web，无 HTTP server，不校验 server.shutdown / 探针，只校验上下文级优雅停机宽限期。
        PropertySource<?> rag = load(RAG_YML);
        assertEquals("30s", String.valueOf(rag.getProperty("spring.lifecycle.timeout-per-shutdown-phase")),
                "rag 未配置上下文级优雅停机宽限期");
    }

    @Test
    void consumerResilienceConfigured() throws Exception {
        PropertySource<?> gw = load(GATEWAY_YML);
        PropertySource<?> sv = load(SUPERVISOR_YML);

        // gateway 消费端：failover + 重试 1（幂等读场景下自动换节点）
        assertEquals("failover", String.valueOf(gw.getProperty("dubbo.consumer.cluster")),
                "gateway 未配置 dubbo.consumer.cluster=failover");
        assertEquals("1", String.valueOf(gw.getProperty("dubbo.consumer.retries")),
                "gateway 未配置 dubbo.consumer.retries=1");

        // supervisor 消费端：超时 + failover + 重试（调 agent-workers 的 Triple 服务）
        assertEquals("30000", String.valueOf(sv.getProperty("dubbo.consumer.timeout")),
                "supervisor 未配置 dubbo.consumer.timeout=30000");
        assertEquals("failover", String.valueOf(sv.getProperty("dubbo.consumer.cluster")),
                "supervisor 未配置 dubbo.consumer.cluster=failover");
        assertEquals("1", String.valueOf(sv.getProperty("dubbo.consumer.retries")),
                "supervisor 未配置 dubbo.consumer.retries=1");
    }
}
