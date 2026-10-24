package com.nexusmind.security.destructive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Dubbo RPC Filter: 拦截破坏性操作
 * 在方法执行前检查Token，缺少Token时存储快照到Redis并抛出异常暂停
 */
@Component
@Activate(group = CommonConstants.PROVIDER)
public class DestructiveOperationDubboFilter implements Filter {
    
    private static final Logger log = LoggerFactory.getLogger(DestructiveOperationDubboFilter.class);
    private static final String SNAPSHOT_KEY_PREFIX = "nexusmind:snapshot:";
    private static final String CONFIRMATION_TOKEN_PREFIX = "nexusmind:token:";
    private static final long SNAPSHOT_TTL = 3600; // 1小时过期
    
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        Method method = null;
        boolean isDestructive = false;
        String operationDesc = "";
        
        try {
            String methodName = invocation.getMethodName();
            Class<?>[] paramTypes = invocation.getParameterTypes();
            
            // 首先在接口上查找方法和注解
            Class<?> ifaceClass = invoker.getInterface();
            method = findMethod(ifaceClass, methodName, paramTypes);
            
            if (method != null && method.isAnnotationPresent(DestructiveOperation.class)) {
                DestructiveOperation annotation = method.getAnnotation(DestructiveOperation.class);
                isDestructive = true;
                operationDesc = annotation.value();
                log.warn("🚨 [DestructiveOperationDubboFilter] 拦截破坏性操作: {} - {}", 
                    methodName, operationDesc);
            }
            
            // 如果没在接口找到，尝试在实现类找
            if (!isDestructive && invoker.getInterface() != null) {
                try {
                    // 从invoker的URL获取实现类信息（Dubbo会自动加载）
                    String implClassName = invoker.getInterface().getName() + "Impl";
                    try {
                        Class<?> implClass = Class.forName(implClassName);
                        method = findMethod(implClass, methodName, paramTypes);
                        if (method != null && method.isAnnotationPresent(DestructiveOperation.class)) {
                            DestructiveOperation annotation = method.getAnnotation(DestructiveOperation.class);
                            isDestructive = true;
                            operationDesc = annotation.value();
                            log.warn("🚨 [DestructiveOperationDubboFilter] 拦截破坏性操作(从实现类): {} - {}", 
                                methodName, operationDesc);
                        }
                    } catch (ClassNotFoundException ignore) {
                        // 实现类不存在，继续
                    }
                } catch (Exception ignore) {
                }
            }
            
            // 如果是破坏性操作，进行Token检查
            if (isDestructive) {
                String token = extractTokenFromInvocation(invocation);
                
                if (token == null || token.isEmpty()) {
                    log.warn("🛑 [DestructiveOperationDubboFilter] 缺少Token，触发挂起机制");
                    handleDestructiveOperationSuspension(invocation, operationDesc);
                    
                    // 抛出异常，中止RPC调用
                    throw new RpcException("破坏性操作需要确认Token，请查看Redis中的快照");
                } else {
                    log.info("✓ [DestructiveOperationDubboFilter] 检测到Token: {}, 允许执行", token);
                }
            }
            
            // 继续正常的RPC调用
            return invoker.invoke(invocation);
            
        } catch (RpcException e) {
            log.error("❌ [DestructiveOperationDubboFilter] RPC异常: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ [DestructiveOperationDubboFilter] 处理异常: {}", e.getMessage(), e);
            throw new RpcException("处理破坏性操作失败: " + e.getMessage());
        }
    }
    
    /**
     * 从RPC调用的请求对象中提取Token
     */
    private String extractTokenFromInvocation(Invocation invocation) {
        try {
            // 获取方法的第一个参数（通常是request对象）
            Object[] args = invocation.getArguments();
            if (args != null && args.length > 0) {
                Object firstArg = args[0];
                // 如果是AgentStreamRequest，尝试从中获取confirmationToken
                if (firstArg != null && firstArg.getClass().getSimpleName().equals("AgentStreamRequest")) {
                    try {
                        // 通过反射获取confirmationToken字段
                        java.lang.reflect.Method getTokenMethod = firstArg.getClass().getMethod("getConfirmationToken");
                        String token = (String) getTokenMethod.invoke(firstArg);
                        return token;
                    } catch (Exception e) {
                        log.debug("无法通过反射提取Token: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("无法从Invocation提取Token: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 处理破坏性操作的挂起
     */
    private void handleDestructiveOperationSuspension(Invocation invocation, String operationDesc) {
        try {
            if (redisTemplate == null) {
                log.warn("⚠️ [DestructiveOperationDubboFilter] Redis未注入，无法存储快照");
                return;
            }
            
            // 1. 生成执行ID和快照
            String executionId = "exec-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
            String confirmationToken = generateConfirmationToken();
            
            // 2. 构建快照数据
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("executionId", executionId);
            snapshot.put("operationDesc", operationDesc);
            snapshot.put("methodName", invocation.getMethodName());
            snapshot.put("timestamp", System.currentTimeMillis());
            snapshot.put("arguments", Arrays.toString(invocation.getArguments()));
            snapshot.put("confirmationToken", confirmationToken);
            snapshot.put("status", "PENDING_CONFIRMATION");
            
            // 3. 存入Redis
            String snapshotKey = SNAPSHOT_KEY_PREFIX + executionId;
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(snapshotKey, snapshotJson, SNAPSHOT_TTL, TimeUnit.SECONDS);
            log.info("📸 [DestructiveOperationDubboFilter] 快照已存储: key={}, ttl={}s", snapshotKey, SNAPSHOT_TTL);
            
            // 4. 存储Token-ExecutionId映射
            String tokenKey = CONFIRMATION_TOKEN_PREFIX + confirmationToken;
            redisTemplate.opsForValue().set(tokenKey, executionId, SNAPSHOT_TTL, TimeUnit.SECONDS);
            log.info("🔑 [DestructiveOperationDubboFilter] 确认Token已生成: token={}", confirmationToken);
            
        } catch (Exception e) {
            log.error("❌ [DestructiveOperationDubboFilter] 存储快照失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 生成确认Token
     */
    private String generateConfirmationToken() {
        return "token-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 12);
    }
    
    /**
     * 查找方法（处理实现类）
     */
    private Method findMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
        try {
            return clazz.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            // 尝试在接口中查找
            for (Class<?> iface : clazz.getInterfaces()) {
                try {
                    return iface.getMethod(methodName, paramTypes);
                } catch (NoSuchMethodException ignore) {
                }
            }
        }
        return null;
    }
    
    /**
     * 根据接口加载实现类（简化版，实际应用需改进）
     */
    private Class<?> loadImplementationClass(Class<?> interfaceClass) {
        // TODO: 实际应从Spring Context获取实现Bean
        return interfaceClass;
    }
}
