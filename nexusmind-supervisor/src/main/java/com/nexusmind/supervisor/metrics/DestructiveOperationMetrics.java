package com.nexusmind.supervisor.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 破坏性操作（挂起 / 确认）指标（切片 1 可观测性基座）。
 *
 * <p>在 {@code SnapshotRepository} 落盘快照（挂起待二次确认）与令牌校验通过（已确认执行）时计数。
 * 标签 {@code operation_type} / {@code risk_level} 取自 {@code ExecutionSnapshot}，
 * 用于观测哪类高风险操作最频繁地进入人机确认环节。
 */
@Component
public class DestructiveOperationMetrics {

    private final MeterRegistry registry;

    public DestructiveOperationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 快照保存（破坏性操作挂起待确认）。 */
    public void recordPending(String operationType, String riskLevel) {
        registry.counter("nexusmind_destructive_pending_total",
                        "operation_type", operationType,
                        "risk_level", riskLevel)
                .increment();
    }

    /** 令牌校验通过（用户确认，操作进入执行）。 */
    public void recordConfirmed() {
        registry.counter("nexusmind_destructive_confirmed_total").increment();
    }

    /** 令牌无效 / 过期 / 缺失（确认失败或重放被拦截）。 */
    public void recordInvalid() {
        registry.counter("nexusmind_destructive_invalid_total").increment();
    }
}
