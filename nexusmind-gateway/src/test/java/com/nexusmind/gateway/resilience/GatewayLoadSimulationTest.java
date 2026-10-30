package com.nexusmind.gateway.resilience;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.WebHandler;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关接入层限流门禁的离线并发验证（切片4）。
 *
 * <p>复用切片3 已验证的 {@link WebTestClient#bindToWebHandler} 路径（保证请求资源名与
 * 生产一致），在 QPS=5 的资源上并发投入 50 个请求：断言绝大多数被门禁挡成 429、少数放行。
 * 证明切片3 的「准入即放行」门禁在真实并发下确实生效，而非仅单请求场景成立。
 */
class GatewayLoadSimulationTest {

    private static final String PATH = "/sentinel-load";

    private WebTestClient webClient;

    @BeforeEach
    void setUp() {
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(true);

        String resource = "GET:" + PATH;
        FlowRule rule = new FlowRule(resource);
        rule.setCount(5);     // QPS = 5
        rule.setGrade(1);     // 1 = QPS
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));

        SentinelWebFilter filter = new SentinelWebFilter(props, new ObjectMapper());
        WebHandler handler = exchange -> Mono.empty();
        webClient = WebTestClient.bindToWebHandler(handler)
                .webFilter(filter)
                .build();
    }

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void highConcurrencyAdmissionGateThrottlesExcess() throws Exception {
        final int total = 50;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger passed = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int status = webClient.get().uri(PATH)
                            .exchange()
                            .returnResult(Void.class)
                            .getStatus()
                            .value();
                    if (status == 429) {
                        blocked.incrementAndGet();
                    } else {
                        passed.incrementAndGet();
                    }
                } catch (Exception e) {
                    passed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdownNow();

        System.out.println("[GatewayLoad] total=" + total
                + " passed=" + passed.get() + " blocked=" + blocked.get());
        assertTrue(blocked.get() >= 30,
                "并发下多数请求应被限流门禁挡成 429，实际 blocked=" + blocked.get());
        assertTrue(passed.get() >= 1,
                "少量请求应被放行，实际 passed=" + passed.get());
    }
}
