package com.nexusmind.security.destructive;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 破坏性操作 AOP 拦截切面
 * 在标记了 @DestructiveOperation 的方法执行前拦截，生成快照并抛出挂起异常
 */
@Aspect
@Component
public class DestructiveActionAdvisor {
    private static final Logger log = LoggerFactory.getLogger(DestructiveActionAdvisor.class);

    /**
     * 环绕通知：拦截所有标记 @DestructiveOperation 的方法
     */
    @Around("@annotation(com.nexusmind.security.destructive.DestructiveOperation)")
    public Object interceptDestructiveOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DestructiveOperation annotation = method.getAnnotation(DestructiveOperation.class);

        log.warn("⚠️ [DestructiveActionAdvisor] 切面拦截触发!");
        log.warn("  方法: {}", method.getName());
        log.warn("  类: {}", method.getDeclaringClass().getName());
        log.warn("  代理类型: {}", joinPoint.getTarget().getClass().getName());

        // 核心机制：优先检查 TaskContextHolder 中的恢复令牌
        // 如果当前上下文中携带恢复令牌，说明已通过审批，直接放行底层真实逻辑
        String activeToken = TaskContextHolder.getResumeToken();
        if (activeToken != null && !activeToken.isBlank()) {
            log.info("✅ [切面放行] 检测到恢复令牌，允许执行破坏性操作: {}", method.getName());
            return joinPoint.proceed();
        }



        // 首次触发：生成快照并抛出挂起异常
        log.warn("⚠️ [DestructiveActionAdvisor] 拦截破坏性操作: {} - 类型: {}, 风险等级: {}", 
                 method.getName(), annotation.type(), annotation.riskLevel());

        ExecutionSnapshot snapshot = buildSnapshot(joinPoint, annotation);
        log.warn("⚠️ [DestructiveActionAdvisor] 即将抛出 ExecutionSuspendedException，taskId={}", snapshot.getTaskId());
        throw new ExecutionSuspendedException(snapshot);
    }

    /**
     * 构建执行快照
     */
    private ExecutionSnapshot buildSnapshot(ProceedingJoinPoint joinPoint, DestructiveOperation annotation) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = signature.getParameterNames();

        ExecutionSnapshot snapshot = new ExecutionSnapshot();
        snapshot.setTaskId(UUID.randomUUID().toString());
        snapshot.setNodeId("node-" + method.getName());
        snapshot.setToolSignature(method.getDeclaringClass().getName() + "." + method.getName());
        
        // 组装方法参数
        Map<String, Object> toolArguments = new HashMap<>();
        for (int i = 0; i < paramNames.length; i++) {
            toolArguments.put(paramNames[i], args[i]);
        }
        snapshot.setToolArguments(toolArguments);
        
        // 设置操作元数据
        snapshot.setOperationType(annotation.type());
        snapshot.setRiskLevel(annotation.riskLevel());
        snapshot.setOperationDescription(
            annotation.description().isEmpty() 
                ? annotation.type().getDescription() 
                : annotation.description()
        );
        
        // 生成变更预览
        if (annotation.requiresDiff()) {
            snapshot.setChangePreview(generateChangePreview(annotation.type(), toolArguments));
        }
        
        // 生成一次性确认令牌
        snapshot.setConfirmationToken(UUID.randomUUID().toString());
        
        // TODO: 实现从 DAG 上下文提取上下文变量的完整版本
        // 当前为简化实现，仅设置空容器
        snapshot.setContextVariables(new HashMap<>());

        return snapshot;
    }

    /**
     * 生成变更预览（简化实现）
     * TODO: 升级为完整的 Diff 生成逻辑，支持标准 unified diff 格式
     * 计划版本：2.0.0
     * 
     * @deprecated 当前为简化版，仅提供基本字符串拼接，不支持真实 Diff 对比
     */
    @Deprecated(since = "1.0.0")
    private String generateChangePreview(DestructiveOperationType type, Map<String, Object> args) {
        return switch (type) {
            case FILE_DELETE -> "将删除文件: " + args.getOrDefault("filePath", "unknown");
            case FILE_OVERWRITE -> "将覆盖文件: " + args.getOrDefault("filePath", "unknown");
            case GIT_FORCE_RESET -> "将强制重置 Git 仓库到: " + args.getOrDefault("commitHash", "HEAD");
            case SYSTEM_COMMAND -> "将执行系统命令: " + args.getOrDefault("command", "unknown");
        };
    }

    /**
     * 从方法参数中提取确认令牌（已弃用）
     * @deprecated 使用 TaskContextHolder 中的恢复令牌替代，该方法将在下个大版本移除
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    private String extractConfirmationToken(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof String str && str.startsWith("token:")) {
                return str.substring(6);
            }
        }
        return null;
    }
}
