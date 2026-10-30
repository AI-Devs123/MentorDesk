package com.nexusmind.supervisor.resilience;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 3：下游调用熔断验证（纯单元，离线）。
 *
 * <p>对资源 {@code test:cb} 配置「异常比例熔断」（异常比例 ≥0.5 且近窗口内 ≥5 次请求即熔断 5s）。
 * 主逻辑恒定抛异常（模拟下游故障）：前若干次执行主逻辑并计入异常，
 * 熔断打开后 {@link ResilientDownstreamCaller#call} 改为走 fallback，证明熔断器确实生效，
 * 且熔断未打开时主逻辑异常仍按生产语义透传（不被降级吞掉）。
 */
class ResilientDownstreamCallerTest {

    private static final String RES = "test:cb";

    @BeforeEach
    void setUp() {
        DegradeRule rule = new DegradeRule(RES);
        rule.setGrade(1); // 1=异常比例
        rule.setCount(0.5);
        rule.setMinRequestAmount(5);
        rule.setStatIntervalMs(10000);
        rule.setTimeWindow(5); // 秒
        DegradeRuleManager.loadRules(List.of(rule));
    }

    @AfterEach
    void tearDown() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void circuitOpensAndFallbackInvoked() {
        ResilientDownstreamCaller caller = new ResilientDownstreamCaller();
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();

        int fallbackReturns = 0;
        for (int i = 0; i < 20; i++) {
            try {
                String result = caller.call(RES,
                        () -> {
                            primaryCalls.incrementAndGet();
                            throw new RuntimeException("downstream boom");
                        },
                        () -> {
                            fallbackCalls.incrementAndGet();
                            return "FALLBACK";
                        });
                // 正常返回只可能来自 fallback（主逻辑恒定抛异常）
                if ("FALLBACK".equals(result)) {
                    fallbackReturns++;
                }
            } catch (RuntimeException ex) {
                // 熔断未打开：主逻辑异常按生产语义透传
            }
        }

        assertTrue(fallbackCalls.get() > 0, "熔断应至少触发一次降级（fallback）");
        assertTrue(primaryCalls.get() < 20, "熔断打开后主逻辑调用数应小于总次数");
        assertEquals(20, primaryCalls.get() + fallbackCalls.get(), "每次调用要么走主逻辑要么走降级");
        assertEquals(fallbackCalls.get(), fallbackReturns, "走降级即返回 FALLBACK");
    }
}
