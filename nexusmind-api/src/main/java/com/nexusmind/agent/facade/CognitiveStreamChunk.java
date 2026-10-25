package com.nexusmind.agent.facade;

import java.io.Serializable;

/**
 * 认知协同流式数据帧 (CognitiveReflectDAG 输出)
 *
 * 两类帧:
 * - progress: 过程日志帧，payload 为 JSON (timestamp/agent/status/message)
 * - data:    业务数据帧 (eventType=journal_result / tutor_prompt)，payload 为 DTO JSON
 * - done:    终帧，由 Gateway 依 observer.onCompleted 转换，不经本类
 */
public class CognitiveStreamChunk implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 帧类型: progress(过程日志) / data(业务数据) */
    private String frameType;

    private String taskId;
    private String timestamp;
    private String agent;
    private String status;
    private String message;

    /** data 帧的事件类型: journal_result / tutor_prompt */
    private String eventType;

    /** data 帧的业务负载 (DTO JSON) */
    private String payloadJson;

    public CognitiveStreamChunk() {
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public String getFrameType() {
        return frameType;
    }

    public void setFrameType(String frameType) {
        this.frameType = frameType;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public static class Builder {
        private final CognitiveStreamChunk chunk = new CognitiveStreamChunk();

        public Builder frameType(String frameType) {
            chunk.frameType = frameType;
            return this;
        }

        public Builder taskId(String taskId) {
            chunk.taskId = taskId;
            return this;
        }

        public Builder timestamp(String timestamp) {
            chunk.timestamp = timestamp;
            return this;
        }

        public Builder agent(String agent) {
            chunk.agent = agent;
            return this;
        }

        public Builder status(String status) {
            chunk.status = status;
            return this;
        }

        public Builder message(String message) {
            chunk.message = message;
            return this;
        }

        public Builder eventType(String eventType) {
            chunk.eventType = eventType;
            return this;
        }

        public Builder payloadJson(String payloadJson) {
            chunk.payloadJson = payloadJson;
            return this;
        }

        public CognitiveStreamChunk build() {
            return chunk;
        }
    }
}
