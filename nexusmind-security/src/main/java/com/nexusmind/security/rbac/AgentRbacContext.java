package com.nexusmind.security.rbac;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.rpc.RpcContext;

/**
 * Agent RBAC 上下文管理器
 * 
 * 用于在 Gateway 侧设置 Agent 身份到 Dubbo RpcContext，
 * 以便 Dubbo Filter 可以获取并进行权限检查
 * 
 * 使用流程：
 * 1. 在 Gateway 接收到 Agent 请求时调用 setCurrentAgent()
 * 2. Dubbo Filter 自动从 RpcContext 获取 Agent 身份
 * 3. 进行权限检查
 * 4. Supervisor 执行任务
 */
@Slf4j
public class AgentRbacContext {
    
    /**
     * RPC 隐式参数中的 Agent 角色键
     */
    private static final String AGENT_ROLE_KEY = "x-nexusmind-agent-role";
    
    /**
     * 设置当前 Agent 角色到 Dubbo RpcContext
     * 
     * 在 Gateway 处理请求时调用，将 Agent 身份传递给后端服务
     * 
     * @param agentRole Agent 角色对象
     * @throws IllegalArgumentException 如果 agentRole 为 null
     */
    public static void setCurrentAgent(AgentRole agentRole) {
        if (agentRole == null) {
            throw new IllegalArgumentException("Agent role cannot be null");
        }
        
        setCurrentAgent(agentRole.getRoleId());
    }
    
    /**
     * 设置当前 Agent 角色 ID 到 Dubbo RpcContext
     * 
     * 🔧 重要：在 Dubbo Triple 调用前必须设置，以确保 attachment 被传递到服务端
     * 
     * @param agentRoleId Agent 角色 ID（如 "SUPERVISOR"）
     * @throws IllegalArgumentException 如果 agentRoleId 为空
     */
    public static void setCurrentAgent(String agentRoleId) {
        if (agentRoleId == null || agentRoleId.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent role ID cannot be empty");
        }
        
        // 验证角色是否有效
        AgentRole role = AgentRole.fromId(agentRoleId);
        if (role == null) {
            throw new IllegalArgumentException("Invalid Agent role ID: " + agentRoleId);
        }
        
        try {
            // 📌 关键：必须使用 getClientAttachment() 设置，Dubbo 会自动在 RPC 调用时传递给服务端
            // 在 Dubbo Triple/gRPC 中，这个 attachment 会被序列化为 metadata 并发送给服务端
            RpcContext.getClientAttachment()
                    .setAttachment(AGENT_ROLE_KEY, agentRoleId);
            
            log.info("✅ [AgentRbacContext] 设置 Agent 角色: {} (已附加到 RpcContext.ClientAttachment)", agentRoleId);
        } catch (Exception e) {
            log.error("❌ [AgentRbacContext] 设置 Agent 角色失败", e);
            throw new RuntimeException("Failed to set Agent context", e);
        }
    }
    
    /**
     * 获取当前 Agent 角色 ID
     * 
     * @return 当前 Agent 角色 ID，如果未设置则返回 null
     */
    public static String getCurrentAgent() {
        try {
            return RpcContext.getClientAttachment()
                    .getAttachment(AGENT_ROLE_KEY);
        } catch (Exception e) {
            log.error("Failed to get Agent role from RpcContext", e);
            return null;
        }
    }
    
    /**
     * 获取当前 Agent 角色对象
     * 
     * @return 当前 Agent 角色枚举值，如果未设置或无效则返回 null
     */
    public static AgentRole getCurrentAgentRole() {
        String agentId = getCurrentAgent();
        if (agentId == null) {
            return null;
        }
        
        return AgentRole.fromId(agentId);
    }
    
    /**
     * 检查当前 Agent 是否具有指定权限
     * 
     * @param permission 要检查的权限
     * @return 如果有权限则返回 true，否则返回 false
     */
    public static boolean hasPermission(Permission permission) {
        AgentRole currentRole = getCurrentAgentRole();
        if (currentRole == null) {
            return false;
        }
        
        return permission.isAllowedFor(currentRole);
    }
    
    /**
     * 检查当前 Agent 是否属于指定角色
     * 
     * @param requiredRole 要检查的角色
     * @return 如果当前 Agent 为指定角色则返回 true，否则返回 false
     */
    public static boolean isCurrentRole(AgentRole requiredRole) {
        AgentRole currentRole = getCurrentAgentRole();
        return currentRole == requiredRole;
    }
    
    /**
     * 检查当前 Agent 是否属于指定角色之一
     * 
     * @param roles 要检查的角色数组
     * @return 如果当前 Agent 为指定角色之一则返回 true，否则返回 false
     */
    public static boolean isCurrentRoleIn(AgentRole... roles) {
        AgentRole currentRole = getCurrentAgentRole();
        if (currentRole == null) {
            return false;
        }
        
        for (AgentRole role : roles) {
            if (currentRole == role) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 清除当前 Agent 上下文
     * 
     * 通常在请求处理完成后调用
     */
    public static void clearContext() {
        try {
            RpcContext.getClientAttachment()
                    .removeAttachment(AGENT_ROLE_KEY);
            
            log.debug("Cleared Agent context");
        } catch (Exception e) {
            log.error("Failed to clear Agent context", e);
        }
    }
}
