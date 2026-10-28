package com.nexusmind.rag;

import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.rag.rerank.BgeRerankerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BgeRerankerService 降级与契约验证（切片 4）。
 *
 * <p>测试 profile 关闭 {@code nexusmind.rerank.enabled=false}（且无 300MB 模型文件），
 * 因此 {@code isAvailable()==false}，rerank 必须<b>平滑回落纯 RRF 原始顺序</b>且不抛异常。
 * 这是「系统永不阻断」契约的核心回归点。真实模型下的排序质量需落地 vocab/model 后另测。
 */
@SpringBootTest(classes = RagApplication.class)
@ActiveProfiles("test")
class BgeRerankerServiceTest {

    @Autowired
    private BgeRerankerService rerankerService;

    @Test
    @DisplayName("模型缺省/未开启时安全降级：保持原序、条数不变、零异常")
    void testRerankDegradesGracefully() {
        List<FusedCandidate> candidates = new ArrayList<>();
        candidates.add(new FusedCandidate("DOC-1", "code", "public void startServer()",
                0.03f, 1, 2, Map.of()));
        candidates.add(new FusedCandidate("DOC-2", "code", "public void shutdownEngineNow()",
                0.02f, 2, 3, Map.of()));

        long start = System.currentTimeMillis();
        List<FusedCandidate> result = rerankerService.rerank("shutdown", candidates, 2);
        long elapsed = System.currentTimeMillis() - start;

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        // 不可用时回落原 RRF 顺序：DOC-1 仍居首
        Assertions.assertEquals("DOC-1", result.get(0).docId());
        // 降级路径是纯内存切片，时延应远小于模型推理量级
        Assertions.assertTrue(elapsed < 50, "降级路径耗时应在 50ms 内，实际=" + elapsed);
    }

    @Test
    @DisplayName("空/null 候选不抛异常")
    void testRerankNullSafe() {
        Assertions.assertNotNull(rerankerService.rerank("q", null, 5));
        Assertions.assertTrue(rerankerService.rerank("q", List.of(), 5).isEmpty());
    }
}
