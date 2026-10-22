package com.nexusmind.agent.facade;

import java.io.Serializable;

/**
 * 任务取消响应（Supervisor 回包，告知中断信号的处理结果）
 */
public class CancelTaskResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 是否找到对应任务 */
    private boolean found;
    /** 是否已成功中断执行线程（true 表示线程被立即唤醒并停止） */
    private boolean interrupted;
    /** 文字说明 */
    private String message;
    /** 当前 Supervisor 仍在执行的任务数 */
    private int activeTaskCount;

    public CancelTaskResponse() {
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getActiveTaskCount() {
        return activeTaskCount;
    }

    public void setActiveTaskCount(int activeTaskCount) {
        this.activeTaskCount = activeTaskCount;
    }

    public static class Builder {
        private final CancelTaskResponse response = new CancelTaskResponse();

        public Builder setFound(boolean found) {
            response.found = found;
            return this;
        }

        public Builder setInterrupted(boolean interrupted) {
            response.interrupted = interrupted;
            return this;
        }

        public Builder setMessage(String message) {
            response.message = message;
            return this;
        }

        public Builder setActiveTaskCount(int activeTaskCount) {
            response.activeTaskCount = activeTaskCount;
            return this;
        }

        public CancelTaskResponse build() {
            return response;
        }
    }
}
