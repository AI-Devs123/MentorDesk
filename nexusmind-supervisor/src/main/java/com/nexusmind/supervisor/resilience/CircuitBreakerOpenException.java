package com.nexusmind.supervisor.resilience;

/**
 * 下游熔断器已打开时抛出的领域异常。
 *
 * <p>由 {@link ResilientDownstreamCaller} 的 fallback 抛出，向上传递「下游不可用」语义，
 * 调用方（如 Controller）可据此返回 503 / 友好提示，而非把故障继续放大。
 */
public class CircuitBreakerOpenException extends RuntimeException {

    public CircuitBreakerOpenException(String message) {
        super(message);
    }

    public CircuitBreakerOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
