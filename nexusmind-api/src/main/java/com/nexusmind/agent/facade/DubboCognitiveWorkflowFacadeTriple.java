package com.nexusmind.agent.facade;

import org.apache.dubbo.common.stream.StreamObserver;

/**
 * Dubbo Triple 服务基类 - 由 CognitiveWorkflowFacadeImpl 实现
 * 与 DubboAgentStreamingFacadeTriple 同构的手写 Triple 契约模式
 */
public class DubboCognitiveWorkflowFacadeTriple {

    /**
     * 认知协同流式服务的基础实现类
     */
    public abstract static class CognitiveWorkflowFacadeImplBase implements CognitiveWorkflowFacade {

        @Override
        public abstract void executeCognitiveStream(CognitiveStreamRequest request,
                                                    StreamObserver<CognitiveStreamChunk> responseObserver);

        @Override
        public abstract CognitiveCancelResponse cancelCognitiveTask(String taskId);
    }
}
