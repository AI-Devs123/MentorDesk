package com.nexusmind.gateway.resilience;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebHandler;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 切片 3：网关 WebFlux 接入层限流验证（纯单元，离线）。
 *
 * <p>用 {@link WebTestClient#bindToWebHandler} 把自研 {@link SentinelWebFilter} 绑到一条
 * 恒返回 200 的 handler 上，再对同一个限流资源（QPS=1）连续两次请求：
 * 第一次放行（200），第二次命中流控（429 + JSON）。同时覆盖「主开关关闭」与「排除路径」直通。
 *
 * <p><b>注意</b>：Sentinel 对资源名的通过计数在 JVM 内跨 {@code @BeforeEach} 持久，故每个测试方法
 * 使用互不相同的资源名（{@code /sentinel-tN}），避免用例间统计互相干扰导致误判。
 */
class SentinelWebFilterTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private WebTestClient webClient;
    private SentinelProperties props;
    private String path;

    @BeforeEach
    void setUp() {
        path = "/sentinel-t" + COUNTER.incrementAndGet();
        props = new SentinelProperties();
        props.setEnabled(true);
        SentinelProperties.FlowRuleDef def = new SentinelProperties.FlowRuleDef();
        def.setResource("GET:" + path);
        def.setCount(1);
        def.setGrade(1);
        props.getFlowRules().add(def);
        FlowRuleManager.loadRules(List.of(toFlowRule(def)));

        SentinelWebFilter filter = new SentinelWebFilter(props, new ObjectMapper());
        WebHandler handler = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };
        webClient = WebTestClient.bindToWebHandler(handler)
                .webFilter(filter)
                .build();
    }

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(List.of());
    }

    private FlowRule toFlowRule(SentinelProperties.FlowRuleDef def) {
        FlowRule rule = new FlowRule(def.getResource());
        rule.setCount(def.getCount());
        rule.setGrade(def.getGrade());
        rule.setLimitApp("default");
        return rule;
    }

    @Test
    void firstRequestPassesSecondBlocked() {
        webClient.get().uri(path).exchange().expectStatus().isOk();
        webClient.get().uri(path).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void blockedResponseIsJsonWithCode() {
        webClient.get().uri(path).exchange().expectStatus().isOk();
        webClient.get().uri(path).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS")
                .jsonPath("$.resource").isEqualTo("GET:" + path);
    }

    @Test
    void disabledSkipsSentinel() {
        props.setEnabled(false);
        webClient.get().uri(path).exchange().expectStatus().isOk();
        webClient.get().uri(path).exchange().expectStatus().isOk();
    }

    @Test
    void excludedPathSkipsSentinel() {
        props.getExcludedPaths().add(path);
        webClient.get().uri(path).exchange().expectStatus().isOk();
        webClient.get().uri(path).exchange().expectStatus().isOk();
    }
}
