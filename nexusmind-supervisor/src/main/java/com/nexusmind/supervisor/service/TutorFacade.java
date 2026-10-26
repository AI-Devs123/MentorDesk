package com.nexusmind.supervisor.service;

import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.api.dto.JournalEntryDTO;
import com.nexusmind.api.dto.JournalReviewRequest;
import com.nexusmind.api.dto.TutorReflectionPrompt;
import com.nexusmind.api.service.IJournalReviewService;
import com.nexusmind.api.service.ITutorService;
import com.nexusmind.security.rbac.AgentRbacContext;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

/**
 * TutorAgent 门面服务 (supervisor 层 - 薄层)
 *
 * 以 Dubbo 消费者身份调用 nexusmind-agent-workers 的 ITutorService。
 *
 * 架构红线 (切片4 评审 P0-2): supervisor 严禁本地注入 agent 实现类——
 * TutorAgentServiceImpl 运行在 agent-workers 进程，本地注入会导致
 * 启动即 NoSuchBeanDefinitionException (SupervisorApplication 注释明令
 * 禁止，且 supervisor pom 依赖 agent-workers 使编译能过、运行期才炸)。
 * 必须经公共契约 + 本门面远程访问。
 */
@Service
public class TutorFacade {

    /**
     * timeout=30000: TutorAgent 内含真实 LLM 调用 (deepseek 等), 实测耗时 7~10s,
     * 默认 Dubbo 引用超时(秒级)会触发 DEADLINE_EXCEEDED, 必须显式抬高 (切片4 实测修复)。
     */
    @DubboReference(timeout = 30000)
    private ITutorService tutorService;

    /**
     * 生成苏格拉底启发反思包 (跨进程 Dubbo 调用，执行在 agent-workers)
     */
    public TutorReflectionPrompt generateSocraticReflection(String symptom, String rootCause, String domain) {
        return tutorService.generateSocraticReflection(symptom, rootCause, domain);
    }
}
