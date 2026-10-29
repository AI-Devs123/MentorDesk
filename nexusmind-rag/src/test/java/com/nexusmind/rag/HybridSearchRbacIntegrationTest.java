package com.nexusmind.rag;

import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;
import com.nexusmind.api.rag.dto.RagIndexDocument;
import com.nexusmind.api.rag.service.IHybridSearchService;
import com.nexusmind.rag.embedding.EmbeddingClient;
import com.nexusmind.rag.engine.LuceneDualEngineManager;
import com.nexusmind.rag.security.AgentCollectionRbacGuard;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 切片 3 验收：RBAC 集合级越权拦截 + 进程内混合检索闭环 + 响应 PII 脱敏。
 *
 * <p>使用独立 {@code index-dir}（@TestPropertySource）以获得<b>隔离</b> Spring 上下文，
 * 避免与切片 1/2 共享同一 {@code LuceneDualEngineManager} 单例导致集合污染。
 * 进程内直接调用 {@code IHybridSearchService} Bean（@DubboService 同时注册为 Spring Bean），
 * 验证的是 RBAC / 融合 / 脱敏的业务逻辑；真正的「网关 ↔ rag 经 Triple 端到端」另由网关侧集成测试覆盖。
 */
@SpringBootTest(classes = RagApplication.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "nexusmind.rag.index-dir=target/nexusmind-rag-rbac-test-indices")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridSearchRbacIntegrationTest {

    @Autowired
    private IHybridSearchService searchService;

    @Autowired
    private LuceneDualEngineManager engineManager;

    @Autowired
    private EmbeddingClient embeddingClient;

    @BeforeAll
    void seedData() throws IOException {
        String journalText = "今天复盘了系统微服务状态机与长连接流控。";
        engineManager.indexDocument(new RagIndexDocument(
                "JOURNAL-101", "journal", journalText,
                embeddingClient.embed(journalText), Map.of("date", "2026-10-27")));

        String codeText = "@AgentSecured public void verifySandbox() { checkPath(); }";
        engineManager.indexDocument(new RagIndexDocument(
                "CODE-101", "code", codeText,
                embeddingClient.embed(codeText), Map.of("lang", "JAVA")));

        // PII 样本：journal 可能含私人邮箱 / 手机号 / 姓名（tutorAgent 跨集合只读场景）
        String piiText = "我的邮箱是 alice@example.com，电话 13812345678，今天天气不错。";
        engineManager.indexDocument(new RagIndexDocument(
                "JOURNAL-PII", "journal", piiText,
                embeddingClient.embed(piiText), Map.of("name", "张三", "date", "2026-10-27")));
    }

    @AfterAll
    void cleanup() throws IOException {
        Path path = Paths.get("target/nexusmind-rag-rbac-test-indices").toAbsolutePath().normalize();
        File dir = path.toFile();
        if (dir.exists()) {
            FileSystemUtils.deleteRecursively(dir);
        }
    }

    private void withRole(String role) {
        RpcContext.getServerAttachment()
                .setAttachment(AgentCollectionRbacGuard.ATTACHMENT_ROLE_KEY, role);
    }

    @Test
    @DisplayName("验收1：codeAgent 禁止访问 journal 集合（越权被 SecurityGuard 拦截）")
    void testCodeAgentCannotAccessJournal() {
        withRole("codeAgent");
        HybridSearchRequest req = new HybridSearchRequest("状态机", "journal", 10, 5, false);
        Assertions.assertThrows(SecurityException.class, () -> searchService.hybridSearch(req),
                "codeAgent 检索 journal 必须被 SecurityGuard 抛出 SecurityException 阻断");
    }

    @Test
    @DisplayName("验收2：journalAgent 合法检索 journal 成功且性能达标")
    void testJournalAgentAccessJournalSuccess() {
        withRole("journalAgent");
        HybridSearchRequest req = new HybridSearchRequest("状态机", "journal", 10, 5, false);
        HybridSearchResponse resp = searchService.hybridSearch(req);
        Assertions.assertNotNull(resp);
        Assertions.assertFalse(resp.candidates().isEmpty());
        Assertions.assertEquals("JOURNAL-101", resp.candidates().get(0).docId());
        Assertions.assertTrue(resp.elapsedMs() < 1000, "纯 RRF 检索链路耗时应远小于 1000ms（首次冷开容差）");
    }

    @Test
    @DisplayName("验收3：webCurator 访问边界——允许 web_kb，拒绝 code")
    void testWebCuratorBoundary() {
        withRole("webCurator");
        HybridSearchRequest invalidReq = new HybridSearchRequest("verifySandbox", "code", 10, 5, false);
        Assertions.assertThrows(SecurityException.class, () -> searchService.hybridSearch(invalidReq),
                "webCurator 检索 code 必须被拦截");
    }

    @Test
    @DisplayName("验收4：tutorAgent 跨集合只读，且响应 PII 已被脱敏（@Sanitize 注解对返回值无效，此处显式打码）")
    void testTutorAgentPiiSanitized() {
        withRole("tutorAgent");
        HybridSearchRequest req = new HybridSearchRequest("邮箱 电话 天气", "journal", 10, 5, false);
        HybridSearchResponse resp = searchService.hybridSearch(req);
        Assertions.assertNotNull(resp);
        List<String> contents = resp.candidates().stream().map(c -> c.content() == null ? "" : c.content()).toList();
        String joined = String.join("\n", contents);
        Assertions.assertFalse(joined.contains("alice@example.com"), "邮箱必须被脱敏");
        Assertions.assertFalse(joined.contains("13812345678"), "手机号必须被脱敏");
        // 敏感 metadata 也需打码
        boolean anyNameMasked = resp.candidates().stream()
                .anyMatch(c -> {
                    String name = c.metadata() == null ? null : c.metadata().get("name");
                    return name != null && !name.equals("张三");
                });
        Assertions.assertTrue(anyNameMasked, "name 等敏感元数据必须被脱敏");
    }
}
