package com.nexusmind.supervisor.service;

import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.api.dto.JournalEntryDTO;
import com.nexusmind.api.dto.JournalReviewRequest;
import com.nexusmind.api.dto.TutorReflectionPrompt;
import com.nexusmind.api.service.IJournalReviewService;
import com.nexusmind.api.service.ITutorService;
import com.nexusmind.supervisor.evolution.KnowledgeAssociation;
import com.nexusmind.supervisor.evolution.KnowledgeEvolutionService;
import com.nexusmind.supervisor.evolution.TaskCompletionEvent;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(TutorFacade.class);

    /**
     * timeout=30000: TutorAgent 内含真实 LLM 调用 (deepseek 等), 实测耗时 7~10s,
     * 默认 Dubbo 引用超时(秒级)会触发 DEADLINE_EXCEEDED, 必须显式抬高 (切片4 实测修复)。
     */
    // 切片5：Triple 协议强制 fastjson2，规避 Hessian2 反序列化 record DTO 崩溃
    @DubboReference(timeout = 30000, parameters = {"serialization=fastjson2"})
    private ITutorService tutorService;

    /**
     * 切片 8-1：知识自进化后置钩子。苏格拉底反思包生成后异步发起相似度扫描，非阻塞返回。
     */
    @Autowired(required = false)
    private KnowledgeEvolutionService evolutionService;

    /**
     * 生成苏格拉底启发反思包 (跨进程 Dubbo 调用，执行在 agent-workers)。
     *
     * <p>切片 2 回灌：在拿到反思包后，best-effort 调知识自进化服务查询与本次上下文相关的
     * 历史关联点，注入 {@link TutorReflectionPrompt#knowledgeLinks()} 槽位（复用 API 已预留结构），
     * 驱动「个人知识网络持续自进化」。失败或为空均返回原始 prompt，绝不拖垮主链路。
     */
    public TutorReflectionPrompt generateSocraticReflection(String symptom, String rootCause, String domain) {
        TutorReflectionPrompt prompt = tutorService.generateSocraticReflection(symptom, rootCause, domain);
        if (evolutionService != null) {
            evolutionService.onTaskCompleted(TaskCompletionEvent.forTutor(prompt));
            try {
                List<KnowledgeAssociation> links = evolutionService.findRelatedInsights(prompt.contextSummary());
                if (!links.isEmpty()) {
                    List<TutorReflectionPrompt.KnowledgeLink> knowledgeLinks = links.stream()
                            .map(a -> new TutorReflectionPrompt.KnowledgeLink(
                                    a.relatedDocId(),
                                    a.relationType(),
                                    "历史关联洞察：" + a.relatedDocId() + "（" + a.relationType()
                                            + "，稠密rank=" + a.denseRank() + "，稀疏rank=" + a.sparseRank() + "）"))
                            .collect(Collectors.toList());
                    prompt = new TutorReflectionPrompt(
                            prompt.promptId(), prompt.targetDomain(), prompt.contextSummary(),
                            prompt.coreConflict(), prompt.questions(), knowledgeLinks);
                }
            } catch (Exception e) {
                log.warn("[KNOWLEDGE_LINK] Tutor 提示词回灌失败(非阻断): {}", e.getMessage());
            }
        }
        return prompt;
    }
}
