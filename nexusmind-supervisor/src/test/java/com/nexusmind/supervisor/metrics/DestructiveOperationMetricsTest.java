package com.nexusmind.supervisor.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 切片 1：破坏性操作（挂起 / 确认）指标验证（纯单元）。
 */
class DestructiveOperationMetricsTest {

    @Test
    void recordsPendingConfirmedAndInvalid() {
        MeterRegistry reg = new SimpleMeterRegistry();
        DestructiveOperationMetrics metrics = new DestructiveOperationMetrics(reg);

        metrics.recordPending("FILE_DELETE", "HIGH");
        metrics.recordPending("FILE_DELETE", "HIGH");
        metrics.recordPending("DB_WRITE", "MEDIUM");
        metrics.recordConfirmed();
        metrics.recordInvalid();

        // pending 按 operation_type/risk_level 分标签累计
        assertEquals(2.0,
                reg.counter("nexusmind_destructive_pending_total",
                        "operation_type", "FILE_DELETE", "risk_level", "HIGH").count(), 0.0);
        assertEquals(1.0,
                reg.counter("nexusmind_destructive_pending_total",
                        "operation_type", "DB_WRITE", "risk_level", "MEDIUM").count(), 0.0);
        assertEquals(1.0, reg.counter("nexusmind_destructive_confirmed_total").count(), 0.0);
        assertEquals(1.0, reg.counter("nexusmind_destructive_invalid_total").count(), 0.0);
    }
}
