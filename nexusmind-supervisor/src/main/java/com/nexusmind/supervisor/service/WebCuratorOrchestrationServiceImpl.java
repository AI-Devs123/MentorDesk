package com.nexusmind.supervisor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.api.dto.WebCurateProgress;
import com.nexusmind.api.dto.WebCurateRequest;
import com.nexusmind.api.dto.WebCurateResult;
import com.nexusmind.api.service.IWebCuratorOrchestrationService;
import com.nexusmind.api.service.IWebCuratorService;
import com.nexusmind.security.rbac.AgentSecured;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页策展编排实现（Supervisor 层 - 薄编排层）。
 *
 * <p>与 {@link CodeOrchestrationServiceImpl} 完全同构：本身被网关经
 * {@code @DubboReference IWebCuratorOrchestrationService} 远程引用，又以
 * {@code @DubboReference} 引用 agent-workers 的 {@link IWebCuratorService}，
 * 形成「网关 → Supervisor(编排) → agent-workers(执行)」的三段式进程边界。
 *
 * <p><b>关于「流式」的诚实说明：</b>{@code IWebCuratorService#curateAndIngest} 是单次 unary 调用，
 * 编排层无法观测 RPC 内部的真实中间态。因此本实现下发的是<b>阶段回执帧</b>：
 * 前置帧声明即将执行的阶段，后置帧的每一个数字（chunkCount / charCount / 降噪比 /
 * 落盘字节数）都取自<b>真实返回结果</b>，不做任何伪造。
 * 待 Week 7 将 curator 升级为 Dubbo 服务端流后，本类只需把 {@code for} 循环替换为
 * 逐帧转发即可，网关与前端契约无需改动。
 *
 * <p><b>失败语义：</b>SSRF 拦截与越权在 agent / Dubbo Filter 层以异常形式上抛，
 * 这里统一转译为 {@code FAILED} 帧并保留 {@code SecurityGuard} / {@code SSRF} 关键词，
 * 供前端安全熔断兜底识别（见 {@code useCurateStream} 的 SECURITY_KEYWORDS）。
 */
@DubboService(interfaceClass = IWebCuratorOrchestrationService.class, timeout = 120000)
@Service
public class WebCuratorOrchestrationServiceImpl implements IWebCuratorOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(WebCuratorOrchestrationServiceImpl.class);

    /** Dubbo 消费者引用 agent-workers 的 WebCuratorAgent（公共契约接口）。 */
    @DubboReference(timeout = 120000)
    private IWebCuratorService webCuratorService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AgentSecured(allowedAgents = {"supervisor", "webCurator"})
    @Override
    public List<WebCurateProgress> orchestrateWebCurate(WebCurateRequest request) {
        String taskId = request.requestId();
        String url = request.url();
        log.info("[WebCurateOrchestration] 收到编排请求: taskId={}, url={}, forceRender={}",
                taskId, url, request.forceRenderJs());

        List<WebCurateProgress> frames = new ArrayList<>();

        // 阶段 1：安全准入声明（真正拦截发生在 agent 的 SSRF 校验，fail-closed）
        frames.add(WebCurateProgress.progress(taskId, "SECURITY_CHECK", "SecurityGuard",
                "SSRF 边界准入已声明：仅允许公网 http/https，"
                        + "拒绝内网 / 回环 / link-local / IPv6 ULA；连接层将钉死核准 IP 防 DNS 重绑"));

        // 阶段 2：抓取与提纯
        frames.add(WebCurateProgress.progress(taskId, "FETCHING", "WebCuratorAgent",
                "启动双引擎抓取通道：FastHttp 优先，SPA 空壳自动降级无头浏览器"
                        + "（ForceRender: " + request.forceRenderJs() + "）"));

        WebCurateResult result;
        try {
            result = webCuratorService.curateAndIngest(request);
        } catch (SecurityException se) {
            // SSRF / 沙箱越权：向上传播为 FAILED 帧，保留关键词供前端熔断识别
            log.error("[WebCurateOrchestration] 抓取被安全层拦截: url={}, reason={}", url, se.getMessage());
            frames.add(WebCurateProgress.progress(taskId, "FAILED", "SecurityGuard",
                    "SecurityGuard 拦截：目标 URL 未通过 SSRF 边界校验（" + se.getMessage() + "）"));
            return frames;
        } catch (Exception e) {
            log.error("[WebCurateOrchestration] 编排调用失败: taskId={}, url={}", taskId, url, e);
            frames.add(WebCurateProgress.progress(taskId, "FAILED", "WebCuratorAgent",
                    "网页萃取失败：" + e.getMessage()));
            return frames;
        }

        if (result == null) {
            frames.add(WebCurateProgress.progress(taskId, "FAILED", "WebCuratorAgent",
                    "网页萃取失败：WebCuratorAgent 返回空结果"));
            return frames;
        }

        // 阶段 3：萃取与分块（数字全部来自真实返回值）
        frames.add(WebCurateProgress.progress(taskId, "EXTRACTED", "WebCuratorAgent",
                String.format("LLM 萃取摘要与元数据成功，切分为 %d 个 Chunk"
                                + "（纯净正文 %,d 字，降噪比 %d%%）",
                        result.chunkCount(), result.charCount(), result.noiseReductionPercent())));

        // 阶段 4：沙箱落盘（路径与字节数均为真实值）
        String stagedJson = toJson(new StagedPayload(result.docId(), result.sandboxSavedPath(),
                result.fileSizeBytes(), result.chunkCount()));
        frames.add(WebCurateProgress.withPayload(taskId, "STAGED", "WebCuratorAgent",
                String.format("Markdown 资产已安全落盘至沙箱暂存区: %s（%,d 字节，渲染通道: %s）",
                        result.sandboxSavedPath(), result.fileSizeBytes(),
                        result.renderJsUsed() ? "Playwright" : "FastHttp"),
                stagedJson));

        // 阶段 5：完成（携带完整结果，供前端 WebCurateResultCard 一次渲染）
        frames.add(WebCurateProgress.withPayload(taskId, "COMPLETED", "Supervisor",
                "知识萃取与资产暂存完成", toJson(result)));

        log.info("[WebCurateOrchestration] 编排完成: taskId={}, docId={}, 共 {} 帧",
                taskId, result.docId(), frames.size());
        return frames;
    }

    /** STAGED 帧的轻量负载：前端可先行展示落盘状态，无需等 COMPLETED 完整体。 */
    private record StagedPayload(String docId, String sandboxSavedPath,
                                 long fileSizeBytes, int chunkCount) {
    }

    /** 序列化失败不阻断编排：降级为 "{}"，由前端按缺字段容错处理。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("[WebCurateOrchestration] 负载序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}
