package com.nexusmind.security.rbac;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Agent RBAC Dubbo Filter
 * 
 * 在 Dubbo 服务端调用前进行权限检查，确保只有授权的 Agent 才能调用特定服务/方法
 * 
 * 工作流程：
 * 1. 从 RpcContext 获取 Agent 身份标识
 * 2. 查找目标方法的 @AgentSecured 注解
 * 3. 检查当前 Agent 是否在允许列表中
 * 4. 如果通过检查，继续调用；否则抛出权限异常
 * 
 * 约束条件：
 * - 仅在 Dubbo Provider 侧（服务端）工作
 * - 优先级设置为 -9000（在其他 Filter 之前执行）
 * - 必须在 RpcContext 中包含 Agent 身份信息
 */
@Slf4j
@Activate(group = CommonConstants.PROVIDER, order = -9000)
public class AgentRbacDubboFilter implements Filter {
    
    /**
     * RPC 隐式参数中的 Agent 角色键
     * 
     * 客户端需要在调用时设置：
     * RpcContext.getClientContext().setAttachment(AGENT_ROLE_KEY, "codeAgent");
     */
    public static final String AGENT_ROLE_KEY = "x-nexusmind-agent-role";
    
    /**
     * 拦截 Dubbo 调用并进行权限检查
     * 
     * @param invoker 调用对象，包含服务信息
     * @param invocation 调用信息，包含方法名和参数
     * @return 调用结果
     * @throws RpcException 当权限检查失败时抛出
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        
        try {
            log.info("🔍 [RBAC-Filter] 拦截RPC调用: interface={}, method={}", 
                invoker.getInterface().getSimpleName(), invocation.getMethodName());
            
            // 1. 首先尝试从 ServerAttachment 获取（标准方式）
            String currentAgentId = RpcContext.getServerAttachment()
                    .getAttachment(AGENT_ROLE_KEY);
            
            log.debug("🔍 [RBAC-Filter] 尝试从 ServerAttachment 提取 Agent 身份: {}", 
                currentAgentId != null ? currentAgentId : "null");
            
            // 备用通道：如果 ServerAttachment 中没有，尝试 ClientAttachment
            // 这是解决 Dubbo Triple gRPC attachment 传递不稳定的必要手段
            // 在正常流程中应该优先使用 ServerAttachment
            if (currentAgentId == null || currentAgentId.trim().isEmpty()) {
                currentAgentId = RpcContext.getClientAttachment()
                        .getAttachment(AGENT_ROLE_KEY);
                
                if (currentAgentId != null && !currentAgentId.trim().isEmpty()) {
                    log.info("✅ [RBAC-Filter] 从 ClientAttachment 备用通道获取 Agent 身份: {}", currentAgentId);
                } else {
                    log.debug("🔍 [RBAC-Filter] ClientAttachment 也未找到 Agent 身份");
                }
            }
            
            log.info("🔍 [RBAC-Filter] 最终提取的 Agent 身份: {}", 
                currentAgentId != null ? currentAgentId : "null");
            
            if (currentAgentId == null || currentAgentId.trim().isEmpty()) {
                // 如果还是未设置，允许继续（可能是非 Agent 调用或测试调用）
                log.debug("ℹ️ [RBAC-Filter] 未找到 Agent 身份，允许继续");
                return invoker.invoke(invocation);
            }
            
            // 3. 转换为 AgentRole 枚举
            AgentRole currentRole = AgentRole.fromId(currentAgentId);
            if (currentRole == null) {
                log.error("Invalid Agent role ID: {}", currentAgentId);
                throw new RpcException(RpcException.FORBIDDEN_EXCEPTION,
                        "SecurityGuard RBAC 拦截: 无效的 Agent 角色标识 [" + currentAgentId + "]");
            }
            
            // 4. 查找目标方法
            Method method = findMethod(
                    invoker.getInterface(),
                    invocation.getMethodName(),
                    invocation.getParameterTypes()
            );
            
            if (method == null) {
                log.warn("Method not found: {}::{}",
                        invoker.getInterface().getName(),
                        invocation.getMethodName());
                return invoker.invoke(invocation);
            }
            
            // 5. 查找 @AgentSecured 注解
            AgentSecured secured = method.getAnnotation(AgentSecured.class);
            if (secured == null) {
                // 尝试从类级别获取注解
                secured = invoker.getInterface().getAnnotation(AgentSecured.class);
            }
            
            // 6. 如果方法没有 @AgentSecured 注解，允许调用
            if (secured == null) {
                log.debug("No @AgentSecured annotation found, allowing call");
                return invoker.invoke(invocation);
            }
            
            // 7. 检查 Agent 是否在允许列表中
            boolean authorized = Arrays.asList(secured.allowedAgents())
                    .contains(currentAgentId);
            
            if (!authorized) {
                String errorMsg = !secured.errorMessage().isEmpty()
                        ? secured.errorMessage()
                        : String.format(
                            "SecurityGuard RBAC 拦截: Agent [%s] 无权访问 [%s::%s]，允许的角色: %s",
                            currentAgentId,
                            invoker.getInterface().getSimpleName(),
                            invocation.getMethodName(),
                            Arrays.toString(secured.allowedAgents())
                        );
                
                log.error(errorMsg);
                throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, errorMsg);
            }
            
            // 8. 如果需要审计，记录调用
            if (secured.audit()) {
                log.info("AUDIT: Agent [{}] called [{}::{}]",
                        currentAgentId,
                        invoker.getInterface().getSimpleName(),
                        invocation.getMethodName());
            }
            
            log.debug("Authorization passed: Agent [{}] can access [{}::{}]",
                    currentAgentId,
                    invoker.getInterface().getSimpleName(),
                    invocation.getMethodName());
            
            // 9. 权限检查通过，继续调用
            return invoker.invoke(invocation);
            
        } catch (RpcException e) {
            // RPC 异常直接抛出
            throw e;
        } catch (Exception e) {
            // 其他异常包装为 RPC 异常
            log.error("Unexpected error in RBAC filter", e);
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, 
                    "SecurityGuard RBAC 检查异常: " + e.getMessage(), e);
        }
    }
    
    /**
     * 根据方法名和参数类型查找方法
     * 
     * @param clazz 目标类
     * @param methodName 方法名
     * @param parameterTypes 参数类型数组
     * @return 找到的方法，如果未找到则返回 null
     */
    private Method findMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes) {
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            log.warn("Method not found: {}::{}({})",
                    clazz.getName(),
                    methodName,
                    Arrays.toString(parameterTypes));
            return null;
        }
    }
}
