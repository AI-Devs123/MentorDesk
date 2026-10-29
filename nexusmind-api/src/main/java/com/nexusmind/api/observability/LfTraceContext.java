package com.nexusmind.api.observability;

import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 追踪上下文传递（切片 3）。
 *
 * <p>同线程 {@link ThreadLocal} 持有当前 traceId，并桥接到：
 * <ul>
 *   <li>SLF4J MDC（日志可携 traceId，便于按 trace 检索）</li>
 *   <li>Dubbo {@link RpcContext} attachment（跨进程透传到 agent-workers，使 LLMClient 的 generation
 *       归并到同一条 trace）</li>
 * </ul>
 *
 * <p>透传机制与 {@code AgentRbacContext} 同源：client 侧 {@code setAttachment}，provider 侧
 * {@code getServerAttachment().getAttachment()} 读取。
 */
public final class LfTraceContext {

    private static final Logger log = LoggerFactory.getLogger(LfTraceContext.class);

    private static final String TRACE_KEY = "x-nexusmind-trace-id";

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> PARENT_SPAN = new ThreadLocal<>();

    private LfTraceContext() {
    }

    public static void set(String traceId) {
        TRACE_ID.set(traceId);
        org.slf4j.MDC.put("traceId", traceId);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void setParent(String spanId) {
        PARENT_SPAN.set(spanId);
    }

    public static String getParent() {
        return PARENT_SPAN.get();
    }

    public static void clear() {
        TRACE_ID.remove();
        PARENT_SPAN.remove();
        org.slf4j.MDC.remove("traceId");
    }

    /** 将 traceId 注入 Dubbo 客户端 attachment，供下游 provider 读取（与 AgentRbacContext 同源机制）。 */
    public static void propagateToRpc() {
        String id = TRACE_ID.get();
        if (id == null) {
            return;
        }
        try {
            RpcContext.getClientAttachment().setAttachment(TRACE_KEY, id);
        } catch (Exception e) {
            log.warn("[LfTraceContext] 注入 RpcContext 失败(非阻断): {}", e.getMessage());
        }
    }

    /** 在 agent-workers 侧读取上游传来的 traceId（优先 ServerAttachment，回退 ClientAttachment）。 */
    public static String readFromRpc() {
        try {
            String id = RpcContext.getServerAttachment().getAttachment(TRACE_KEY);
            if (id == null || id.isEmpty()) {
                id = RpcContext.getClientAttachment().getAttachment(TRACE_KEY);
            }
            return (id == null || id.isEmpty()) ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}
