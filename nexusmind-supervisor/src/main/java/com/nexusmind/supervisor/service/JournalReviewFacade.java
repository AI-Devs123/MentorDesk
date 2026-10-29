package com.nexusmind.supervisor.service;

import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.api.service.IJournalReviewService;
import com.nexusmind.supervisor.evolution.KnowledgeEvolutionService;
import com.nexusmind.supervisor.evolution.TaskCompletionEvent;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 日记审查门面服务 (supervisor 层 - 薄层)
 *
 * 职责:
 * - 以 Dubbo 消费者身份调用 nexusmind-agent-workers 的核心服务
 * - 暴露给 Controller 层
 * - 不涉及核心业务逻辑 (那些都在 agent-workers 进程中执行)
 *
 * 注意: 这是一个门面(Facade) 模式的实现，用于维持层级分离。
 * 注入方式为 @DubboReference（原为 @Autowired 本地注入）：
 * 本地注入会把 agent 的类加载进 supervisor 进程，导致"日记执行实际跑在
 * supervisor 里、落盘落 supervisor 工作目录、llm 配置读 supervisor 的 yml"。
 * 改为远程引用后，执行、落盘、配置读取全部回归 agent-workers 进程。
 */
@Service
public class JournalReviewFacade {

    /**
     * Dubbo 引用 agent-workers 的核心服务（公共 API 契约接口）。
     * 服务方在 JournalReviewServiceImpl 上以
     * @DubboService(interfaceClass = IJournalReviewService.class) 导出。
     */
    /**
     * timeout=30000: reviewAndPersist 内含真实 LLM 三要素抽取, 实测耗时 5s+,
     * 默认 Dubbo 引用超时(秒级)会触发 DEADLINE_EXCEEDED, 必须显式抬高 (切片4 实测修复)。
     */
    @DubboReference(timeout = 30000)
    private IJournalReviewService journalReviewService;

    /**
     * 切片 8-1：知识自进化后置钩子。任务成功落盘后 fire-and-forget 发起相似度扫描，
     * 不阻塞本方法返回（非阻塞 SSE 链路）。钩子内部对所有异常 best-effort 容错。
     */
    @Autowired(required = false)
    private KnowledgeEvolutionService evolutionService;

    /**
     * 审查日记并落盘
     * 委托给 agent-workers 的实现（跨进程 Dubbo 调用）
     *
     * @param rawContent 原始日记内容
     * @return 包含条目、路径、统计信息的数据载体
     */
    public DataPayload reviewAndPersist(String rawContent) {
        DataPayload payload = journalReviewService.reviewAndPersist(rawContent);
        if (evolutionService != null) {
            evolutionService.onTaskCompleted(TaskCompletionEvent.forJournal(rawContent));
        }
        return payload;
    }
}
