package com.nexusmind.supervisor.resilience;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 3：Supervisor servlet 接入层限流验证（纯单元，离线）。
 *
 * <p>用 {@link MockHttpServletRequest}/{@link MockHttpServletResponse}/{@link MockFilterChain}
 * 驱动自研 {@link SentinelServletFilter}：对同一限流资源（QPS=1）连发两次，
 * 第一次放行（200，进入 chain），第二次命中流控（429 + JSON）。覆盖主开关与排除路径。
 */
class SentinelServletFilterTest {

    private SentinelServletFilter filter;

    @BeforeEach
    void setUp() {
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(true);
        SentinelProperties.FlowRuleDef def = new SentinelProperties.FlowRuleDef();
        def.setResource("GET:/sentinel-servlet");
        def.setCount(1);
        def.setGrade(1);
        props.getFlowRules().add(def);
        FlowRuleManager.loadRules(List.of(toFlowRule(def)));
        filter = new SentinelServletFilter(props, new ObjectMapper());
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

    private MockHttpServletRequest get(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    @Test
    void firstRequestPassesSecondBlocked() throws Exception {
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        MockFilterChain chain1 = new MockFilterChain();
        filter.doFilter(get("/sentinel-servlet"), res1, chain1);
        assertEquals(HttpStatus.OK.value(), res1.getStatus(), "首次请求应放行");
        assertTrue(chain1.getRequest() != null, "放行的请求应进入 FilterChain");

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(get("/sentinel-servlet"), res2, chain2);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), res2.getStatus(), "限流应返回 429");
        assertTrue(chain2.getRequest() == null, "被限流的请求不应进入 FilterChain");
        assertTrue(res2.getContentAsString().contains("\"TOO_MANY_REQUESTS\""), "响应体应含限流码");
        assertTrue(res2.getContentAsString().contains("GET:/sentinel-servlet"), "响应体应含 resource");
    }

    @Test
    void disabledSkipsSentinel() throws Exception {
        // 复用同一 props 引用：关闭后过滤器直通
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(false);
        SentinelServletFilter disabledFilter = new SentinelServletFilter(props, new ObjectMapper());
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        MockFilterChain chain1 = new MockFilterChain();
        disabledFilter.doFilter(get("/sentinel-servlet"), res1, chain1);
        assertEquals(HttpStatus.OK.value(), res1.getStatus());
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        MockFilterChain chain2 = new MockFilterChain();
        disabledFilter.doFilter(get("/sentinel-servlet"), res2, chain2);
        assertEquals(HttpStatus.OK.value(), res2.getStatus(), "关闭后两次都应放行");
    }

    @Test
    void excludedPathSkipsSentinel() throws Exception {
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(true);
        props.getExcludedPaths().add("/sentinel-servlet");
        SentinelServletFilter excludedFilter = new SentinelServletFilter(props, new ObjectMapper());
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        excludedFilter.doFilter(get("/sentinel-servlet"), res1, new MockFilterChain());
        assertEquals(HttpStatus.OK.value(), res1.getStatus());
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        excludedFilter.doFilter(get("/sentinel-servlet"), res2, new MockFilterChain());
        assertEquals(HttpStatus.OK.value(), res2.getStatus(), "排除路径应直通");
    }
}
