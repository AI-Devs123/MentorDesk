package com.nexusmind.supervisor.evolution;

import com.nexusmind.api.observability.LfTracingClient;
import com.nexusmind.api.observability.LfTraceContext;
import com.nexusmind.api.quality.IQualityJudgeService;
import com.nexusmind.api.quality.JudgeRequest;
import com.nexusmind.api.quality.JudgeResult;
import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;
import com.nexusmind.api.rag.service.IHybridSearchService;
import com.nexusmind.security.rbac.AgentRbacContext;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 知识自进化服务（切片 1 核心）。
 *
 * <p>作为「后置钩子」挂载到各编排入口返回之后：每当 Journal / Tutor / Code / WebCurator
 * 任务成功完成，即以<b>异步、非阻塞、best-effort</b>方式对当次产出的上下文做一次
 * 混合检索相似度探测，验证「自进化中枢」的扫描通路。
 *
 * <h3>非阻塞铁律（对齐 W3 二阶段确认与流式生命周期设计）</h3>
 * <ul>
 *   <li>主链路（SSE / Dubbo 编排）调用 {@link #onTaskCompleted} 后<b>立即返回</b>，
 *       扫描在虚拟线程中异步执行，绝不占用请求线程，避免 TTFT 雪崩。</li>
 *   <li>扫描全程 try-catch，任何失败仅告警，<b>绝不向上抛出</b>——监控钩子绝不能拖垮业务。</li>
 *   <li>调用 rag 前在<b>同一线程</b>设置 {@code supervisor} 角色 attachment
 *       （{@link AgentRbacContext} 基于 Dubbo RpcContext 的 ThreadLocal），
 *       否则会被 rag 的 {@code AgentRbacDubboFilter} 拒绝。</li>
 * </ul>
 *
 * <p>切片 1 完成「扫描 + 日志 + Redis 暂存」；切片 2 在此之上叠加<b>新旧知识关联点抽取</b>
 * （{@link #extractAssociations}，Rank + bothHit 启发式，绕开无效的 rrfScore 阈值）
 * 与 {@code KNOWLEDGE_LINK} 回灌。
 */
@Service
public class KnowledgeEvolutionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEvolutionService.class);
    private static final int DEFAULT_TOP_K = 10;
    private static final int DEFAULT_FUSED_TOP_N = 5;

    /**
     * 远程引用 nexusmind-rag 的混合检索服务。
     * 关键：携带 {@code serialization=fastjson2} 规避 Dubbo 3.2 hessian2 反序列化 Java record
     * 崩溃（W7 切片 5 已证实）；{@code check=false} 使 supervisor 在 rag 未就绪时仍可启动。
     */
    @DubboReference(protocol = "tri", version = "1.0.0", check = false,
            parameters = {"serialization=fastjson2"})
    private IHybridSearchService hybridSearchService;

    @Autowired(required = false)
    private EvolutionScanRepository scanRepository;

    @Autowired(required = false)
    @Qualifier("evolutionExecutor")
    private Executor evolutionExecutor;

    /**
     * 远程引用 agent-workers 的内联质量评测服务（切片 4）。
     * 关键：{@code check=false} 使 agent-workers 未就绪时 supervisor 仍可启动；
     * 评分失败/缺失时主链路零感知（best-effort 范式）。
     */
    @DubboReference(protocol = "tri", version = "1.0.0", check = false,
            parameters = {"serialization=fastjson2"})
    private IQualityJudgeService judgeService;

    /**
     * 可选的 Langfuse 探针（切片 3）。评分结果作为 trace 的 score 观测，
     * 缺失或关闭时完全跳过（{@code required=false} 降级）。
     */
    @Autowired(required = false)
    private LfTracingClient tracingClient;

    @Value("${nexusmind.evolution.enabled:true}")
    private boolean enabled = true;

    /**
     * 关联窗口（切片 2 路径 A）：稠密与稀疏两路 rank 均 ≤ 此值且 bothHit 才视为关联点。
     * 经 Nacos 下发，便于实测后校准（命中过多→收紧，过少→放宽或切到路径 B rerank）。
     */
    @Value("${nexusmind.evolution.rank-window:3}")
    private int rankWindow = 3;

    /**
     * 任务完成钩子入口（fire-and-forget，非阻塞）。
     *
     * @param event 本次任务完成事件（含产出上下文与目标集合）
     */
    public void onTaskCompleted(TaskCompletionEvent event) {
        if (!enabled) {
            log.debug("[EVOLUTION_SCAN] 已禁用，跳过: task={}", event.taskId());
            return;
        }
        // 捕获切面线程上的 traceId（LangfuseTraceAspect 已 set），供后续非阻塞评分上报 score 使用
        final String traceId = LfTraceContext.getTraceId();
        // 无虚拟线程执行器时退化为同步直跑（保证在最小化环境也能验证通路）
        Executor ex = (evolutionExecutor != null) ? evolutionExecutor : (r -> r.run());
        CompletableFuture.runAsync(() -> {
            try {
                scan(event, traceId, ex);
            } catch (Exception e) {
                log.warn("[EVOLUTION_SCAN] 异步扫描异常(非阻断): task={}, error={}",
                        event.taskId(), e.getMessage());
            }
        }, ex);
    }

    /**
     * 执行一次相似度探测 + 关联点抽取（同步逻辑，由 {@link #onTaskCompleted} 调度）。
     *
     * @param traceId 上游编排切面注入的 Langfuse traceId（可能为空，仅用于 score 上报）
     * @param ex      异步执行器（judge 任务复用，避免阻塞主链路）
     */
    void scan(TaskCompletionEvent event, String traceId, Executor ex) {
        try {
            // 必须在发起 RPC 的当前线程设置角色 attachment（RpcContext 为 ThreadLocal）
            AgentRbacContext.setCurrentAgent("supervisor");

            HybridSearchRequest req = new HybridSearchRequest(
                    event.producedContent(), event.collection(),
                    DEFAULT_TOP_K, DEFAULT_FUSED_TOP_N, false);
            HybridSearchResponse resp = hybridSearchService.hybridSearch(req);

            int count = (resp != null && resp.candidates() != null) ? resp.candidates().size() : 0;
            String topDocId = "N/A";
            float topScore = 0f;
            if (count > 0) {
                topDocId = resp.candidates().get(0).docId();
                topScore = resp.candidates().get(0).rrfScore();
            }

            long now = System.currentTimeMillis();
            // 切片 2：新旧知识关联点抽取（Rank + bothHit 启发式）
            List<KnowledgeAssociation> associations =
                    extractAssociations(resp != null ? resp.candidates() : null,
                            event.newDocId(), event.collection(), now);

            if (associations.isEmpty()) {
                log.info("[EVOLUTION_SCAN] task={}, agent={}, collection={}, 候选命中={}, topDoc={}, topRrf={}, 关联点=0",
                        event.taskId(), event.agentRole(), event.collection(),
                        count, topDocId, topScore);
            } else {
                String links = associations.stream()
                        .map(a -> a.relatedDocId() + "(" + a.relationType() + ",rrf=" + a.rrfScore() + ")")
                        .collect(Collectors.joining(", "));
                log.info("[KNOWLEDGE_LINK] task={}, 发现关联点 {} 个: {}",
                        event.taskId(), associations.size(), links);
            }

            if (scanRepository != null) {
                // 独立索引：以新资产 docId 反查（供下一任务预加载；newDocId 为 null 时静默跳过）
                if (event.newDocId() != null && !associations.isEmpty()) {
                    scanRepository.saveLinks(event.newDocId(), associations);
                }
                scanRepository.save(new EvolutionScanState(
                        event.taskId(), event.agentRole(), event.collection(),
                        event.producedContent(), count, topDocId, topScore,
                        associations, now));
            }

            // 切片 4：扫描完成后，非阻塞提交一次内联质量评分（复用同一 executor，绝不阻塞主链路）
            if (judgeService != null && event.producedContent() != null
                    && !event.producedContent().isBlank()) {
                final String content = event.producedContent();
                final String taskType = mapTaskType(event.agentRole());
                final String taskId = event.taskId();
                CompletableFuture.runAsync(() -> {
                    try {
                        JudgeResult jr = judgeService.judge(new JudgeRequest(taskType, content, null, null));
                        if (scanRepository != null) {
                            scanRepository.saveQuality(taskId, jr);
                        }
                        if (tracingClient != null && traceId != null) {
                            tracingClient.score(traceId, null, "quality:" + taskType,
                                    jr.score(), jr.rationale());
                        }
                    } catch (Exception e) {
                        log.warn("[QUALITY_JUDGE] 非阻断评分失败: task={}, err={}",
                                taskId, e.getMessage());
                    }
                }, ex);
            }
        } catch (Exception e) {
            log.warn("[EVOLUTION_SCAN] 检索探测失败(非阻断): task={}, error={}",
                    event.taskId(), e.getMessage());
        } finally {
            AgentRbacContext.clearContext();
        }
    }

    /**
     * 将 Agent 角色映射为质量评测的 taskType（驱动 judge rubric 选择）。
     */
    private static String mapTaskType(String agentRole) {
        return switch (agentRole == null ? "" : agentRole) {
            case "codeAgent" -> "code";
            case "webCurator" -> "web_kb";
            case "tutorAgent" -> "tutor";
            case "journalAgent" -> "journal";
            default -> "general";
        };
    }

    /**
     * 新旧知识关联点抽取（切片 2 路径 A：Rank + bothHit 启发式）。
     *
     * <p><b>为何不用 rrfScore 阈值</b>：{@code FusedCandidate.rrfScore} 仅 ≈ 0~0.033，
     * 非归一化相似度，原计划的 0.65~0.85 阈值带对它永远不命中。此处改用更稳的信号：
     * <ol>
     *   <li>{@code bothHit()} —— 稠密（语义）+ 稀疏（字面）两路都召回，融合质量最可信；</li>
     *   <li>{@code denseRank ≤ rankWindow 且 sparseRank ≤ rankWindow} —— 两路都进前 N；</li>
     *   <li>排除与自身 {@code newDocId} 相同的候选，避免自关联。</li>
     * </ol>
     * 纯函数、无副作用、零额外推理，异步非阻塞不新增延迟。
     */
    List<KnowledgeAssociation> extractAssociations(List<FusedCandidate> candidates,
                                                   String newDocId, String collection, long discoveredAt) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<KnowledgeAssociation> result = new ArrayList<>();
        for (FusedCandidate c : candidates) {
            if (!c.bothHit()) {
                continue; // 仅单路命中，相关性不足信
            }
            if (c.denseRank() > rankWindow || c.sparseRank() > rankWindow) {
                continue; // 任一通道排名超出窗口
            }
            if (newDocId != null && newDocId.equals(c.docId())) {
                continue; // 排除自关联
            }
            result.add(KnowledgeAssociation.of(newDocId, c, "BOTH_HIT_TOP" + rankWindow, discoveredAt));
        }
        return result;
    }

    /**
     * 回灌查询（切片 2 Tutor 回灌用）：基于给定摘要做一次 RRF 扫描并抽取关联点。
     * best-effort——任何失败返回空列表，绝不拖垮调用方主链路。
     *
     * @param querySummary 本次上下文摘要（如 TutorReflectionPrompt.contextSummary）
     * @param collection   目标集合（默认 journal）
     * @return 关联点列表（无则空）
     */
    public List<KnowledgeAssociation> findRelatedInsights(String querySummary, String collection) {
        if (!enabled || querySummary == null || querySummary.isBlank()) {
            return List.of();
        }
        try {
            AgentRbacContext.setCurrentAgent("supervisor");
            HybridSearchRequest req = new HybridSearchRequest(
                    querySummary, collection, DEFAULT_TOP_K, DEFAULT_FUSED_TOP_N, false);
            HybridSearchResponse resp = hybridSearchService.hybridSearch(req);
            long now = System.currentTimeMillis();
            return extractAssociations(resp != null ? resp.candidates() : null, null, collection, now);
        } catch (Exception e) {
            log.warn("[KNOWLEDGE_LINK] 回灌查询失败(非阻断): query={}, error={}",
                    querySummary, e.getMessage());
            return List.of();
        } finally {
            AgentRbacContext.clearContext();
        }
    }

    /** 回灌查询便捷重载：默认 journal 集合。 */
    public List<KnowledgeAssociation> findRelatedInsights(String querySummary) {
        return findRelatedInsights(querySummary, "journal");
    }

    // ── 测试注入点（手动构造 + mock，不依赖 Nacos/Dubbo 运行时） ──
    void setHybridSearchService(IHybridSearchService s) {
        this.hybridSearchService = s;
    }

    void setEvolutionExecutor(Executor ex) {
        this.evolutionExecutor = ex;
    }

    void setScanRepository(EvolutionScanRepository r) {
        this.scanRepository = r;
    }

    void setEnabled(boolean b) {
        this.enabled = b;
    }

    void setRankWindow(int w) {
        this.rankWindow = w;
    }

    /** 测试注入点：内联质量评测服务（null 时 judge 完全跳过）。 */
    void setJudgeService(IQualityJudgeService s) {
        this.judgeService = s;
    }

    /** 测试注入点：Langfuse 探针（null 时 score 上报跳过）。 */
    void setTracingClient(LfTracingClient c) {
        this.tracingClient = c;
    }
}
