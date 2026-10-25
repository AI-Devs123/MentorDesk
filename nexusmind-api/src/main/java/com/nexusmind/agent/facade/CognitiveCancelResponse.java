package com.nexusmind.agent.facade;

import java.io.Serializable;

/**
 * 认知任务取消响应 (RST_STREAM 语义)
 */
public class CognitiveCancelResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean found;
    private boolean interrupted;
    private int activeTaskCount;

    public CognitiveCancelResponse() {
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public boolean isFound() {
        return found;
    }

    public void setFound(boolean found) {
        this.found = found;
    }

    public boolean isInterrupted() {
        return interrupted;
    }

    public void setInterrupted(boolean interrupted) {
        this.interrupted = interrupted;
    }

    public int getActiveTaskCount() {
        return activeTaskCount;
    }

    public void setActiveTaskCount(int activeTaskCount) {
        this.activeTaskCount = activeTaskCount;
    }

    public static class Builder {
        private final CognitiveCancelResponse response = new CognitiveCancelResponse();

        public Builder found(boolean found) {
            response.found = found;
            return this;
        }

        public Builder interrupted(boolean interrupted) {
            response.interrupted = interrupted;
            return this;
        }

        public Builder activeTaskCount(int activeTaskCount) {
            response.activeTaskCount = activeTaskCount;
            return this;
        }

        public CognitiveCancelResponse build() {
            return response;
        }
    }
}
