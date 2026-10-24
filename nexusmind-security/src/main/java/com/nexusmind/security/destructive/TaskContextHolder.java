package com.nexusmind.security.destructive;

/**
 * 任务上下文保存器
 * 用于在执行恢复调用时，暂存合法的 resumeToken，使 DestructiveActionAdvisor 切面识别到放行标记
 */
public class TaskContextHolder {
    private static final ThreadLocal<String> EXECUTION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> RESUME_TOKEN = new ThreadLocal<>();

    /**
     * 设置任务上下文
     *
     * @param executionId 执行ID
     * @param resumeToken 恢复令牌
     */
    public static void setContext(String executionId, String resumeToken) {
        EXECUTION_ID.set(executionId);
        RESUME_TOKEN.set(resumeToken);
    }

    /**
     * 获取执行ID
     *
     * @return 执行ID
     */
    public static String getExecutionId() {
        return EXECUTION_ID.get();
    }

    /**
     * 获取恢复令牌
     *
     * @return 恢复令牌
     */
    public static String getResumeToken() {
        return RESUME_TOKEN.get();
    }

    /**
     * 清空上下文
     */
    public static void clearContext() {
        EXECUTION_ID.remove();
        RESUME_TOKEN.remove();
    }
}
