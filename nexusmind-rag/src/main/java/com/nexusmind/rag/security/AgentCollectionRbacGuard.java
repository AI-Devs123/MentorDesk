package com.nexusmind.rag.security;

import com.nexusmind.security.rbac.AgentRbacDubboFilter;
import com.nexusmind.security.rbac.AgentRole;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 集合级 RBAC 守卫（切片 3 新增）。
 *
 * <p>现有 {@link AgentRbacDubboFilter} 只做方法级 {@code @AgentSecured} 校验，不感知 collection 维度。
 * 本守卫在 Provider 业务方法内，依据「角色 → 允许访问的 collection 白名单」做更细粒度的越权拦截，
 * 防止某个 Agent 越权扫描不属于它的知识集合。
 *
 * <p><b>密钥一致性（修复点）</b>：复用现有 {@link AgentRbacDubboFilter#AGENT_ROLE_KEY}
 * （{@code x-nexusmind-agent-role}），与全系统网关/Filter 同源，避免 JSON 草稿中自创
 * {@code X-Agent-Role} 导致生产全链路鉴权失效。
 *
 * <p>角色取值经 {@link AgentRole#fromId(String)} 归一化，兼容大小写（如 {@code Supervisor} → {@code supervisor}）。
 */
@Component
public class AgentCollectionRbacGuard {

    /**
     * 与全系统一致的 RPC 隐式参数键（来自 AgentRbacDubboFilter）。
     */
    public static final String ATTACHMENT_ROLE_KEY = AgentRbacDubboFilter.AGENT_ROLE_KEY;

    /**
     * 角色 → 允许访问的 collection 白名单（严格对齐 AgentRole 的 roleId）。
     */
    private static final Map<String, Set<String>> ROLE_COLLECTION_PERMISSIONS = Map.of(
            "supervisor", Set.of("journal", "code", "web_kb"),
            "journalAgent", Set.of("journal"),
            "codeAgent", Set.of("code", "web_kb"),
            "webCurator", Set.of("web_kb"),
            "tutorAgent", Set.of("journal", "code", "web_kb")
    );

    /**
     * 校验当前发起 Agent 是否有权访问目标 collection。
     *
     * @param collection 目标集合名
     * @throws SecurityException 未携带身份、身份非法、或不在白名单内时抛出（403 语义）
     */
    public void validateAccess(String collection) {
        AgentRole role = resolveRole();
        if (role == null) {
            throw new SecurityException("SecurityGuard 拦截：未携带或无效的 Agent 身份上下文 (" + ATTACHMENT_ROLE_KEY + ")");
        }
        Set<String> allowed = ROLE_COLLECTION_PERMISSIONS.get(role.getRoleId());
        if (allowed == null || !allowed.contains(collection)) {
            throw new SecurityException(String.format(
                    "SecurityGuard 拦截：Agent 角色 [%s] 无权访问集合 [%s]，允许的集合: %s",
                    role.getRoleId(), collection, allowed == null ? "无" : allowed));
        }
    }

    /**
     * 从 RPC 上下文解析角色：优先 ServerAttachment（标准 Triple 入站通道），
     * 失败回退 ClientAttachment（与现有 AgentRbacDubboFilter 的容错策略一致）。
     */
    private AgentRole resolveRole() {
        String raw = RpcContext.getServerAttachment().getAttachment(ATTACHMENT_ROLE_KEY);
        if (raw == null || raw.isBlank()) {
            raw = RpcContext.getClientAttachment().getAttachment(ATTACHMENT_ROLE_KEY);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return AgentRole.fromId(raw);
    }
}
