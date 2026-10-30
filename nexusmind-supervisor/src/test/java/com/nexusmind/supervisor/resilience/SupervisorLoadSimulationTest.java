package com.nexusmind.supervisor.resilience;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Supervisor 接入层限流门禁的离线并发验证（切片4）。
 *
 * <p>与 {@code GatewayLoadSimulationTest} 同构，直接并发调用 {@link SentinelServletFilter}
 * （jakarta Filter）的准入逻辑，QPS=5 资源上投入 50 个并发请求，断言多数被挡成 429。
 * 证明 Servlet 侧门禁在并发下同样生效。
 */
class SupervisorLoadSimulationTest {

    private static final String RESOURCE = "POST:/sentinel-load";

    private SentinelServletFilter filter;

    @BeforeEach
    void setUp() {
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(true);
        filter = new SentinelServletFilter(props, new ObjectMapper());

        FlowRule rule = new FlowRule(RESOURCE);
        rule.setCount(5);     // QPS = 5
        rule.setGrade(1);     // 1 = QPS
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));
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
                    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/sentinel-load");
                    MockHttpServletResponse resp = new MockHttpServletResponse();
                    filter.doFilter(req, resp, new MockFilterChain());
                    if (resp.getStatus() == 429) {
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

        System.out.println("[SupervisorLoad] total=" + total
                + " passed=" + passed.get() + " blocked=" + blocked.get());
        assertTrue(blocked.get() >= 30,
                "并发下多数请求应被限流门禁挡成 429，实际 blocked=" + blocked.get());
        assertTrue(passed.get() >= 1,
                "少量请求应被放行，实际 passed=" + passed.get());
    }
}
