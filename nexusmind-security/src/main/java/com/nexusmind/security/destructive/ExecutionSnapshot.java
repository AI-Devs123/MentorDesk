package com.nexusmind.security.destructive;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * DAG 执行快照
 * 用于挂起时保存完整的执行上下文，恢复时重建现场
 */
public class ExecutionSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 任务唯一标识
     */
    private String taskId;
    
    /**
     * 当前 DAG 节点 ID
     */
    private String nodeId;
    
    /**
     * 执行上下文变量表（存储 DAG 中间变量）
     */
    private Map<String, Object> contextVariables;
    
    /**
     * 待执行的 Tool 方法签名
     */
    private String toolSignature;
    
    /**
     * Tool 方法参数（JSON 序列化）
     */
    private Map<String, Object> toolArguments;
    
    /**
     * 破坏性操作类型
     */
    private DestructiveOperationType operationType;
    
    /**
     * 风险等级
     */
    private RiskLevel riskLevel;
    
    /**
     * 操作描述
     */
    private String operationDescription;
    
    /**
     * 变更预览（Diff 或影响路径列表）
     */
    private String changePreview;
    
    /**
     * 一次性确认令牌
     */
    private String confirmationToken;
    
    /**
     * 创建时间戳
     */
    private Instant createdAt;
    
    /**
     * 过期时间戳（默认 1 小时后）
     */
    private Instant expiresAt;

    // Constructors
    public ExecutionSnapshot() {
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plusSeconds(3600); // 默认 1 小时 TTL
    }

    // Getters and Setters
    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Map<String, Object> getContextVariables() {
        return contextVariables;
    }

    public void setContextVariables(Map<String, Object> contextVariables) {
        this.contextVariables = contextVariables;
    }

    public String getToolSignature() {
        return toolSignature;
    }

    public void setToolSignature(String toolSignature) {
        this.toolSignature = toolSignature;
    }

    public Map<String, Object> getToolArguments() {
        return toolArguments;
    }

    public void setToolArguments(Map<String, Object> toolArguments) {
        this.toolArguments = toolArguments;
    }

    public DestructiveOperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(DestructiveOperationType operationType) {
        this.operationType = operationType;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getOperationDescription() {
        return operationDescription;
    }

    public void setOperationDescription(String operationDescription) {
        this.operationDescription = operationDescription;
    }

    public String getChangePreview() {
        return changePreview;
    }

    public void setChangePreview(String changePreview) {
        this.changePreview = changePreview;
    }

    public String getConfirmationToken() {
        return confirmationToken;
    }

    public void setConfirmationToken(String confirmationToken) {
        this.confirmationToken = confirmationToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
