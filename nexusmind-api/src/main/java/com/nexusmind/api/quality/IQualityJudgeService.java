package com.nexusmind.api.quality;

/**
 * 内联质量评测服务契约（切片 4）。
 *
 * <p>Provider 侧由 nexusmind-agent-workers 的 {@code QualityJudgeServiceImpl}
 * 以 {@code @DubboService(interfaceClass = IQualityJudgeService.class)} 导出；
 * Consumer 侧（nexusmind-supervisor）以 {@code @DubboReference} 引用，
 * 挂在 {@code KnowledgeEvolutionService.onTaskCompleted} 钩子里<b>非阻塞</b>调用。
 *
 * <p>评分在 agent-workers 进程内执行（与 LLMClient 同进程，复用其 base-url/api-key/降级），
 * supervisor 仅远程引用、绝不本地注入实现（分层红线：Supervisor 严禁本地注入 agent 实现）。
 */
public interface IQualityJudgeService {

    /**
     * 对一次任务产出做结构化质量评分。
     *
     * @param req 评分请求（含任务类型与待评正文）
     * @return 评分结果（异常/脏数据由 Provider 侧降级为 0 分，绝不向上抛）
     */
    JudgeResult judge(JudgeRequest req);
}
