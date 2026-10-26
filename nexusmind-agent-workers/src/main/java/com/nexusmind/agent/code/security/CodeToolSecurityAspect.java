package com.nexusmind.agent.code.security;

import com.nexusmind.security.rbac.AgentRole;
import com.nexusmind.security.rbac.AgentSecured;
import com.nexusmind.security.rbac.AgentSecurityContextHolder;
import com.nexusmind.security.rbac.AgentSecurityException;
import org.apache.dubbo.rpc.RpcContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 工具层 RBAC 切面（防御纵深）。
 *
 * <p><b>复用既有 RBAC 基座，不另造枚举 / 注解 / 上下文 Holder / Filter。</b>
 * 真正的鉴权权威链路是 {@code AgentRbacDubboFilter}（Dubbo Provider 端，order=-9000）：
 * 它从 {@code RpcContext} 读取 {@code x-nexusmind-agent-role} 并校验服务方法的
 * {@link AgentSecured#allowedAgents()}。本切面仅作为<b>进程内纵深防御</b>，拦截
 * {@code com.nexusmind.agent.code.tool} 包下被 {@link AgentSecured} 标注的原子工具方法，
 * 防止角色上下文缺失 / 越权时工具被直接调用。
 *
 * <p><b>角色解析优先级：</b>
 * <ol>
 *   <li>生产同步链路：Dubbo Triple Provider 在 RPC 调用链中透传的角色（{@code RpcContext} 的
 *       ServerAttachment / ClientAttachment，与 {@code AgentRbacDubboFilter} 同 Key）；</li>
 *   <li>兜底 / 单测 / 异步传播：{@link AgentSecurityContextHolder}（由调用方用
 *       {@code wrap(Runnable)} 在池化 / 虚拟线程中显式传播，或单测中 {@code setRole}）。</li>
 * </ol>
 */
@Aspect
@Component
@Order(1)
public class CodeToolSecurityAspect {

    private static final Logger log = LoggerFactory.getLogger(CodeToolSecurityAspect.class);
    private static final String ROLE_KEY = "x-nexusmind-agent-role";

    @Around("@annotation(com.nexusmind.security.rbac.AgentSecured) && within(com.nexusmind.agent.code.tool..*)")
    public Object checkPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        AgentSecured agentSecured = resolveAnnotation(joinPoint);
        if (agentSecured == null) {
            // 理论上不会发生（pointcut 已限定），保守放行给后续业务逻辑
            return joinPoint.proceed();
        }

        AgentRole currentRole = resolveRole();

        if (currentRole == null) {
            String msg = "SecurityGuard 拒绝访问：未捕获到任何 Agent 角色上下文 -> "
                    + joinPoint.getSignature().toShortString();
            log.error(msg);
            throw new AgentSecurityException(msg);
        }

        boolean authorized = Arrays.asList(agentSecured.allowedAgents()).contains(currentRole.getRoleId());
        if (!authorized) {
            String msg = String.format(
                    "SecurityGuard 拦截：角色 [%s] 无权调用代码工具 [%s]，允许角色: %s",
                    currentRole.getRoleId(),
                    joinPoint.getSignature().toShortString(),
                    Arrays.toString(agentSecured.allowedAgents()));
            log.warn(msg);
            throw new AgentSecurityException(msg);
        }

        return joinPoint.proceed();
    }

    private AgentSecured resolveAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        AgentSecured annotation = method.getAnnotation(AgentSecured.class);
        if (annotation != null) {
            return annotation;
        }
        // 兜底：代理场景下可能拿到接口方法，回查目标类同名方法
        try {
            Method targetMethod = joinPoint.getTarget().getClass()
                    .getMethod(method.getName(), method.getParameterTypes());
            return targetMethod.getAnnotation(AgentSecured.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private AgentRole resolveRole() {
        // 1) 生产链路：Dubbo Triple Provider 透传的角色（与 AgentRbacDubboFilter 同 Key）
        try {
            String roleId = RpcContext.getServerAttachment().getAttachment(ROLE_KEY);
            if (isBlank(roleId)) {
                roleId = RpcContext.getClientAttachment().getAttachment(ROLE_KEY);
            }
            if (!isBlank(roleId)) {
                AgentRole role = AgentRole.fromId(roleId.trim());
                if (role != null) {
                    return role;
                }
            }
        } catch (Exception ignored) {
            // RpcContext 不可用时退回上下文桥
        }
        // 2) 兜底 / 单测 / 异步传播上下文
        return AgentSecurityContextHolder.getRole();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
