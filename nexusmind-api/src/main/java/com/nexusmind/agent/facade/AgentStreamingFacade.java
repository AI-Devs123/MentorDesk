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
}
