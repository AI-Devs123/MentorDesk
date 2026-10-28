package com.nexusmind.gateway.controller;

import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;
import com.nexusmind.api.rag.service.IHybridSearchService;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网关 SSE 桥接验收（切片 3）。
 *
 * <p>采用 {@code WebTestClient.bindToController(...)} 直接绑定控制器实例，<b>不加载任何
 * Spring ApplicationContext</b>——因此无需 Nacos / Redis / Dubbo 注册中心即可验证 SSE 帧构造、
 * 脱敏透传与 RBAC 异常翻译逻辑。mock 经控制器既有 setter 注入，绕开 {@code @DubboReference}。
 *
 * <p>真实的「网关 ↔ rag 经 Triple 端到端」需 Nacos + 两服务联跑，属集成冒烟测试，另行进行。
 *
 * <p>注：此前曾用 {@code @WebFluxTest(GatewaySearchController.class)}，但它仍会以 {@code GatewayApplication}
 * 作为配置源（含 {@code @EnableDubbo} 与对 {@code com.nexusmind.gateway / com.nexusmind.security} 的
 * {@code @ComponentScan}），从而把 Redisson / Dubbo / Nacos 等自动配置一并拉起，在没有注册中心与
 * Redis 的测试环境里必然 {@code Failed to load ApplicationContext}。{@code bindToController} 彻底规避之。
 */
class GatewaySearchControllerTest {

    private GatewaySearchController controller;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        controller = new GatewaySearchController();
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("验收1：合法检索经 SSE 透出 RESULT 与 COMPLETED 帧，且候选随响应返回")
    void streamEmitsResultAndCompletedEvents() {
        IHybridSearchService mockSvc = mock(IHybridSearchService.class);
        FusedCandidate candidate = new FusedCandidate(
                "JOURNAL-101", "journal", "今天复盘了系统微服务状态机与长连接流控。",
                1.0f, 1, 0, Map.of("date", "2026-10-28"));
        when(mockSvc.hybridSearch(any())).thenReturn(
                new HybridSearchResponse("状态机", "journal", 8L, List.of(candidate)));
        // bindToController 不创建 ReferenceBean，注入 mock 以绕过 Nacos
        controller.setHybridSearchService(mockSvc);

        webTestClient.get()
                .uri(b -> b.path("/api/v1/search/stream")
                        .queryParam("query", "状态机")
                        .queryParam("collection", "journal")
                        .queryParam("topN", "5")
                        .build())
                .header("X-Agent-Role", "journalAgent")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertTrue(body.contains("event:RESULT"), "必须包含 RESULT 帧");
                    assertTrue(body.contains("JOURNAL-101"), "响应载荷应含命中候选");
                    assertTrue(body.contains("event:COMPLETED"), "流程结束应发 COMPLETED 帧");
                });
    }

    @Test
    @DisplayName("验收2：后端 SecurityException/FORBIDDEN 被翻译为 SECURITY_VIOLATION SSE 事件")
    void securityViolationTranslatedToSseEvent() {
        IHybridSearchService mockSvc = mock(IHybridSearchService.class);
        when(mockSvc.hybridSearch(any())).thenThrow(
                new RpcException(RpcException.FORBIDDEN_EXCEPTION, "Agent 角色 [codeAgent] 无权访问集合 [journal]"));
        controller.setHybridSearchService(mockSvc);

        webTestClient.get()
                .uri(b -> b.path("/api/v1/search/stream")
                        .queryParam("query", "状态机")
                        .queryParam("collection", "journal")
                        .build())
                .header("X-Agent-Role", "codeAgent")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertTrue(body.contains("SECURITY_VIOLATION"),
                        "越权必须翻译为 SECURITY_VIOLATION 事件，实际: " + body));
    }

    @Test
    @DisplayName("验收3：阻塞调用被 offload（request 不为 null 即证明 worker 线程成功设置角色并发起调用）")
    void offloadInvokesServiceWithRequest() {
        IHybridSearchService mockSvc = mock(IHybridSearchService.class);
        when(mockSvc.hybridSearch(any())).thenAnswer(inv -> {
            HybridSearchRequest req = inv.getArgument(0);
            FusedCandidate c = new FusedCandidate("X-1", req.collection(), "内容", 1.0f, 1, 0, Map.of());
            return new HybridSearchResponse(req.query(), req.collection(), 5L, List.of(c));
        });
        controller.setHybridSearchService(mockSvc);

        webTestClient.get()
                .uri(b -> b.path("/api/v1/search/stream")
                        .queryParam("query", "abc")
                        .queryParam("collection", "code")
                        .build())
                .header("X-Agent-Role", "codeAgent")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertTrue(body.contains("event:RESULT"),
                        "offload 后的阻塞调用应成功返回 RESULT，实际: " + body));
    }
}
