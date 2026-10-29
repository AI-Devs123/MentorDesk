package com.nexusmind.supervisor.evolution;

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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
 * <p>切片 1 只完成「扫描 + 日志 + Redis 暂存」；切片 2 将在此之上叠加 0.65~0.85 阈值带的
 * 新旧知识关联点抽取与 {@code KNOWLEDGE_LINK} 反哺。
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

    @Value("${nexusmind.evolution.enabled:true}")
    private boolean enabled = true;

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
        // 无虚拟线程执行器时退化为同步直跑（保证在最小化环境也能验证通路）
        Executor ex = (evolutionExecutor != null) ? evolutionExecutor : (r -> r.run());
        CompletableFuture.runAsync(() -> {
            try {
                scan(event);
            } catch (Exception e) {
                log.warn("[EVOLUTION_SCAN] 异步扫描异常(非阻断): task={}, error={}",
                        event.taskId(), e.getMessage());
            }
        }, ex);
    }

    /**
     * 执行一次相似度探测（同步逻辑，由 {@link #onTaskCompleted} 调度）。
     */
    void scan(TaskCompletionEvent event) {
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

            log.info("[EVOLUTION_SCAN] task={}, agent={}, collection={}, 候选命中={}, topDoc={}, topRrf={}, 耗时={}ms",
                    event.taskId(), event.agentRole(), event.collection(),
                    count, topDocId, topScore, resp != null ? resp.elapsedMs() : -1);

            if (scanRepository != null) {
                scanRepository.save(new EvolutionScanState(
                        event.taskId(), event.agentRole(), event.collection(),
                        event.producedContent(), count, topDocId, topScore,
                        System.currentTimeMillis()));
            }
        } catch (Exception e) {
            log.warn("[EVOLUTION_SCAN] 检索探测失败(非阻断): task={}, error={}",
                    event.taskId(), e.getMessage());
        } finally {
            AgentRbacContext.clearContext();
        }
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
}
