package com.nexusmind.agent.facade;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 流式请求
 */
public class AgentStreamRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String sessionId;
    private String agentName;
    private String payload;
    private Map<String, String> metadata;

    public AgentStreamRequest() {
        this.metadata = new HashMap<>();
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public static class Builder {
        private final AgentStreamRequest request = new AgentStreamRequest();

        public Builder setTaskId(String taskId) {
            request.taskId = taskId;
            return this;
        }

        public Builder setSessionId(String sessionId) {
            request.sessionId = sessionId;
            return this;
        }

        public Builder setAgentName(String agentName) {
            request.agentName = agentName;
            return this;
        }

        public Builder setPayload(String payload) {
            request.payload = payload;
            return this;
        }

        public Builder setMetadata(Map<String, String> metadata) {
            request.metadata = metadata;
            return this;
        }

        public AgentStreamRequest build() {
            return request;
        }
    }
}
