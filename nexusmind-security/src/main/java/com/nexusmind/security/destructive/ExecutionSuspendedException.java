package com.nexusmind.security.destructive;

/**
 * 执行挂起异常
 * 当触发破坏性操作需要人工确认时抛出此异常，打断当前 DAG 执行链
 * 全局异常处理器会捕获此异常，推流 SSE 通知前端并存储快照
 */
public class ExecutionSuspendedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 执行快照
     */
    private final ExecutionSnapshot snapshot;

    public ExecutionSuspendedException(ExecutionSnapshot snapshot) {
        super("Execution suspended for user confirmation: " + snapshot.getOperationDescription());
        this.snapshot = snapshot;
    }

    public ExecutionSuspendedException(String message, ExecutionSnapshot snapshot) {
        super(message);
        this.snapshot = snapshot;
    }

    public ExecutionSnapshot getSnapshot() {
        return snapshot;
    }
}
