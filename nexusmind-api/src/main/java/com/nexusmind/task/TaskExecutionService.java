package com.nexusmind.task;

import java.util.Map;

/**
 * 任务执行服务接口 (Dubbo RPC)
 * 处理破坏性操作的恢复和执行
 */
public interface TaskExecutionService {
    
    /**
     * 恢复任务并执行破坏性操作
     * 
     * @param executionId 执行ID
     * @param token 确认令牌
     * @return 操作结果
     */
    Map<String, Object> resumeTask(String executionId, String token);
    
    /**
     * 中止/拒绝任务执行
     * 
     * @param executionId 执行ID
     * @return 操作结果
     */
    Map<String, Object> abortTask(String executionId);
}
