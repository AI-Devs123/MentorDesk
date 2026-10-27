package com.nexusmind.supervisor.service;

import com.nexusmind.api.dto.WebCurateProgress;
import com.nexusmind.api.dto.WebCurateRequest;
import com.nexusmind.api.dto.WebCurateResult;
import com.nexusmind.api.dto.WebDocMetadata;
import com.nexusmind.api.service.IWebCuratorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebCuratorOrchestrationServiceImpl 单测：验证「编排层只做下发与阶段帧聚合，不碰抓取与落盘」的契约。
 *
 * <p>额外覆盖两条前端强依赖的语义：
 * <ul>
 *   <li>失败帧必须携带 {@code SecurityGuard} 关键词 —— 前端 SECURITY_KEYWORDS 兜底识别依赖它；</li>
 *   <li>EXTRACTED / STAGED 帧的数字必须取自<b>真实返回值</b>，不得伪造。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WebCuratorOrchestrationServiceImplTest {

    @Mock
    private IWebCuratorService webCuratorService;

    @InjectMocks
    private WebCuratorOrchestrationServiceImpl orchestrationService;

    /** rawHtml=1000 / charCount=300 → 降噪比 70% */
    private static WebCurateResult successResult() {
        return new WebCurateResult(
                "WEB-1", "doc_abc", "inbox/web/20261027221310_demo.md",
                new WebDocMetadata("标题", "一句话摘要", List.of("标签A"), List.of("实体X")),
                6, 300, "https://example.com/a", false,
                1000, 4096L);
    }

    @Test
    @DisplayName("正常路径应产出 SECURITY_CHECK → FETCHING → EXTRACTED → STAGED → COMPLETED 五帧")
    void shouldEmitFiveStageFramesOnSuccess() {
        WebCurateRequest request = new WebCurateRequest("WEB-1", "https://example.com/a", false);
        when(webCuratorService.curateAndIngest(request)).thenReturn(successResult());

        List<WebCurateProgress> frames = orchestrationService.orchestrateWebCurate(request);

        assertNotNull(frames);
        assertEquals(5, frames.size(), "正常路径应有 5 个阶段帧");
        assertEquals(List.of("SECURITY_CHECK", "FETCHING", "EXTRACTED", "STAGED", "COMPLETED"),
                frames.stream().map(WebCurateProgress::stage).toList());
        // 所有帧回显同一 taskId，供前端按任务归组
        assertTrue(frames.stream().allMatch(f -> "WEB-1".equals(f.taskId())));
        // 编排层必须真的把请求透传给 WebCuratorAgent，不得本地模拟
        verify(webCuratorService, times(1)).curateAndIngest(request);
    }

    @Test
    @DisplayName("EXTRACTED 帧的降噪比与 Chunk 数必须来自真实返回值")
    void shouldReportRealMetricsInExtractedFrame() {
        WebCurateRequest request = new WebCurateRequest("WEB-2", "https://example.com/b", true);
        when(webCuratorService.curateAndIngest(request)).thenReturn(successResult());

        List<WebCurateProgress> frames = orchestrationService.orchestrateWebCurate(request);

        WebCurateProgress extracted = frames.stream()
                .filter(f -> "EXTRACTED".equals(f.stage())).findFirst().orElseThrow();
        // (1000 - 300) / 1000 = 70%
        assertTrue(extracted.message().contains("70%"),
                "降噪比应按真实值计算，实际: " + extracted.message());
        assertTrue(extracted.message().contains("6"),
                "Chunk 数应取自返回值，实际: " + extracted.message());

        WebCurateProgress staged = frames.stream()
                .filter(f -> "STAGED".equals(f.stage())).findFirst().orElseThrow();
        assertTrue(staged.message().contains("inbox/web/20261027221310_demo.md"));
        assertTrue(staged.message().contains("4,096"), "字节数应带千分位展示真实值");
        assertTrue(staged.payloadJson().contains("doc_abc"),
                "STAGED 负载应可独立解析出 docId: " + staged.payloadJson());

        WebCurateProgress completed = frames.get(frames.size() - 1);
        assertTrue(completed.payloadJson().contains("\"docId\":\"doc_abc\""),
                "COMPLETED 负载应为完整结果 JSON: " + completed.payloadJson());
    }

    @Test
    @DisplayName("SSRF 拦截异常应转译为 FAILED 帧并保留 SecurityGuard 关键词")
    void shouldEmitFailedFrameWithSecurityKeyword() {
        WebCurateRequest request = new WebCurateRequest("WEB-3", "http://127.0.0.1:8080/admin", false);
        when(webCuratorService.curateAndIngest(any(WebCurateRequest.class)))
                .thenThrow(new SecurityException("目标地址属于内网回环段，拒绝抓取"));

        List<WebCurateProgress> frames = orchestrationService.orchestrateWebCurate(request);

        assertEquals(3, frames.size(), "失败时应为 SECURITY_CHECK / FETCHING / FAILED 三帧");
        WebCurateProgress failed = frames.get(frames.size() - 1);
        assertEquals("FAILED", failed.stage());
        assertEquals("SecurityGuard", failed.agentName());
        assertTrue(failed.message().contains("SecurityGuard"),
                "失败帧必须携带 SecurityGuard 关键词，供前端熔断兜底识别: " + failed.message());
        assertTrue(failed.message().contains("内网回环段"), "应保留原始拦截原因");
    }

    @Test
    @DisplayName("普通异常应转译为 FAILED 帧且不吞掉原因")
    void shouldEmitFailedFrameOnGenericException() {
        WebCurateRequest request = new WebCurateRequest("WEB-4", "https://example.com/c", false);
        when(webCuratorService.curateAndIngest(any(WebCurateRequest.class)))
                .thenThrow(new RuntimeException("WebCurator 入库失败 [WEB-4]: 正文过短"));

        List<WebCurateProgress> frames = orchestrationService.orchestrateWebCurate(request);

        WebCurateProgress failed = frames.get(frames.size() - 1);
        assertEquals("FAILED", failed.stage());
        assertTrue(failed.message().contains("正文过短"),
                "失败原因应透传，实际: " + failed.message());
    }

    @Test
    @DisplayName("Agent 返回空结果时也应收敛为 FAILED 帧，而非 NPE 或空帧序列")
    void shouldFailGracefullyWhenResultIsNull() {
        WebCurateRequest request = new WebCurateRequest("WEB-5", "https://example.com/d", false);
        when(webCuratorService.curateAndIngest(any(WebCurateRequest.class))).thenReturn(null);

        List<WebCurateProgress> frames = orchestrationService.orchestrateWebCurate(request);

        assertEquals(3, frames.size());
        assertEquals("FAILED", frames.get(frames.size() - 1).stage());
    }
}
