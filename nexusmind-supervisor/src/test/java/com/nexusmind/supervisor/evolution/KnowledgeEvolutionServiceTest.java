package com.nexusmind.supervisor.evolution;

import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;
import com.nexusmind.api.rag.service.IHybridSearchService;
import com.nexusmind.security.rbac.AgentRbacContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 1 验收：知识自进化后置钩子基础设施。
 *
 * <p>不依赖 Nacos / Redis / Dubbo 运行时——通过 {@code KnowledgeEvolutionService} 的测试注入点
 * 注入 mock 检索服务与「直接执行器」（同步确定性），验证：
 * <ul>
 *   <li>1. 四个编排入口的事件工厂映射到正确的 rag 集合（journal / code / web_kb）；</li>
 *   <li>2. {@code onTaskCompleted} 触发扫描、调用 {@code hybridSearch} 且携带 supervisor 角色；</li>
 *   <li>3. 扫描结果落 Redis 扫描态（save 被调用）；</li>
 *   <li>4. best-effort：检索抛异常时绝不向上传播，扫描状态安全降级；</li>
 *   <li>5. enabled=false 时完全跳过。</li>
 * </ul>
 */
class KnowledgeEvolutionServiceTest {

    /** 直接执行器：让 fire-and-forget 在当前线程同步执行，测试确定可断言。 */
    private static final Executor DIRECT = Runnable::run;

    private final KnowledgeEvolutionService service = new KnowledgeEvolutionService();

    @AfterEach
    void tearDown() {
        AgentRbacContext.clearContext();
    }

    // ── 事件工厂：集合映射正确性 ──

    @Test
    void forJournal_mapsToJournalCollection() {
        TaskCompletionEvent e = TaskCompletionEvent.forJournal("今日突破：搞懂了 RRF 融合");
        assertEquals("journalAgent", e.agentRole());
        assertEquals("journal", e.collection());
        assertTrue(e.producedContent().contains("RRF"));
    }

    @Test
    void forCode_mapsToCodeCollection() {
        TaskCompletionEvent e = TaskCompletionEvent.forCode("task-1",
                List.of(new CodeAnalysisProgress("task-1", "2026-10-29", "PARSE",
                        "src/Foo.java", 1, 2, "{}")));
        assertEquals("codeAgent", e.agentRole());
        assertEquals("code", e.collection());
    }

    @Test
    void forTutor_mapsToJournalCollection() {
        TaskCompletionEvent e = TaskCompletionEvent.forTutor(
                new com.nexusmind.api.dto.TutorReflectionPrompt(
                        "p1", "concurrency", "背景", "矛盾",
                        List.of(new com.nexusmind.api.dto.TutorReflectionPrompt.ReflectionQuestion(
                                0, "A", "B", "为什么虚拟线程不会耗尽池子？")),
                        List.of()));
        assertEquals("tutorAgent", e.agentRole());
        assertEquals("journal", e.collection());
        assertTrue(e.producedContent().contains("虚拟线程"));
    }

    // ── 服务行为：扫描 + RBAC + 暂存 ──

    @Test
    void onTaskCompleted_invokesHybridSearchWithSupervisorRole_andSavesScan() {
        AtomicInteger callCount = new AtomicInteger();
        IHybridSearchService mock = req -> {
            // 校验：调用前必须已设 supervisor 角色（RpcContext ThreadLocal）
            assertEquals("supervisor", AgentRbacContext.getCurrentAgent(),
                    "检索调用必须在 supervisor 角色上下文下进行，否则会被 rag 的 RBAC Filter 拒绝");
            callCount.incrementAndGet();
            // 切片 1 走纯 RRF 快路径，enableRerank 必须为 false（对 Boolean/boolean 契约均安全）
            assertFalse(req.enableRerank(),
                    "切片 1 走纯 RRF 快路径，enableRerank 应为 false（不触发慢路径精排）");
            return new HybridSearchResponse(req.query(), req.collection(), 12L,
                    List.of(new FusedCandidate("doc-9", req.collection(), "x", 0.83f, 1, 2, null)));
        };
        InMemoryScanRepository repo = new InMemoryScanRepository();

        service.setHybridSearchService(mock);
        service.setEvolutionExecutor(DIRECT);
        service.setScanRepository(repo);
        service.setEnabled(true);

        TaskCompletionEvent e = TaskCompletionEvent.forJournal("今日复盘：虚拟线程调度");
        service.onTaskCompleted(e);

        assertEquals(1, callCount.get(), "hybridSearch 应被调用一次");
        assertNotNull(repo.last(), "扫描完成应落 Redis 扫描态");
        assertEquals("journal", repo.last().collection());
        assertEquals(1, repo.last().candidateCount());
        assertEquals("doc-9", repo.last().topDocId());
    }

    // ── best-effort：异常不向上传播 ──

    @Test
    void onTaskCompleted_swallowsSearchException_andDoesNotThrow() {
        IHybridSearchService failing = req -> {
            throw new RuntimeException("rag 宕机");
        };
        service.setHybridSearchService(failing);
        service.setEvolutionExecutor(DIRECT);
        service.setScanRepository(new InMemoryScanRepository());
        service.setEnabled(true);

        // 即便检索崩溃，主链路也必须安然返回（监控钩子绝不拖垮业务）
        assertDoesNotThrow(() ->
                service.onTaskCompleted(TaskCompletionEvent.forJournal("content")));
    }

    // ── enabled=false 完全跳过 ──

    @Test
    void onTaskCompleted_skipsWhenDisabled() {
        AtomicInteger callCount = new AtomicInteger();
        IHybridSearchService mock = req -> {
            callCount.incrementAndGet();
            return new HybridSearchResponse(req.query(), req.collection(), 1L, List.of());
        };
        service.setHybridSearchService(mock);
        service.setEvolutionExecutor(DIRECT);
        service.setEnabled(false);

        service.onTaskCompleted(TaskCompletionEvent.forJournal("x"));
        assertEquals(0, callCount.get(), "禁用时不应发起任何检索");
    }

    /** 内存版扫描态仓储，仅用于测试断言（不依赖 Redis）。 */
    static class InMemoryScanRepository extends EvolutionScanRepository {
        private EvolutionScanState last;

        InMemoryScanRepository() {
            super(new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public void save(EvolutionScanState state) {
            this.last = state;
        }

        EvolutionScanState last() {
            return last;
        }
    }
}
