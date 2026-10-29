package com.nexusmind.supervisor.observability;

import com.nexusmind.api.observability.LfTraceContext;
import com.nexusmind.api.observability.LfTracingClient;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 编排入口追踪切面（切片 3）。
 *
 * <p>在 Supervisor 的四个编排入口上织入 Trace + Span，将一次前端任务下发的端到端耗时、
 * 入口、成败可视化到 Langfuse。设计铁律（与切片 2 反复强调的「旁路异步采样」一致）：
 * <ul>
 *   <li><b>非阻塞</b>：{@link LfTracingClient} 底层走 {@code HttpClient#sendAsync} + daemon 线程，
 *       绝不占用 Dubbo / SSE 业务线程；</li>
 *   <li><b>best-effort</b>：探针缺失（NoOp）或上报失败一律吞掉，编排主链路零感知；</li>
 *   <li><b>零侵入</b>：编排方法本身不 import 任何 observability 类，可观测层完全由本切面旁路注入。</li>
 * </ul>
 *
 * <p>traceId 经 {@link LfTraceContext#propagateToRpc()} 注入 Dubbo 客户端 attachment，
 * 透传到 agent-workers 进程内的 {@code LLMClient}，使下游 LLM generation 归并到同一条 trace
 * （与 {@code AgentRbacContext} 同源的 RpcContext attachment 透传机制）。
 */
@Aspect
@Component
public class LangfuseTraceAspect {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTraceAspect.class);

    private final LfTracingClient tracingClient;

    public LangfuseTraceAspect(LfTracingClient tracingClient) {
        this.tracingClient = tracingClient;
    }

    // ── 四个编排入口的切点（薄编排层 @Service / @DubboService，均被 CGLIB 代理，AOP 可织入）──

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
    public Object traceOrchestration(ProceedingJoinPoint pjp) throws Throwable {
        // 探针关闭（NoOp）时彻底旁路，连 ThreadLocal 都不设，零开销。
        if (!tracingClient.isEnabled()) {
            return pjp.proceed();
        }

        String traceId = UUID.randomUUID().toString();
        String spanId = UUID.randomUUID().toString();
        String name = pjp.getSignature().toShortString();
        long startMs = System.currentTimeMillis();

        LfTraceContext.set(traceId);
        LfTraceContext.propagateToRpc(); // 透传到 agent-workers 的 LLMClient，归并到同一条 trace

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("entry", name);
        meta.put("agent", "supervisor");
        tracingClient.trace(traceId, "orchestration", null, meta);

        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            long endMs = System.currentTimeMillis();
            tracingClient.spanAndClose(traceId, spanId, name, "ERR",
                    truncate(t.getMessage()), startMs, endMs, "ERROR");
            LfTraceContext.clear();
            throw t;
        }
        long endMs = System.currentTimeMillis();
        tracingClient.spanAndClose(traceId, spanId, name, "OK", "OK", startMs, endMs, "DEFAULT");
        LfTraceContext.clear();
        return result;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 256 ? s.substring(0, 256) : s;
    }
}
