package com.nexusmind.security.rbac;

/**
 * 安全域统一异常（区别于 JDK 的 {@code java.nio.file.AccessDeniedException}）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>继承 {@link RuntimeException}，避免被 {@code catch (IOException)} 误吞；</li>
 *   <li>语义上对应 Week2 的 SECURITY_VIOLATION 帧，便于网关 / Dubbo 统一序列化与拦截；</li>
 *   <li>工具层 {@code CodeToolSecurityAspect} 在角色越权或缺失上下文时抛出本异常。</li>
 * </ul>
 */
public class AgentSecurityException extends RuntimeException {

    public AgentSecurityException(String message) {
        super(message);
    }

    public AgentSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
