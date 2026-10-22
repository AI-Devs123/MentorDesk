package com.nexusmind.agent.facade;

import org.apache.dubbo.common.stream.StreamObserver;

/**
 * Dubbo Triple 服务基类 - 由 AgentStreamingFacadeImpl 实现
 */
public class DubboAgentStreamingFacadeTriple {

    /**
     * Agent 流式服务的基础实现类
     */
    public abstract static class AgentStreamingFacadeImplBase implements AgentStreamingFacade {

        @Override
        public abstract void executeTaskStream(AgentStreamRequest request, StreamObserver<AgentStreamResponse> responseObserver);

    @Override
    public abstract StreamObserver<AgentStreamRequest> biDirectionalStream(StreamObserver<AgentStreamResponse> responseObserver);

    @Override
    public abstract CancelTaskResponse cancelTask(CancelTaskRequest request);
}
}
