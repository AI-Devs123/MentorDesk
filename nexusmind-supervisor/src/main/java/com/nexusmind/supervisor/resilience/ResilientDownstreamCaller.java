package com.nexusmind.supervisor.resilience;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 下游调用熔断器（通用模板）。
 *
 * <p>把任意一次「可能失败/变慢」的下游调用（如跨进程 Dubbo 调用）包成 Sentinel 资源，
 * 由 {@code sentinel.degrade-rules} 中同名资源的熔断规则守护：当异常比例/慢调用比例超阈值，
 * Sentinel 会打开熔断器，后续 {@link SphU#entry(String)} 直接抛出 {@link BlockException}
 * （具体为 {@code DegradeException}），此时走 {@code fallback} 降级而非继续打到已故障的下游，避免雪崩。
 *
 * <p>用法示例（见 {@code JournalReviewFacade}）：
 * <pre>
 *   resilientDownstream.call("downstream:journalReviewService",
 *       () -> journalReviewService.reviewAndPersist(raw),
 *       () -> { throw new CircuitBreakerOpenException("..."); });
 * </pre>
 */
@Service
public class ResilientDownstreamCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientDownstreamCaller.class);

    /**
     * 以熔断/限流保护执行 {@code primary}，熔断打开时改走 {@code fallback}。
     *
     * @param resource  Sentinel 资源名（需与 yml 中 degrade-rules 的 resource 对应）
     * @param primary   受保护的主逻辑（下游调用）
     * @param fallback  熔断/限流命中时的降级逻辑
     * @param <T>       返回值类型
     * @return primary 或 fallback 的结果
     */
    public <T> T call(String resource, Supplier<T> primary, Supplier<T> fallback) {
        Entry entry;
        try {
            entry = SphU.entry(resource);
        } catch (BlockException ex) {
            log.warn("[Sentinel] 下游资源 {} 熔断/限流命中（{}），执行降级",
                    resource, ex.getClass().getSimpleName());
            return fallback.get();
        }
        try {
            return primary.get();
        } catch (RuntimeException ex) {
            // 记录业务异常，供熔断规则（异常比例 / 异常数）统计；
            // 异常按生产语义继续向上透传——熔断未打开时不屏蔽真实下游错误。
            Tracer.trace(ex);
            throw ex;
        } finally {
            entry.exit();
        }
    }
}
