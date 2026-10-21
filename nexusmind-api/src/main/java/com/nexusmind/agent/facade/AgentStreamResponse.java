package com.nexusmind.agent.facade;

import java.io.Serializable;

/**
 * Agent 流式响应
 */
public class AgentStreamResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private long timestamp;
    private String agentName;
    private String stage;
    private String deltaChunk;
    private boolean requiresConfirmation;
    private String confirmationPayload;

    public AgentStreamResponse() {
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

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getDeltaChunk() {
        return deltaChunk;
    }

    public void setDeltaChunk(String deltaChunk) {
        this.deltaChunk = deltaChunk;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getConfirmationPayload() {
        return confirmationPayload;
    }

    public void setConfirmationPayload(String confirmationPayload) {
        this.confirmationPayload = confirmationPayload;
    }

    public static class Builder {
        private final AgentStreamResponse response = new AgentStreamResponse();

        public Builder setTaskId(String taskId) {
            response.taskId = taskId;
            return this;
        }

        public Builder setTimestamp(long timestamp) {
            response.timestamp = timestamp;
            return this;
        }

        public Builder setAgentName(String agentName) {
            response.agentName = agentName;
            return this;
        }

        public Builder setStage(String stage) {
            response.stage = stage;
            return this;
        }

        public Builder setDeltaChunk(String deltaChunk) {
            response.deltaChunk = deltaChunk;
            return this;
        }

        public Builder setRequiresConfirmation(boolean requiresConfirmation) {
            response.requiresConfirmation = requiresConfirmation;
            return this;
        }

        public Builder setConfirmationPayload(String confirmationPayload) {
            response.confirmationPayload = confirmationPayload;
            return this;
        }

        public AgentStreamResponse build() {
            return response;
        }
    }
}
