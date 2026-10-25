package com.nexusmind.agent.facade;

import java.io.Serializable;

/**
 * 认知协同流式请求 (CognitiveReflectDAG 输入)
 */
public class CognitiveStreamRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String rawContent;
    private String userId;

    public CognitiveStreamRequest() {
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public static class Builder {
        private final CognitiveStreamRequest request = new CognitiveStreamRequest();

        public Builder setTaskId(String taskId) {
            request.taskId = taskId;
            return this;
        }

        public Builder setRawContent(String rawContent) {
            request.rawContent = rawContent;
            return this;
        }

        public Builder setUserId(String userId) {
            request.userId = userId;
            return this;
        }

        public CognitiveStreamRequest build() {
            return request;
        }
    }
}
