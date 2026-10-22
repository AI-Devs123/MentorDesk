package com.nexusmind.security.rbac;

import java.util.EnumSet;
import java.util.Set;

/**
 * 权限定义
 * 
 * 定义系统中所有可能的权限及其对应的 Agent 角色
 */
public enum Permission {
    
    // ============ Supervisor 权限 ============
    /**
     * 调用 DAG 编排服务
     */
    DAG_ORCHESTRATION("dag.orchestration", "DAG 编排", EnumSet.of(AgentRole.SUPERVISOR)),
    
    /**
     * 任务协调
     */
    TASK_COORDINATION("task.coordination", "任务协调", EnumSet.of(AgentRole.SUPERVISOR)),
    
    // ============ JournalAgent 权限 ============
    /**
     * 日记只读访问
     */
    JOURNAL_READ("journal.read", "日记只读", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.JOURNAL_AGENT
    )),
    
    /**
     * 日记追加写（不能删除或修改）
     */
    JOURNAL_APPEND("journal.append", "日记追加写", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.JOURNAL_AGENT
    )),
    
    // ============ CodeAgent 权限 ============
    /**
     * 代码库读访问
     */
    CODE_READ("code.read", "代码读", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.CODE_AGENT,
        AgentRole.TUTOR_AGENT  // 导师可读代码供学习
    )),
    
    /**
     * 代码库写访问
     */
    CODE_WRITE("code.write", "代码写", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.CODE_AGENT
    )),
    
    /**
     * AST 解析权限
     */
    AST_PARSE("ast.parse", "AST 解析", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.CODE_AGENT,
        AgentRole.TUTOR_AGENT  // 导师可分析代码
    )),
    
    /**
     * Git 操作权限
     */
    GIT_OPERATION("git.operation", "Git 操作", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.CODE_AGENT
    )),
    
    // ============ TutorAgent 权限 ============
    /**
     * RAG 知识库只读访问
     */
    RAG_READ("rag.read", "RAG 知识库只读", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.TUTOR_AGENT
    )),
    
    /**
     * 知识检索权限
     */
    KNOWLEDGE_SEARCH("knowledge.search", "知识检索", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.TUTOR_AGENT
    )),
    
    // ============ 禁止的权限 (反向定义) ============
    /**
     * 健康私密数据访问（所有 Agent 禁止）
     */
    HEALTH_PRIVATE_READ("health.private.read", "健康私密数据读", EnumSet.noneOf(AgentRole.class)),
    
    /**
     * 凭证库访问（所有 Agent 禁止）
     */
    CREDENTIAL_ACCESS("credential.access", "凭证库访问", EnumSet.noneOf(AgentRole.class)),
    
    /**
     * 系统命令执行（除 Supervisor 外，其他 Agent 禁止）
     */
    SYSTEM_COMMAND("system.command", "系统命令执行", EnumSet.of(AgentRole.SUPERVISOR)),
    
    /**
     * 本地文件删除操作
     */
    FILE_DELETE("file.delete", "文件删除", EnumSet.of(AgentRole.SUPERVISOR)),
    
    /**
     * 数据库操作权限
     */
    DATABASE_WRITE("database.write", "数据库写", EnumSet.of(
        AgentRole.SUPERVISOR,
        AgentRole.CODE_AGENT,  // 代码库可能需要数据库操作
        AgentRole.JOURNAL_AGENT
    ));
    
    /**
     * 权限 ID
     */
    private final String permissionId;
    
    /**
     * 权限描述
     */
    private final String description;
    
    /**
     * 允许该权限的角色集合
     */
    private final Set<AgentRole> allowedRoles;
    
    Permission(String permissionId, String description, Set<AgentRole> allowedRoles) {
        this.permissionId = permissionId;
        this.description = description;
        this.allowedRoles = allowedRoles;
    }
    
    /**
     * 检查指定角色是否有该权限
     */
    public boolean isAllowedFor(AgentRole role) {
        return allowedRoles.contains(role);
    }
    
    /**
     * 检查指定角色是否被禁止该权限
     */
    public boolean isDeniedFor(AgentRole role) {
        return !isAllowedFor(role);
    }
    
    public String getPermissionId() {
        return permissionId;
    }
    
    public String getDescription() {
        return description;
    }
    
    public Set<AgentRole> getAllowedRoles() {
        return allowedRoles;
    }
    
    /**
     * 根据权限 ID 查找权限
     */
    public static Permission fromId(String permissionId) {
        if (permissionId == null || permissionId.trim().isEmpty()) {
            return null;
        }
        
        for (Permission permission : Permission.values()) {
            if (permission.permissionId.equalsIgnoreCase(permissionId)) {
                return permission;
            }
        }
        
        return null;
    }
}
