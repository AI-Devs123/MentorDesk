package com.nexusmind.agent.facade;

import java.io.Serializable;

/**
 * 任务取消请求（网关在前端断连时下发，即 RST_STREAM 语义）
 */
public class CancelTaskRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;

    public CancelTaskRequest() {
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

    public static class Builder {
        private final CancelTaskRequest request = new CancelTaskRequest();

        public Builder setTaskId(String taskId) {
            request.taskId = taskId;
            return this;
        }

        public CancelTaskRequest build() {
            return request;
        }
    }
}
