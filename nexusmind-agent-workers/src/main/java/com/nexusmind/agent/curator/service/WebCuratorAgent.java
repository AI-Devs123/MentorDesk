package com.nexusmind.agent.curator.service;

import com.nexusmind.agent.curator.cleaner.CleanArticlePipeline;
import com.nexusmind.agent.curator.fetcher.HybridWebFetcher;
import com.nexusmind.agent.curator.model.DocMetadata;
import com.nexusmind.api.dto.WebCurateRequest;
import com.nexusmind.api.dto.WebCurateResult;
import com.nexusmind.api.dto.WebDocMetadata;
import com.nexusmind.api.service.IWebCuratorService;
import com.nexusmind.security.rbac.AgentSecured;
import com.nexusmind.security.sandbox.SecureFileOperationService;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * WebCurator 核心服务（agent-workers 层 - 网页抓取 / 去噪 / 知识萃取 / 沙箱落盘）。
 *
 * <p>切片 4 起提升为跨进程公共契约：经
 * {@code @DubboService(interfaceClass = IWebCuratorService.class)} 导出到 Nacos，
 * supervisor 侧 WebCuratorFacade 远程引用——抓取、提纯、萃取、分块、落盘全部发生在
 * agent-workers 进程。
 *
 * <p><b>执行边界（WEB_CURATOR）：</b>
 * <ul>
 *   <li>仅可在沙箱 {@code inbox/web/} 下写暂存文件；</li>
 *   <li>抓取通道内置 SSRF 防护（入口准入 + 连接层钉 IP），禁止访问内网 / 回环地址；</li>
 *   <li>挂 {@code @AgentSecured(allowedAgents = {"supervisor", "webCurator"})}，
 *       经 Dubbo 导出后由 AgentRbacDubboFilter 在 RPC 层强制执行。</li>
 * </ul>
 *
 * <p>落盘策略：以相对沙箱路径 {@code inbox/web/<时间戳>_<slug>.md} 写入，内含 YAML frontmatter
 * （docId / 元数据 / 分块规模 / 原始链接），正文为切片 2 的纯净 Markdown。相对路径由
 * PathSandboxValidator 强制约束在沙箱根内，调用方无法越界写。
 */
@Service
@DubboService(interfaceClass = IWebCuratorService.class, timeout = 120000)
public class WebCuratorAgent implements IWebCuratorService {

    private static final Logger log = LoggerFactory.getLogger(WebCuratorAgent.class);

    /** 落盘相对目录（沙箱根下），与 PathSandboxValidator 的沙箱根拼接成绝对路径。 */
    private static final String INBOX_DIR = "inbox/web";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final HybridWebFetcher fetcher;
    private final CleanArticlePipeline cleanPipeline;
    private final CuratedIngestPipeline ingestPipeline;
    private final SecureFileOperationService secureFileOps;

    public WebCuratorAgent(HybridWebFetcher fetcher,
                           CleanArticlePipeline cleanPipeline,
                           CuratedIngestPipeline ingestPipeline,
                           SecureFileOperationService secureFileOps) {
        this.fetcher = fetcher;
        this.cleanPipeline = cleanPipeline;
        this.ingestPipeline = ingestPipeline;
        this.secureFileOps = secureFileOps;
    }

