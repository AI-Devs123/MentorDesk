package com.nexusmind.supervisor.service;

import com.nexusmind.security.destructive.TaskContextHolder;
import com.nexusmind.security.destructive.TrashBackupService;
import com.nexusmind.security.rbac.AgentSecured;
import com.nexusmind.supervisor.execution.SnapshotRepository;
import com.nexusmind.task.TaskExecutionService;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 任务执行服务实现 (Dubbo RPC)
 * 处理破坏性操作的恢复和执行
 */
@DubboService
public class TaskExecutionServiceImpl implements TaskExecutionService {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionServiceImpl.class);

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired(required = false)
    private TrashBackupService trashBackupService;

    /**
     * 恢复任务并执行破坏性操作
     * 
     * @param executionId 执行ID
     * @param token 确认令牌
     * @return 操作结果（包含详细的验证信息）
     */
    @Override
    @AgentSecured(allowedAgents = {"supervisor"})
    public Map<String, Object> resumeTask(String executionId, String token) {
        log.info("📥 收到任务恢复请求: executionId={}, token={}", executionId, token);

        Map<String, Object> response = new HashMap<>();

        // 参数校验
        if (executionId == null || token == null || executionId.trim().isEmpty() || token.trim().isEmpty()) {
            log.error("❌ 缺少必要参数: execution_id 或 token");
            response.put("status", "ERROR");
            response.put("code", "PARAM_MISSING");
            response.put("message", "缺少 execution_id 或 token");
            return response;
        }

        // 令牌校验和消费
        try {
            // 校验令牌是否有效，校验成功后立即删除快照（防止二次重放）
            boolean tokenValid = snapshotRepository.validateAndConsumeToken(executionId, token);
            
            if (!tokenValid) {
                log.error("❌ 令牌校验失败: executionId={}", executionId);
                response.put("status", "ERROR");
                response.put("code", "TOKEN_INVALID");
                response.put("message", "令牌无效或已过期");
                return response;
            }
            
            log.info("✅ 令牌校验成功，快照已消费");
            
            // 注入上下文令牌，标记该任务已获得授权
            TaskContextHolder.setContext(executionId, token);
            
            log.info("✅ [Supervisor] 破坏性操作已核准，可继续执行后续业务流程");
            
            response.put("status", "SUCCESS");
            response.put("code", "OK");
            response.put("execution_id", executionId);
            response.put("message", "破坏性操作已核准，待执行");
            
        } catch (Exception e) {
            log.error("❌ 处理恢复请求异常", e);
            response.put("status", "ERROR");
            response.put("code", "PROCESS_ERROR");
            response.put("message", "处理过程中发生异常: " + e.getMessage());
        } finally {
            TaskContextHolder.clearContext();
        }

        return response;
    }
    /**
     * 中止/拒绝任务执行
     *
     * 功能：
     * 1. 立即销毁 Redis 中的待办快照
     * 2. 标记任务被取消，阻断后续 DAG 流转
     * 3. 返回熔断响应，确保沙箱文件完好无损
     *
     * @param executionId 执行ID
     * @return 操作结果
     */
    @Override
    @AgentSecured(allowedAgents = {"supervisor"})
    public Map<String, Object> abortTask(String executionId) {
        log.warn("🛑 [Supervisor] 收到任务中止拒绝请求, executionId: {}", executionId);

        Map<String, Object> response = new HashMap<>();

        // 参数校验
        if (executionId == null || executionId.trim().isEmpty()) {
            log.error("❌ 缺少必要参数: execution_id");
            response.put("status", "ERROR");
            response.put("code", "PARAM_MISSING");
            response.put("message", "缺少 execution_id");
            return response;
        }

        try {
            // 1. 立即销毁 Redis 中的待办快照
            boolean snapshotRemoved = snapshotRepository.discardSnapshot(executionId);
            log.info("🗑️ [Supervisor] 快照清理结果: executionId={}, removed={}", executionId, snapshotRemoved);

            // 2. 标记任务被取消，阻断后续潜在的后台线程流转
            AgentStreamingFacadeImpl.markTaskCancelled(executionId);
            log.info("🔒 [Supervisor] 任务已标记为已取消: executionId={}", executionId);

            // 3. 返回熔断响应
            response.put("status", "ABORTED");
            response.put("code", "OK");
            response.put("execution_id", executionId);
            response.put("message", "用户拒绝操作，任务回滚中断");
            response.put("snapshot_purged", snapshotRemoved);

            log.info("✅ [Supervisor] 任务已安全中止，快照已清理: executionId={}", executionId);

        } catch (Exception e) {
            log.error("❌ 处理中止请求异常", e);
            response.put("status", "ERROR");
            response.put("code", "PROCESS_ERROR");
            response.put("message", "处理过程中发生异常: " + e.getMessage());
        }

        return response;
    }
}
