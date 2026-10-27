package com.nexusmind.agent.security;

import com.nexusmind.api.dto.CodeAnalysisRequest;
import com.nexusmind.api.service.ICodeAnalysisService;
import com.nexusmind.api.service.IJournalReviewService;
import com.nexusmind.security.rbac.AgentRbacContext;
import com.nexusmind.security.rbac.AgentRbacDubboFilter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CodeAgent 安全边界「端到端闭环」测试（切片5）。
 *
 * <p>直接驱动<b>真实</b>的 {@link AgentRbacDubboFilter}（Provider 端权威鉴权链路），
 * 以 mock {@link Invoker} 的 {@code getInterface()} 返回<b>真实</b>公共契约接口
 * （{@link IJournalReviewService} / {@link ICodeAnalysisService}），经
 * {@link AgentRbacContext} 设置角色（与生产网关同 Key {@code x-nexusmind-agent-role}），
 * 断言越权调用被 {@link RpcException#FORBIDDEN_EXCEPTION} 拦截、合规调用放行。
 *
 * <p><b>为什么有效：</b>过滤器仅从 {@code invoker.getInterface()}（即接口）读取 {@code @AgentSecured}，
 * 故本测试同时验证了「切片5 将 @AgentSecured 上提到接口层」这一修复——此前注解只挂在实现类，
 * 过滤器读不到，越权实际不被拦截（装饰性注解）。
 */
@DisplayName("CodeAgent 安全边界 E2E 测试")
class CodeAgentSecurityBoundaryE2ETest {

    private static final String ROLE_KEY = AgentRbacDubboFilter.AGENT_ROLE_KEY;

    private AgentRbacDubboFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AgentRbacDubboFilter();
        // 每个用例前清空任何残留角色上下文
        clearRole();
    }

    @AfterEach
    void tearDown() {
        clearRole();
    }

    // ---- 日记服务边界 ----

    @Test
    @DisplayName("越权阻断: codeAgent 调用日记审查服务必须被 FORBIDDEN")
    void codeAgentMustBeDeniedOnJournalService() {
        Invoker<IJournalReviewService> invoker = buildInvoker(IJournalReviewService.class);
        Invocation invocation = buildInvocation("reviewAndPersist", String.class);

        setRole("codeAgent");

        RpcException ex = assertThrows(RpcException.class,
                () -> filter.invoke(invoker, invocation),
                "codeAgent 越权调用日记服务应被拦截");
        assertEquals(RpcException.FORBIDDEN_EXCEPTION, ex.getCode(), "必须为 FORBIDDEN 异常");
        // 越权时过滤器不应放行到实际调用
        verify(invoker, never()).invoke(any());
    }

    @Test
    @DisplayName("越权阻断: tutorAgent 调用日记审查服务必须被 FORBIDDEN")
    void tutorAgentMustBeDeniedOnJournalService() {
        Invoker<IJournalReviewService> invoker = buildInvoker(IJournalReviewService.class);
        Invocation invocation = buildInvocation("reviewAndPersist", String.class);

        setRole("tutorAgent");

        RpcException ex = assertThrows(RpcException.class,
                () -> filter.invoke(invoker, invocation));
        assertEquals(RpcException.FORBIDDEN_EXCEPTION, ex.getCode());
    }

    @Test
    @DisplayName("放行: journalAgent 调用日记审查服务应成功")
    void journalAgentAllowedOnJournalService() {
        Invoker<IJournalReviewService> invoker = buildInvoker(IJournalReviewService.class);
        Invocation invocation = buildInvocation("reviewAndPersist", String.class);

        setRole("journalAgent");

        assertDoesNotThrow(() -> filter.invoke(invoker, invocation),
                "journalAgent 调用日记服务应被放行");
        verify(invoker, times(1)).invoke(invocation);
    }

    @Test
    @DisplayName("放行: supervisor 调用日记审查服务应成功")
    void supervisorAllowedOnJournalService() {
        Invoker<IJournalReviewService> invoker = buildInvoker(IJournalReviewService.class);
        Invocation invocation = buildInvocation("reviewAndPersist", String.class);

        setRole("supervisor");

        assertDoesNotThrow(() -> filter.invoke(invoker, invocation));
        verify(invoker, times(1)).invoke(invocation);
    }

    // ---- 代码分析服务边界 ----

    @Test
    @DisplayName("放行: codeAgent 调用代码分析服务应成功")
    void codeAgentAllowedOnCodeAnalysisService() {
        Invoker<ICodeAnalysisService> invoker = buildInvoker(ICodeAnalysisService.class);
        Invocation invocation = buildInvocation("analyzeProject", CodeAnalysisRequest.class);

        setRole("codeAgent");

        assertDoesNotThrow(() -> filter.invoke(invoker, invocation),
                "codeAgent 调用代码分析服务应被放行");
        verify(invoker, times(1)).invoke(invocation);
    }

    @Test
    @DisplayName("放行: supervisor 调用代码分析服务应成功")
    void supervisorAllowedOnCodeAnalysisService() {
        Invoker<ICodeAnalysisService> invoker = buildInvoker(ICodeAnalysisService.class);
        Invocation invocation = buildInvocation("analyzeProject", CodeAnalysisRequest.class);

        setRole("supervisor");

        assertDoesNotThrow(() -> filter.invoke(invoker, invocation));
        verify(invoker, times(1)).invoke(invocation);
    }

    @Test
    @DisplayName("越权阻断: journalAgent 调用代码分析服务必须被 FORBIDDEN")
    void journalAgentMustBeDeniedOnCodeAnalysisService() {
        Invoker<ICodeAnalysisService> invoker = buildInvoker(ICodeAnalysisService.class);
        Invocation invocation = buildInvocation("analyzeProject", CodeAnalysisRequest.class);

        setRole("journalAgent");

        RpcException ex = assertThrows(RpcException.class,
                () -> filter.invoke(invoker, invocation));
        assertEquals(RpcException.FORBIDDEN_EXCEPTION, ex.getCode());
        verify(invoker, never()).invoke(any());
    }

    @Test
    @DisplayName("非法角色标识必须被 FORBIDDEN（防伪造 / 枚举越界）")
    void invalidRoleMustBeDenied() {
        Invoker<IJournalReviewService> invoker = buildInvoker(IJournalReviewService.class);
        Invocation invocation = buildInvocation("reviewAndPersist", String.class);

        // 直接写入一个根本不存在的角色，绕过 AgentRbacContext 的校验
        RpcContext.getClientAttachment().setAttachment(ROLE_KEY, "root");

        RpcException ex = assertThrows(RpcException.class,
                () -> filter.invoke(invoker, invocation));
        assertEquals(RpcException.FORBIDDEN_EXCEPTION, ex.getCode());
    }

    // ---- 工具方法 ----

    private <T> Invoker<T> buildInvoker(Class<T> interfaceClass) {
        @SuppressWarnings("unchecked")
        Invoker<T> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn(interfaceClass);
        Result result = mock(Result.class);
        when(invoker.invoke(any())).thenReturn(result);
        return invoker;
    }

    private Invocation buildInvocation(String methodName, Class<?>... paramTypes) {
        Invocation invocation = mock(Invocation.class);
        when(invocation.getMethodName()).thenReturn(methodName);
        when(invocation.getParameterTypes()).thenReturn(paramTypes);
        return invocation;
    }

    /** 经生产同款入口设置角色（写入 ClientAttachment，与网关 AgentRbacContext 同 Key） */
    private void setRole(String roleId) {
        AgentRbacContext.setCurrentAgent(roleId);
    }

    private void clearRole() {
        try {
            AgentRbacContext.clearContext();
        } catch (Exception ignored) {
            // 某些环境下 clearContext 可能抛，直接清底层 attachment
        }
        try {
            RpcContext.getClientAttachment().removeAttachment(ROLE_KEY);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
