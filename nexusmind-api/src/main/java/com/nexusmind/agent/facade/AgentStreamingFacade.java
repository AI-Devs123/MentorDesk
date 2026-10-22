package com.nexusmind.agent.facade;

import org.apache.dubbo.common.stream.StreamObserver;

/**
 * Agent 流式服务接口（Dubbo Triple 协议）
 */
public interface AgentStreamingFacade {

    /**
     * 服务端流式调用：客户端发送单次任务指令，服务端响应多 Agent 协同过程流
     *
     * @param request 任务请求
     * @param responseObserver 响应观察者
     */
    void executeTaskStream(AgentStreamRequest request, StreamObserver<AgentStreamResponse> responseObserver);

    /**
     * 双向流式调用：支持客户端与 Agent 交互式协同
     *
     * @return 请求观察者
     */
    StreamObserver<AgentStreamRequest> biDirectionalStream(StreamObserver<AgentStreamResponse> responseObserver);

    /**
     * 取消指定任务（网关在前端 SSE 断连时调用，即 RST_STREAM 语义）。
     * Supervisor 收到后立即置位中断标志并唤醒执行线程，停止后续阶段。
     *
     * @param request 含 taskId 的取消请求
     * @return 取消处理结果
     */
    CancelTaskResponse cancelTask(CancelTaskRequest request);
}
