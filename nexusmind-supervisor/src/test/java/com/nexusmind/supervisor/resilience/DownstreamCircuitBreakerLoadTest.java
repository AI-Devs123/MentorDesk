package com.nexusmind.supervisor.resilience;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下游熔断的离线负载验证（切片4）。
 *
 * <p>以 {@link ResilientDownstreamCaller} 在故障负载下（100 次调用、主逻辑持续抛异常）
 * 反复进入同一 Sentinel 资源，触发 {@code DegradeRule}（异常比例≥0.5）打开熔断器。
 * 断言：① 熔断打开前真实异常被透传（exceptionCount≥1）；
 *       ② 熔断打开后请求走降级 fallback（fallbackCount 占多数且≥20）。
 * 这证明切片3 的下游熔断在持续故障负载下确实能防雪崩，而非仅单发调用成立。
 */
class DownstreamCircuitBreakerLoadTest {

    private static final String RESOURCE = "downstream:loadTest";

    @BeforeEach
    void setUp() {
        DegradeRule rule = new DegradeRule(RESOURCE);
        rule.setGrade(1);            // 1 = 异常比例
        rule.setCount(0.5);          // 异常比例阈值 50%
        rule.setMinRequestAmount(5);
        rule.setStatIntervalMs(10000);
        rule.setTimeWindow(5);       // 熔断 5 秒
        DegradeRuleManager.loadRules(List.of(rule));
    }

    @AfterEach
    void tearDown() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void repeatedFailuresOpenCircuitAndInvokeFallback() {
        ResilientDownstreamCaller caller = new ResilientDownstreamCaller();
        AtomicInteger fallbackCount = new AtomicInteger();
        AtomicInteger exceptionCount = new AtomicInteger();

        final int total = 100;
        for (int i = 0; i < total; i++) {
            try {
                String result = caller.call(RESOURCE,
                        () -> {
                            throw new RuntimeException("downstream boom");
                        },
                        () -> "FALLBACK");
                if ("FALLBACK".equals(result)) {
                    fallbackCount.incrementAndGet();
                }
            } catch (RuntimeException ex) {
                exceptionCount.incrementAndGet();
            }
        }

        System.out.println("[CircuitBreaker] total=" + total
                + " fallback=" + fallbackCount.get()
                + " exception=" + exceptionCount.get());
        assertTrue(exceptionCount.get() >= 1,
                "熔断打开前应有真实异常透传，实际=" + exceptionCount.get());
        assertTrue(fallbackCount.get() >= 20,
                "故障持续下熔断应被打开并走降级，实际 fallback=" + fallbackCount.get());
        assertTrue(fallbackCount.get() > exceptionCount.get(),
                "降级次数应明显多于真实异常次数");
    }
}
