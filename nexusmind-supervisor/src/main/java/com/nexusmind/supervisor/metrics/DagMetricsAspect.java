package com.nexusmind.supervisor.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 编排入口耗时埋点（切片 1 可观测性基座）。
 *
 * <p>与 {@code LangfuseTraceAspect} 同源织入四个 supervisor 编排入口，记录端到端耗时（Timer）
 * 与成败计数（Counter）。设计铁律与本仓既有可观测范式一致：
 * <ul>
 *   <li><b>零侵入</b>：编排方法本身不依赖本切面；</li>
 *   <li><b>best-effort</b>：指标采集绝不拖垮主链路（异常已在 try/catch 内隔离，不影响 proceed）；</li>
 *   <li><b>幂等</b>：与 LangfuseTraceAspect 同为 {@code @Around}，各自仅 proceed 一次，
 *       目标方法仍只执行一次。</li>
 * </ul>
 */
@Aspect
@Component
public class DagMetricsAspect {

    private final MeterRegistry registry;

    public DagMetricsAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Pointcut("execution(* com.nexusmind.supervisor.service.JournalReviewFacade.reviewAndPersist(..))")
    private void journalEntry() {
    }

    @Pointcut("execution(* com.nexusmind.supervisor.service.TutorFacade.generateSocraticReflection(..))")
    private void tutorEntry() {
    }

    @Pointcut("execution(* com.nexusmind.supervisor.service.CodeOrchestrationServiceImpl.orchestrateCodeAnalysis(..))")
    private void codeEntry() {
    }

    @Pointcut("execution(* com.nexusmind.supervisor.service.WebCuratorOrchestrationServiceImpl.orchestrateWebCurate(..))")
    private void webEntry() {
    }

    @Pointcut("journalEntry() || tutorEntry() || codeEntry() || webEntry()")
    private void anyOrchestrationEntry() {
    }

    @Around("anyOrchestrationEntry()")
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        String name = pjp.getSignature().getName();
        long start = System.nanoTime();
        try {
            Object result = pjp.proceed();
            record(name, start, "OK");
            return result;
        } catch (Throwable t) {
            record(name, start, "ERROR");
            throw t;
        }
    }

    private void record(String name, long startNanos, String outcome) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        registry.counter("nexusmind_dag_orchestration_total",
                        "entry", name,
                        "outcome", outcome)
                .increment();
        Timer.builder("nexusmind_dag_orchestration_seconds")
                .tags("entry", name, "outcome", outcome)
                .register(registry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }
}
