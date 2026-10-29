package com.nexusmind.supervisor.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;
import com.nexusmind.api.rag.service.IHybridSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 2 验收：新旧知识关联点抽取（路径 A：Rank + bothHit 启发式）与回灌持久化。
 *
 * <p>不依赖 Nacos / Redis / Dubbo 运行时——{@code extractAssociations} 为纯函数直接验证，
 * 回灌以内存仓储断言。覆盖 DoD 1~3：
 * <ul>
 *   <li>DoD1：两条语义相关（bothHit 且 rank≤窗口）的命中 → 稳定产出 ≥1 关联点；</li>
 *   <li>DoD2：单路命中 / rank 越界 / 自关联 → 不产出关联点（误召回率 0）；</li>
 *   <li>DoD3：EvolutionScanState 含关联点经 Jackson 序列化往返可还原。</li>
 * </ul>
 */
class KnowledgeAssociationExtractorTest {

    private final KnowledgeEvolutionService service = new KnowledgeEvolutionService();

    // ── DoD1：bothHit + 双路 rank≤窗口 → 命中 ──

    @Test
    void extractAssociations_bothHitWithinWindow_producesOne() {
        List<FusedCandidate> cands = List.of(
                new FusedCandidate("doc-old", "journal", "x", 0.033f, 1, 2, null));
        List<KnowledgeAssociation> links =
                service.extractAssociations(cands, "doc-new", "journal", 100L);

        assertEquals(1, links.size(), "bothHit 且双路 rank≤3 应产出 1 个关联点");
        assertEquals("doc-old", links.get(0).relatedDocId());
        assertEquals("BOTH_HIT_TOP3", links.get(0).relationType());
        assertEquals(100L, links.get(0).discoveredAt());
    }

    // ── DoD2：单路命中（sparseOnly）→ 排除 ──

    @Test
    void extractAssociations_sparseOnly_excluded() {
        List<FusedCandidate> cands = List.of(
                new FusedCandidate("doc-a", "journal", "x", 0.016f, 0, 1, null)); // denseRank=0
        assertTrue(service.extractAssociations(cands, "doc-new", "journal", 1L).isEmpty(),
                "仅稀疏命中不应视为关联点");
    }

    // ── DoD2：rank 超出窗口 → 排除 ──

    @Test
    void extractAssociations_rankBeyondWindow_excluded() {
        List<FusedCandidate> cands = List.of(
                new FusedCandidate("doc-b", "journal", "x", 0.001f, 5, 6, null)); // 双路命中但 rank 超出
        assertTrue(service.extractAssociations(cands, "doc-new", "journal", 1L).isEmpty(),
                "双路命中但 rank>3 不应视为关联点");
    }

    // ── DoD2：自关联（relatedDocId == newDocId）→ 排除 ──

    @Test
    void extractAssociations_selfAssociation_excluded() {
        List<FusedCandidate> cands = List.of(
                new FusedCandidate("doc-self", "journal", "x", 0.033f, 1, 1, null));
        assertTrue(service.extractAssociations(cands, "doc-self", "journal", 1L).isEmpty(),
                "与自身 docId 相同的候选应排除（防自关联）");
    }

    // ── 窗口可配置性（RANK_WINDOW 经 Nacos 下发）──

    @Test
    void extractAssociations_rankWindowConfigurable() {
        service.setRankWindow(2);
        // denseRank=1, sparseRank=2 在窗口 2 内 → 命中
        List<FusedCandidate> hit = List.of(new FusedCandidate("doc-h", "journal", "x", 0.03f, 1, 2, null));
        assertEquals(1, service.extractAssociations(hit, "d", "journal", 1L).size());

        // denseRank=1, sparseRank=3 超出窗口 2 → 排除
        List<FusedCandidate> miss = List.of(new FusedCandidate("doc-m", "journal", "x", 0.02f, 1, 3, null));
        assertTrue(service.extractAssociations(miss, "d", "journal", 1L).isEmpty(),
                "sparseRank=3 超出窗口 2 应排除");
    }

    // ── 集成：onTaskCompleted 把关联点落扫描态 ──

    @Test
    void onTaskCompleted_persistsAssociationsAndScanState() {
        IHybridSearchService mock = req -> new HybridSearchResponse(req.query(), req.collection(), 10L,
                List.of(new FusedCandidate("doc-old", req.collection(), "x", 0.033f, 1, 2, null)));
        InMemoryScanRepository repo = new InMemoryScanRepository();

        service.setHybridSearchService(mock);
        service.setEvolutionExecutor(Runnable::run);
        service.setScanRepository(repo);
        service.setEnabled(true);

        // 构造一个带 newDocId 的事件（webCurator 场景，docId 来自 WebCurateResult）
        TaskCompletionEvent e = new TaskCompletionEvent(
                "task-1", "webCurator", "web_kb", "虚拟线程调度最佳实践", "doc-new");

        service.onTaskCompleted(e);

        assertNotNull(repo.last(), "扫描态应落盘");
        assertEquals(1, repo.last().associations().size(), "应发现 1 个关联点");
        assertEquals("doc-old", repo.last().associations().get(0).relatedDocId());
        // 独立索引也应以 newDocId 反查（内存仓储 saveLinks 静默成功）
        assertEquals(1, repo.loadLinksByDocId("doc-new").size(), "关联点应可经 newDocId 反查");
    }

    // ── DoD3：含关联点的扫描态经 Jackson 序列化往返可还原 ──

    @Test
    void evolutionScanState_withAssociations_serializesRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EvolutionScanState state = new EvolutionScanState(
                "task-9", "journalAgent", "journal", "query", 1, "doc-old", 0.033f,
                List.of(new KnowledgeAssociation("doc-new", "doc-old", "journal",
                        0.033f, 1, 2, "BOTH_HIT_TOP3", 123L)),
                123L);

        String json = mapper.writeValueAsString(state);
        EvolutionScanState back = mapper.readValue(json, EvolutionScanState.class);

        assertEquals(state.taskId(), back.taskId());
        assertEquals(1, back.associations().size());
        assertEquals("doc-old", back.associations().get(0).relatedDocId());
        assertEquals("BOTH_HIT_TOP3", back.associations().get(0).relationType());
    }

    /** 内存版扫描态仓储（不依赖 Redis），同时实现 save/load/saveLinks/loadLinksByDocId。 */
    static class InMemoryScanRepository extends EvolutionScanRepository {
        private EvolutionScanState last;
        private final java.util.Map<String, List<KnowledgeAssociation>> links = new java.util.HashMap<>();

        InMemoryScanRepository() {
            super(new ObjectMapper());
        }

        @Override
        public void save(EvolutionScanState state) {
            this.last = state;
        }

        @Override
        public void saveLinks(String newDocId, List<KnowledgeAssociation> l) {
            links.put(newDocId, l);
        }

        @Override
        public List<KnowledgeAssociation> loadLinksByDocId(String newDocId) {
            return links.getOrDefault(newDocId, List.of());
        }

        EvolutionScanState last() {
            return last;
        }
    }
}
