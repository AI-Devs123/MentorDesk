package com.nexusmind.gateway.controller;

import com.nexusmind.security.auth.JwtTokenProvider;
import com.nexusmind.security.rbac.AgentRole;
import com.nexusmind.security.sandbox.SecureFileOperationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 沙箱文档读取网关（Slice 3）。
 *
 * <p><b>为什么需要它：</b>网页策展流水线把正文提纯为 Markdown 落盘到沙箱
 * （{@code inbox/web/<时间戳>_<slug>.md}），但 {@code StagedPayload} 只回传路径与字节数，
 * 前端拿不到正文。本端点把「已落盘的文档正文」安全地读出来，供前端 Markdown 渲染器消费。
 *
 * <p><b>能力复用：</b>读取直接委托 {@link SecureFileOperationService#readFileAsString(String)}，
 * 该方法此前零调用方（预留能力）。路径安全由 {@code PathSandboxValidator} 兜底，
 * 已内建 {@code ..} / {@code ~} / {@code %2e%2e} 黑名单与「结果必须落在沙箱根内」的二次校验。
 *
 * <p><b>本控制器额外加的三道闸（最小权限）：</b>
 * <ol>
 *   <li>角色白名单：仅 {@code supervisor} / {@code webCurator}（与策展写入侧 WEB_INGEST 一致）；</li>
 *   <li>路径白名单：必须以 {@code inbox/} 开头，避免接口退化成沙箱内任意文件读取；</li>
 *   <li>后缀白名单：仅 {@code .md} / {@code .markdown}，且单次读取截断于 {@value #MAX_CHARS} 字符。</li>
 * </ol>
 *
 * <p><b>可测性：</b>依赖走构造器注入（非字段注入），单测可直接
 * {@code new SandboxDocController(mockOps, mockJwt)}，配合
 * {@code WebTestClient.bindToController(...)} 无需加载任何 ApplicationContext
 * （网关 Application 带 {@code @EnableDubbo} + 宽 {@code @ComponentScan}，
 * {@code @WebFluxTest} 会把 Dubbo/Nacos/Redis 自动配置拉起而失败）。
 */
@RestController
public class SandboxDocController {

    private static final Logger log = LoggerFactory.getLogger(SandboxDocController.class);

    /** 与 CurateStreamController 保持一致：仅编排中枢与网页策展主体可读写策展产物 */
    private static final Set<String> ALLOWED_ROLES = Set.of("supervisor", "webCurator");

    /** 仅允许读取文档类文件，避免接口退化成任意文件读取 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".md", ".markdown");

    /** 仅允许沙箱 inbox/ 子树（策展落盘目录），不暴露沙箱内其它内容 */
    private static final Set<String> ALLOWED_PREFIXES = Set.of("inbox/");

    /**
     * 单次读取字符上限。超出则截断并回传 {@code truncated=true}，
     * 防止超大文档一次性灌进浏览器打爆渲染。按「字符」而非字节截断，避免切坏多字节字符。
     */
    private static final int MAX_CHARS = 200_000;

    private final SecureFileOperationService secureFileOps;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 构造器注入：生产由 Spring 装配（两者分别是 security 模块的 {@code @Service} / 组件），
     * 单测可直接 new 并传入 mock / 真实实例。
     */
    public SandboxDocController(
            SecureFileOperationService secureFileOps,
            JwtTokenProvider jwtTokenProvider) {
        this.secureFileOps = secureFileOps;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * 读取沙箱内已落盘的 Markdown 文档正文。
     *
     * @param authHeader {@code Bearer <jwt>}
     * @param path       相对沙箱根的路径，如 {@code inbox/web/20261027221310_demo.md}
     */
    @GetMapping("/api/v1/sandbox/doc")
    public Mono<ResponseEntity<Map<String, Object>>> readDoc(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(name = "path") String path) {

        // 1. JWT 鉴权与角色门禁：网关侧前置校验，不等下游才发现越权
        String agentRole = extractAgentRole(authHeader);
        if (agentRole == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("AUTH_ERROR", "【认证错误】无效的 JWT Token 或缺少 role 信息")));
        }
        if (!ALLOWED_ROLES.contains(agentRole)) {
            log.warn("[SandboxDocGateway] 角色越权: role={}, path={}", agentRole, path);
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("SECURITY_VIOLATION",
                            "已触发 SecurityGuard 访问控制熔断（AccessDenied）：当前角色 ["
                                    + agentRole + "] 不具备策展产物读取权限，"
                                    + "仅 supervisor / webCurator 可读取")));
        }

        // 2. 业务层路径准入（后缀 + 前缀白名单），先于沙箱校验器收敛入参
        String normalized = path == null ? "" : path.trim();
        if (normalized.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(error("BAD_REQUEST", "path 参数不能为空")));
        }

        String lowered = normalized.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (ALLOWED_EXTENSIONS.stream().noneMatch(lowered::endsWith)) {
            log.warn("[SandboxDocGateway] 拒绝非文档后缀: path={}", normalized);
            return Mono.just(ResponseEntity.badRequest().body(error(
                    "BAD_REQUEST", "仅允许读取 .md / .markdown 文档: " + normalized)));
        }
        if (ALLOWED_PREFIXES.stream().noneMatch(lowered::startsWith)) {
            log.warn("[SandboxDocGateway] 拒绝沙箱 inbox/ 之外的路径: path={}", normalized);
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(
                    "SECURITY_VIOLATION",
                    "已触发 SecurityGuard 访问控制熔断（AccessDenied）："
                            + "仅允许读取沙箱 inbox/ 下的策展文档")));
        }

        // 3. 阻塞文件 I/O 卸载到 boundedElastic（WebFlux 事件循环不允许阻塞）
        return Mono.fromCallable(() -> readWithinSandbox(normalized))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok)
                // 沙箱校验器拦截（目录穿越等）：按越权处理，不暴露文件系统细节
                .onErrorResume(SecurityException.class, ex -> {
                    log.warn("[SandboxDocGateway] 沙箱路径校验失败: path={}, reason={}",
                            normalized, ex.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(error("SECURITY_VIOLATION",
                                    "已触发 SecurityGuard 访问控制熔断（AccessDenied）：路径不合法")));
                })
                .onErrorResume(IOException.class, ex -> {
                    log.warn("[SandboxDocGateway] 文档读取失败: path={}, reason={}",
                            normalized, ex.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(error("NOT_FOUND", "沙箱文档不存在或不可读: " + normalized)));
                });
    }

    /**
     * 在沙箱约束内读取文档，并按字符上限截断。
     *
     * @throws IOException      文件不存在或不可读
     * @throws SecurityException 路径穿越 / 越出沙箱根
     */
    private Map<String, Object> readWithinSandbox(String relativePath) throws IOException {
        if (!secureFileOps.fileExists(relativePath)) {
            throw new NoSuchFileException(relativePath);
        }

        long sizeBytes = secureFileOps.getFileSize(relativePath);
        String content = secureFileOps.readFileAsString(relativePath);

        boolean truncated = content.length() > MAX_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_CHARS);
        }

        log.info("[SandboxDocGateway] 读取成功: path={}, sizeBytes={}, truncated={}",
                relativePath, sizeBytes, truncated);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", relativePath);
        body.put("sizeBytes", sizeBytes);
        body.put("truncated", truncated);
        body.put("content", content);
        return body;
    }

    private static Map<String, Object> error(String stage, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stage", stage);
        body.put("message", message);
        return body;
    }

    /**
     * 从 JWT 提取 Agent 角色；无效 / 未知角色返回 null（与 CurateStreamController 同构）。
     */
    private String extractAgentRole(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return null;
            }
            String role = jwtTokenProvider.getRoleFromToken(authHeader.substring(7));
            if (role == null || role.trim().isEmpty()) {
                return null;
            }
            if (AgentRole.fromId(role) == null) {
                log.warn("[SandboxDocGateway] 无效的 Agent 角色值: {}", role);
                return null;
            }
            return role;
        } catch (Exception e) {
            log.error("[SandboxDocGateway] JWT role 提取失败: {}", e.getMessage());
            return null;
        }
    }
}
