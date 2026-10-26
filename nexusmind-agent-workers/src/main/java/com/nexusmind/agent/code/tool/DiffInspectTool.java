package com.nexusmind.agent.code.tool;

import com.nexusmind.security.destructive.DiffEngine;
import com.nexusmind.security.destructive.RiskLevel;
import com.nexusmind.security.rbac.AgentSecured;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 原子工具：代码改动只读审查（CodeAgent 切片3）。
 *
 * <p><b>修复原设计的"伪 diff"硬伤：</b>原实现以"行数变少"判定破坏性，既会把"删几行空行"
 * 误报为破坏，又会漏掉"用更多行覆盖核心逻辑"。本实现改用
 * {@link DiffEngine}（LCS 行级 diff）精确计算新增 / 删除 / 修改行数，并按
 * {@link RiskLevel} 评估风险，与 Week3 的 {@code DestructiveActionAdvisor} <b>共用同一引擎</b>，
 * 避免安全域出现两套阈值。
 *
 * <p><b>只读铁律：</b>本工具只读取现有文件内容做比对，<b>绝不</b>把 {@code proposedContent} 落盘。
 * 真正写入必须走 Week3 的破坏性操作确认链路。
 */
@Component
public class DiffInspectTool {

    private static final Logger log = LoggerFactory.getLogger(DiffInspectTool.class);

    /** 触碰即判 CRITICAL 的关键文件片段（安全 / 配置 / 构建 / 密钥） */
    private static final List<String> SENSITIVE_MARKERS = List.of(
            ".env", "pom.xml", "build.gradle", "settings.xml",
            "application.yml", "application.yaml", "application.properties",
            "bootstrap.yml", "bootstrap.yaml",
            "Dockerfile", "docker-compose", "Jenkinsfile",
            ".github/workflows", "k8s", "secret", "credential", "keystore", ".pem", ".key"
    );

    /** diff 预览最大行数与字符数，防止超大 diff 撑爆 LLM 上下文 */
    private static final int MAX_PREVIEW_LINES = 200;
    private static final int MAX_PREVIEW_CHARS = 8000;

    private final PathSandboxValidator validator;

    public DiffInspectTool(PathSandboxValidator validator) {
        this.validator = validator;
    }

    /**
     * @param taskId           任务标识（审计追踪）
     * @param projectSubPath   项目在沙箱内的子路径
     * @param relativeFilePath 待审查文件相对项目根的路径
     * @param proposedContent  待审查的新内容（<b>仅比对，不落盘</b>）
     */
    public record DiffRequest(String taskId,
                              String projectSubPath,
                              String relativeFilePath,
                              String proposedContent) {
    }

    /**
     * @param originalLines      原文件行数（文件不存在则为 0）
     * @param proposedLines      拟写入内容行数
     * @param addedLines         纯新增行数
     * @param deletedLines       纯删除行数
     * @param modifiedLines      被替换（修改）的行数
     * @param riskLevel          Week3 同源风险等级
     * @param destructive        是否判定为破坏性改动（HIGH / CRITICAL）
     * @param touchesSensitivePath 是否触碰配置 / 构建 / 密钥等关键文件
     * @param diffPreview        标准 unified diff 预览（超长已截断）
     */
    public record DiffResponse(String relativeFilePath,
                               int originalLines,
                               int proposedLines,
                               int addedLines,
                               int deletedLines,
                               int modifiedLines,
                               RiskLevel riskLevel,
                               boolean destructive,
                               boolean touchesSensitivePath,
                               String diffPreview) {
    }

    /**
     * 只读比对代码改动，评估破坏性风险。
     *
     * @throws IOException 读取原文件失败
     */
    @AgentSecured(allowedAgents = {"codeAgent"}, readOnly = true, audit = true)
    public DiffResponse execute(DiffRequest request) throws IOException {
        if (request == null || request.relativeFilePath() == null || request.relativeFilePath().isBlank()) {
            throw new IllegalArgumentException("relativeFilePath 不能为空");
        }

        String projectBase = normalizeProjectSubPath(request.projectSubPath());
        String relativePath = normalizeRelativePath(request.relativeFilePath());

        Path projectRoot = validator.resolveSecurePath(projectBase);
        Path targetFile = validator.resolveSecurePath(projectBase + "/" + relativePath);
        if (!targetFile.startsWith(projectRoot)) {
            throw new SecurityException("SecurityGuard 拦截: 目标文件不在指定项目根内 -> " + relativePath);
        }

        List<String> originalLines = Files.isRegularFile(targetFile)
                ? Files.readAllLines(targetFile, StandardCharsets.UTF_8)
                : List.of();
        String proposed = request.proposedContent() == null ? "" : request.proposedContent();
        List<String> proposedLines = proposed.lines().toList();

        DiffEngine.DiffStats stats = DiffEngine.computeStats(originalLines, proposedLines);
        boolean sensitive = touchesSensitivePath(relativePath);
        RiskLevel riskLevel = DiffEngine.assessRisk(stats, sensitive);
        boolean destructive = riskLevel.isHigherThan(RiskLevel.MEDIUM);

        String preview = truncate(DiffEngine.toUnifiedDiff(originalLines, proposedLines));

        log.info("[DiffInspectTool] 审查完成: file={}, +{}/-{}/~{} lines, risk={}, destructive={}",
                relativePath, stats.added(), stats.deleted(), stats.modified(), riskLevel, destructive);

        return new DiffResponse(
                relativePath,
                originalLines.size(),
                proposedLines.size(),
                stats.added(),
                stats.deleted(),
                stats.modified(),
                riskLevel,
                destructive,
                sensitive,
                preview
        );
    }

    /**
     * 判断是否触碰关键文件（大小写不敏感的子串包含，用于路径判定而非符号匹配）。
     */
    static boolean touchesSensitivePath(String relativePath) {
        String lower = relativePath.toLowerCase().replace('\\', '/');
        for (String marker : SENSITIVE_MARKERS) {
            if (lower.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String diff) {
        String[] lines = diff.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int emitted = 0;
        for (String line : lines) {
            if (emitted >= MAX_PREVIEW_LINES || sb.length() >= MAX_PREVIEW_CHARS) {
                sb.append("... (diff 预览已截断)\n");
                break;
            }
            sb.append(line).append('\n');
            emitted++;
        }
        String out = sb.toString();
        return out.length() > MAX_PREVIEW_CHARS
                ? out.substring(0, MAX_PREVIEW_CHARS) + "\n... (diff 预览已截断)\n"
                : out;
    }

    private static String normalizeProjectSubPath(String projectSubPath) {
        String path = projectSubPath == null ? "" : projectSubPath.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.equals("projects")) {
            return "projects";
        }
        return path.startsWith("projects/") ? path : "projects/" + path;
    }

    private static String normalizeRelativePath(String relativeFilePath) {
        String path = relativeFilePath.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }
}
