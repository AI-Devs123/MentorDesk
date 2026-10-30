package com.nexusmind.gateway.controller;

import com.nexusmind.security.auth.JwtTokenProvider;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import com.nexusmind.security.sandbox.SecureFileOperationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 沙箱文档读取端点验收（Slice 3）。
 *
 * <p>采用 {@code WebTestClient.bindToController(...)} 直接绑定控制器实例，<b>不加载任何 Spring
 * ApplicationContext</b>——网关 Application 带 {@code @EnableDubbo} 与宽 {@code @ComponentScan}，
 * {@code @WebFluxTest} 会把 Dubbo / Nacos / Redis 自动配置一并拉起而失败。
 *
 * <p>文件链路用<b>真实的</b> {@link PathSandboxValidator} + {@link SecureFileOperationService}
 * （沙箱根指向 JUnit 临时目录），因此「目录穿越被拒」等用例验证的是真实校验逻辑而非 mock 桩；
 * 仅 JWT 解析用 mock（避免引入密钥配置）。
 */
class SandboxDocControllerTest {

    /** 端点侧单次读取字符上限，需与 SandboxDocController.MAX_CHARS 保持一致 */
    private static final int MAX_CHARS = 200_000;

    private static final String AUTH = "Bearer fake-jwt";

    @TempDir
    Path sandboxRoot;

    private SecureFileOperationService secureFileOps;
    private JwtTokenProvider jwtTokenProvider;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        secureFileOps = new SecureFileOperationService(new PathSandboxValidator(sandboxRoot.toString()));
        jwtTokenProvider = mock(JwtTokenProvider.class);
        when(jwtTokenProvider.getRoleFromToken(any())).thenReturn("supervisor");
        webTestClient = WebTestClient
                .bindToController(new SandboxDocController(secureFileOps, jwtTokenProvider))
                .build();
    }

    @Test
    @DisplayName("验收1：合法角色读取 inbox/ 下的 Markdown，回传路径/字节数/正文")
    void readsMarkdownWithinInbox() throws IOException {
        String content = "# 标题\n\n正文 **加粗**。";
        secureFileOps.writeFile("inbox/web/20261027_demo.md", content);

        webTestClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "inbox/web/20261027_demo.md").build())
                .header("Authorization", AUTH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("inbox/web/20261027_demo.md")
                .jsonPath("$.truncated").isEqualTo(false)
                .jsonPath("$.content").isEqualTo(content);
    }

    @Test
    @DisplayName("验收2：目录穿越（../../etc/passwd.md）被沙箱校验器拒绝，返回 403")
    void rejectsPathTraversal() {
        webTestClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "inbox/../../etc/passwd.md").build())
                .header("Authorization", AUTH)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.stage").isEqualTo("SECURITY_VIOLATION");
    }

    @Test
    @DisplayName("验收3：非 inbox/ 子树（journals/xxx.md）越权拒绝，返回 403")
    void rejectsPathOutsideInbox() {
        webTestClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "journals/2026/10/29/entry-001.md").build())
                .header("Authorization", AUTH)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.stage").isEqualTo("SECURITY_VIOLATION");
    }

    @Test
    @DisplayName("验收4：非文档后缀拒绝，返回 400")
    void rejectsNonMarkdownExtension() {
        webTestClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "inbox/web/secret.env").build())
                .header("Authorization", AUTH)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.stage").isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("验收5：文档不存在返回 404，且不暴露文件系统细节")
    void returns404WhenMissing() {
        webTestClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "inbox/web/not-exist.md").build())
                .header("Authorization", AUTH)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.stage").isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("验收6：缺少 Authorization 返回 401")
    void rejectsMissingJwt() {
        webTestClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "inbox/web/demo.md").build())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.stage").isEqualTo("AUTH_ERROR");
    }

    @Test
    @DisplayName("验收7：合法角色但不在白名单（journalAgent）返回 403 越权熔断")
    void rejectsUnauthorizedRole() {
        JwtTokenProvider journalJwt = mock(JwtTokenProvider.class);
        when(journalJwt.getRoleFromToken(any())).thenReturn("journalAgent");
        WebTestClient journalClient = WebTestClient
                .bindToController(new SandboxDocController(secureFileOps, journalJwt))
                .build();

        journalClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "inbox/web/demo.md").build())
                .header("Authorization", AUTH)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.stage").isEqualTo("SECURITY_VIOLATION");
    }

    @Test
    @DisplayName("验收9：未知角色值（guest）按认证失败处理，返回 401")
    void rejectsUnknownRoleValue() {
        JwtTokenProvider unknownJwt = mock(JwtTokenProvider.class);
        when(unknownJwt.getRoleFromToken(any())).thenReturn("guest");
        WebTestClient unknownClient = WebTestClient
                .bindToController(new SandboxDocController(secureFileOps, unknownJwt))
                .build();

        unknownClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "inbox/web/demo.md").build())
                .header("Authorization", AUTH)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.stage").isEqualTo("AUTH_ERROR");
    }

    @Test
    @DisplayName("验收8：超大文档按字符截断并回传 truncated=true")
    void truncatesOversizedDocument() throws IOException {
        String oversized = "a".repeat(MAX_CHARS + 10);
        secureFileOps.writeFile("inbox/web/big.md", oversized);

        Map<String, Object> body = webTestClient.get()
                .uri(b -> b.path("/api/v1/sandbox/doc")
                        .queryParam("path", "inbox/web/big.md").build())
                .header("Authorization", AUTH)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertEquals(true, body.get("truncated"));
        assertEquals(MAX_CHARS, ((String) body.get("content")).length());
        assertTrue(((Number) body.get("sizeBytes")).longValue() > MAX_CHARS);
    }
}