    @AgentSecured(allowedAgents = {"supervisor", "webCurator"}, audit = true)
    @Override
    public WebCurateResult curateAndIngest(WebCurateRequest request) {
        String url = request.url();
        log.info("[WebCurator] 收到入库请求: requestId={}, url={}, forceRender={}",
                request.requestId(), url, request.forceRenderJs());

        try {
            // 1) 双通道抓取（含 SSRF 防护；SecurityException 不降级，直接上抛）
            String rawHtml = fetcher.fetch(url, request.forceRenderJs());

            // 2) 正文提纯（过短抛 ContentExtractionException，不静默产空文档）
            CleanArticlePipeline.CleanMarkdownDocument clean =
                    cleanPipeline.execute(rawHtml, url);

            // 3) LLM 结构化萃取 + 自适应语义分块 → 入库载荷（docId 幂等）
            CuratedIngestPipeline.IngestPayload payload =
                    ingestPipeline.process(clean);

            // 4) 组装带 frontmatter 的 Markdown 并落盘沙箱
            String savedRelativePath = persist(payload, request.forceRenderJs());

            // 5) 映射为契约返回值（agent 内部 DocMetadata → api 层 WebDocMetadata）
            DocMetadata md = payload.metadata();
            return new WebCurateResult(
                    request.requestId(),
                    payload.docId(),
                    savedRelativePath,
                    new WebDocMetadata(md.title(), md.summary(), md.tags(), md.entities()),
                    payload.chunks().size(),
                    payload.fullMarkdown().length(),
                    clean.meta().canonical(),
                    request.forceRenderJs()
            );
        } catch (SecurityException se) {
            // SSRF 拦截：明确向上传播，让调用方知悉抓取被安全层拒绝（而非当作普通失败）
            log.error("[WebCurator] 抓取被安全层拦截: url={}, reason={}", url, se.getMessage());
            throw se;
        } catch (Exception e) {
            log.error("[WebCurator] 入库失败: requestId={}, url={}", request.requestId(), url, e);
            throw new RuntimeException(
                    "WebCurator 入库失败 [" + request.requestId() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 把入库载荷落盘为带 YAML frontmatter 的 Markdown。
     *
     * @return 相对沙箱根的路径，如 inbox/web/20261027221310_xxx.md
     */
    private String persist(CuratedIngestPipeline.IngestPayload payload, boolean renderJsUsed) {
        String docId = payload.docId();
        DocMetadata md = payload.metadata();
        String ts = LocalDateTime.now().format(TS_FMT);
        String slug = buildSlug(md.title(), docId);
        String relativePath = INBOX_DIR + "/" + ts + "_" + slug + ".md";

        String content = buildMarkdown(payload, md, renderJsUsed);
        try {
            secureFileOps.writeFile(relativePath, content);
            log.info("[WebCurator] 已落盘: {} (docId={}, chunks={})",
                    relativePath, docId, payload.chunks().size());
        } catch (IOException ioe) {
            throw new RuntimeException("沙箱落盘失败: " + relativePath, ioe);
        }
        return relativePath;
    }

    /**
     * 组装 frontmatter + 正文。frontmatter 携带 docId / 元数据 / 分块规模 / 原始链接，
     * 供 Week 7 向量化消费时直接读取，无需再解析正文。
     */
    private String buildMarkdown(CuratedIngestPipeline.IngestPayload payload,
                                 DocMetadata md, boolean renderJsUsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("docId: ").append(escapeYaml(payload.docId())).append("\n");
        sb.append("title: ").append(escapeYaml(md.title())).append("\n");
        sb.append("summary: ").append(escapeYaml(md.summary())).append("\n");
        sb.append("tags: ").append(toYamlList(md.tags())).append("\n");
        sb.append("entities: ").append(toYamlList(md.entities())).append("\n");
        sb.append("originUrl: ").append(escapeYaml(payload.originUrl())).append("\n");
        sb.append("chunkCount: ").append(payload.chunks().size()).append("\n");
        sb.append("charCount: ").append(payload.fullMarkdown().length()).append("\n");
        sb.append("renderJsUsed: ").append(renderJsUsed).append("\n");
        sb.append("curatedAt: ")
                .append(escapeYaml(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                .append("\n");
        sb.append("---\n\n");
        sb.append(payload.fullMarkdown());
        return sb.toString();
    }

    /**
     * 由标题生成文件名安全的 slug：保留字母数字与中文，其余折叠为下划线，截断至 40 字符。
     * 空标题退化为 docId，保证落盘文件名永不为空 / 含路径遍历字符。
     */
    private String buildSlug(String title, String docId) {
        String base = (title == null || title.isBlank()) ? docId : title;
        String sanitized = base.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .strip();
        if (sanitized.length() > 40) {
            sanitized = sanitized.substring(0, 40);
        }
        return sanitized.isBlank() ? docId : sanitized;
    }

    /**
     * YAML 标量转义：含特殊字符（冒号 / # / 方括号 / 引号 / 换行等）时用双引号包裹并转义。
     */
    private String escapeYaml(String v) {
        if (v == null) {
            return "";
        }
        if (v.matches(".*[:#\\[\\]{}&*?|<>=!%@`\"'\\n\\r].*")) {
            return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return v;
    }

    /**
     * YAML 行内列表：[a, b, c]，元素经 escapeYaml 处理。
     */
    private String toYamlList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(escapeYaml(items.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }
}
