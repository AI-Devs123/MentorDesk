package com.nexusmind.security.rbac;

/**
 * Agent 角色上下文桥（轻量 ThreadLocal 载体）。
 *
 * <p><b>定位：</b>这不是第二套 RBAC 权威源。真正的鉴权权威链路是
 * {@code AgentRbacDubboFilter}（Provider 端）从 {@code RpcContext} 读取
 * {@code x-nexusmind-agent-role} 并校验 {@link AgentSecured}。本 Holder 仅作为：
 * <ul>
 *   <li>① 单测可注入角色的测试缝；</li>
 *   <li>② 当工具在 Dubbo 异步 / 线程池 / 虚拟线程路径上执行时，由调用方用 {@link #wrap(Runnable)}
 *       显式捕获并传播角色（{@code InheritableThreadLocal} 对池化 / 虚拟线程<b>无效</b>，故不采用）。</li>
 * </ul>
 *
 * <p>生产同步链路下，{@code CodeToolSecurityAspect} 优先读取 {@code RpcContext} 中的角色，
 * 仅在缺失时才回退到本 Holder。
 */
public final class AgentSecurityContextHolder {

    private static final ThreadLocal<AgentRole> CURRENT_ROLE = new ThreadLocal<>();

    private AgentSecurityContextHolder() {
    }

    public static void setRole(AgentRole role) {
        CURRENT_ROLE.set(role);
    }

    public static AgentRole getRole() {
        return CURRENT_ROLE.get();
    }

    public static void clear() {
        CURRENT_ROLE.remove();
    }

    /**
     * 装饰异步任务，使其在池化 / 虚拟线程中执行时仍携带当前角色上下文。
     *
     * @param task 待执行的异步任务
     * @return 已捕获当前角色上下文的 Runnable
     */
    public static Runnable wrap(Runnable task) {
        AgentRole capturedRole = getRole();
        return () -> {
            AgentRole previous = getRole();
            try {
                if (capturedRole != null) {
                    setRole(capturedRole);
                }
                task.run();
            } finally {
                if (previous != null) {
                    setRole(previous);
                } else {
                    clear();
                }
            }
        };
    }
}
