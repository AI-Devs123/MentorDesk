package com.nexusmind.agent.code.tool;

import com.nexusmind.agent.code.index.SymbolIndex;
import com.nexusmind.agent.code.parser.JavaParserSymbolExtractor;
import com.nexusmind.api.dto.UnifiedCodeSymbol;
import com.nexusmind.security.rbac.AgentSecured;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 原子工具：单文件 AST 符号提取（CodeAgent 切片3）。
 *
 * <p><b>能力：</b>对指定项目下的<b>单个</b> Java 文件执行切片2 的高保真符号提取，并把结果增量写入
 * {@link SymbolIndex}，返回本次提取的元数据摘要与符号明细，供 LLM 决定下一步（继续解析 / 检索 / 审查）。
 *
 * <p><b>双层防御：</b>
 * <ul>
 *   <li><b>RBAC：</b>{@link AgentSecured} 限定只有 {@code codeAgent} 可调用；Dubbo 边界由
 *       {@code AgentRbacDubboFilter} 拦截，工具层由 {@code CodeToolSecurityAspect} 纵深拦截；</li>
 *   <li><b>沙箱：</b>所有路径经 {@link PathSandboxValidator#resolveSecurePath} 收敛到沙箱内，
 *       并在解析后二次校验"文件必须位于项目根之内"，杜绝跨项目越界读取。</li>
 * </ul>
 *
 * <p><b>只读语义：</b>本工具不写业务代码，仅解析与建索引，故 {@code readOnly = true}。
 */
@Component
public class ParseAstTool {

    private static final Logger log = LoggerFactory.getLogger(ParseAstTool.class);

    private final JavaParserSymbolExtractor extractor;
    private final PathSandboxValidator validator;
    private final SymbolIndex symbolIndex;

    public ParseAstTool(JavaParserSymbolExtractor extractor,
                        PathSandboxValidator validator,
                        SymbolIndex symbolIndex) {
        this.extractor = extractor;
        this.validator = validator;
        this.symbolIndex = symbolIndex;
    }

    /**
     * @param taskId           任务标识（索引分区 + 审计追踪）
     * @param projectSubPath   项目在沙箱内的子路径，如 {@code projects/demo}；可省略 {@code projects/} 前缀
     * @param relativeFilePath 文件相对项目根的路径，如 {@code src/main/java/com/demo/Main.java}
     */
    public record ParseAstRequest(String taskId, String projectSubPath, String relativeFilePath) {
    }

    /**
     * @param filePath    本次解析的相对文件路径
     * @param symbolCount 本次提取到的符号数
     * @param indexSize   该任务索引分区当前的符号总数
     * @param symbols     符号明细（含 callees / superTypes / modifiers 等全量语义）
     */
    public record ParseAstResponse(String filePath,
                                   int symbolCount,
                                   int indexSize,
                                   List<UnifiedCodeSymbol> symbols) {
    }

    /**
     * 解析单个 Java 文件并增量建索引。
     *
     * @throws IOException 文件读取或语法解析失败（语法错误由切片2 的 DoD3 校验抛出）
     */
    @AgentSecured(allowedAgents = {"codeAgent"}, readOnly = true, audit = true)
    public ParseAstResponse execute(ParseAstRequest request) throws IOException {
        if (request == null || request.relativeFilePath() == null || request.relativeFilePath().isBlank()) {
            throw new IllegalArgumentException("relativeFilePath 不能为空");
        }

        Path projectRoot = validator.resolveSecurePath(normalizeProjectSubPath(request.projectSubPath()));
        Path targetFile = validator.resolveSecurePath(
                normalizeProjectSubPath(request.projectSubPath()) + "/" + normalizeRelativePath(request.relativeFilePath()));

        // 沙箱之上的二次边界：必须落在项目根之内（防 "合法沙箱内但跨项目" 的越界读取）
        if (!targetFile.startsWith(projectRoot)) {
            throw new SecurityException("SecurityGuard 拦截: 目标文件不在指定项目根内 -> " + request.relativeFilePath());
        }
        if (!Files.isRegularFile(targetFile)) {
            throw new IllegalArgumentException("目标文件不存在或不是普通文件: " + request.relativeFilePath());
        }
        if (!targetFile.getFileName().toString().endsWith(".java")) {
            throw new IllegalArgumentException("ParseAstTool 仅支持 .java 文件: " + request.relativeFilePath());
        }

        String relativeFilePath = normalizeRelativePath(request.relativeFilePath());
        List<UnifiedCodeSymbol> symbols = extractor.extractSymbols(targetFile, relativeFilePath, projectRoot);
        int indexSize = symbolIndex.registerAll(request.taskId(), symbols);

        log.info("[ParseAstTool] 解析完成: file={}, symbols={}, indexSize={}",
                relativeFilePath, symbols.size(), indexSize);

        return new ParseAstResponse(relativeFilePath, symbols.size(), indexSize, symbols);
    }

    /**
     * 归一项目子路径：统一分隔符、去前导斜杠，未带 {@code projects/} 前缀则自动补齐。
     */
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
