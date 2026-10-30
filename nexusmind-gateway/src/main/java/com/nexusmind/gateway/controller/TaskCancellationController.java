package com.nexusmind.gateway.controller;

import com.nexusmind.agent.facade.AgentStreamingFacade;
import com.nexusmind.agent.facade.CancelTaskRequest;
import com.nexusmind.agent.facade.CancelTaskResponse;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务取消控制器
 * 用于处理前端的主动中断请求（与 SSE 断连自动中断互为备份）
 */
@RestController
public class TaskCancellationController {

    private static final Logger log = LoggerFactory.getLogger(TaskCancellationController.class);

    // 切片5：Triple 协议强制 fastjson2，规避 Hessian2 反序列化 record DTO 崩溃
    @DubboReference(parameters = {"serialization=fastjson2"})
    private AgentStreamingFacade agentStreamingFacade;

    /**
     * 主动中断指定任务
     * 前端调用此接口来中断正在进行的流式任务
     */
    @PostMapping("/api/v1/agent/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(@RequestParam(name = "taskId") String taskId) {
        log.warn("🛑 [TaskCancellation] 收到前端主动中断请求: {}", taskId);

        // 只有确实在运行的任务才允许取消
        if (!AgentGatewayController.hasTask(taskId)) {
            log.warn("⚠️ [TaskCancellation] 任务不存在或已完成: {}", taskId);
            return ResponseEntity.ok(Map.of(
                    "code", 404,
                    "message", "任务不存在或已完成",
                    "taskId", taskId,
                    "cancelled", false
            ));
        }

        // 1) 网关侧置位，停止向前端推送
        AtomicBoolean isCancelled = AgentGatewayController.getTaskCancellationFlag(taskId);
        isCancelled.set(true);
        log.info("✓ [TaskCancellation] 网关侧任务已标记为中断: {}", taskId);

        // 2) 跨进程下发 RST_STREAM 给 Supervisor，立即停止其执行线程
        boolean supervisorInterrupted = false;
        try {
            CancelTaskRequest req = CancelTaskRequest.newBuilder().setTaskId(taskId).build();
            CancelTaskResponse resp = agentStreamingFacade.cancelTask(req);
            supervisorInterrupted = resp.isInterrupted();
            log.warn("✓ [TaskCancellation] RST_STREAM 已送达 Supervisor: found={}, interrupted={}, activeTasks={}",
                    resp.isFound(), resp.isInterrupted(), resp.getActiveTaskCount());
        } catch (Exception e) {
            log.error("❌ [TaskCancellation] 通知 Supervisor 失败: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "任务中断信号已发送",
                "taskId", taskId,
                "cancelled", true,
                "supervisorInterrupted", supervisorInterrupted
        ));
    }

    /**
     * 查询任务的中断状态
     */
    @PostMapping("/api/v1/agent/status")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@RequestParam(name = "taskId") String taskId) {
        if (!AgentGatewayController.hasTask(taskId)) {
            return ResponseEntity.ok(Map.of(
                    "code", 404,
                    "taskId", taskId,
                    "message", "任务不存在"
            ));
        }
        AtomicBoolean isCancelled = AgentGatewayController.getTaskCancellationFlag(taskId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "taskId", taskId,
                "cancelled", isCancelled.get()
        ));
    }
}
