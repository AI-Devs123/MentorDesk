package com.nexusmind.agent.code.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.code.CancelTokenRegistry;
import com.nexusmind.agent.code.parser.JavaParserSymbolExtractor;
import com.nexusmind.agent.code.sink.SymbolSink;
import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.CodeAnalysisRequest;
import com.nexusmind.api.dto.UnifiedCodeSymbol;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 大仓遍历与任务编排器（CodeAgent 切片1 主控）。
 *
 * <p><b>职责：</b>将 Supervisor 下发的 {@link CodeAnalysisRequest} 落地为逐文件扫描的流式进度，
 * 并在每一步严守安全边界：
 * <ul>
 *   <li><b>物理 / 逻辑隔离：</b>所有路径经 {@link PathSandboxValidator#resolveSecurePath} 收敛到
 *       {@code projects/<subPath>}，CodeAgent 永不直接触碰 journal/ 等私密目录；</li>
 *   <li><b>硬伤C（落库）：</b>每文件提取完即经 {@link SymbolSink} 增量落 JSONL（复用
 *       AppendOnlyFileChannelWriter），W7 的 RAG 引擎直接消费，不堆内存；</li>
 *   <li><b>带宽治理：</b>{@code INDEXED} 帧只携带<b>当前文件</b>符号增量，杜绝 O(N²) 包溢出；</li>
 *   <li><b>单文件容灾：</b>坏文件标记 FAILED 后继续，主管道不崩；</li>
 *   <li><b>ABORT 中断：</b>复用 Week3 语义，在文件迭代边界读取取消令牌，命中即收工。</li>
 * </ul>
 */
@Component
public class ProjectSymbolAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ProjectSymbolAnalyzer.class);
    private static final String CANCEL_PREFIX = "code-analysis:";

    private final JavaParserSymbolExtractor extractor;
    private final PathSandboxValidator validator;
    private final CancelTokenRegistry cancelRegistry;
    private final SymbolSink symbolSink;
    private final ObjectMapper objectMapper;

    public ProjectSymbolAnalyzer(JavaParserSymbolExtractor extractor,
                                PathSandboxValidator validator,
                                CancelTokenRegistry cancelRegistry,
                                SymbolSink symbolSink,
                                ObjectMapper objectMapper) {
        this.extractor = extractor;
        this.validator = validator;
        this.cancelRegistry = cancelRegistry;
        this.symbolSink = symbolSink;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行项目符号分析，逐阶段回调 {@code sink}。
     *
     * @param request 分析请求（taskId / projectSubPath / language）
     * @param sink    进度帧消费者（Supervisor 侧可适配为 SSE / Dubbo 流）
     */
    public void analyze(CodeAnalysisRequest request, Consumer<CodeAnalysisProgress> sink) {
        String taskId = request.taskId();

        // 1. 沙箱隔离：强制收敛到 projects/<subPath>
        Path projectRoot;
        try {
            String rel = "projects/" + (request.projectSubPath() == null ? "" : request.projectSubPath());
            projectRoot = validator.resolveSecurePath(rel);
        } catch (SecurityException e) {
            sink.accept(build(taskId, "FAILED", null, 0, 0,
                    "{\"error\":\"" + e.getMessage() + "\"}"));
            return;
        }

        // 2. 扫描匹配文件列表
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            javaFiles = stream
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            sink.accept(build(taskId, "FAILED", null, 0, 0,
                    "{\"error\":\"遍历代码仓库失败: " + e.getMessage() + "\"}"));
            return;
        }

        int total = javaFiles.size();
        sink.accept(build(taskId, "SCANNING", "锁定源文件列表", 0, total, "{}"));

        int processed = 0;
        boolean aborted = false;
        try {
            // 3. 逐文件解析推流
            for (Path file : javaFiles) {
                // 实时感知 Week3 的 ABORT 信号（文件迭代边界，虚拟线程安全退出）
                if (cancelRegistry.isCancelled(CANCEL_PREFIX + taskId)) {
                    log.warn("[CodeAgent] 任务被用户中断: taskId={}", taskId);
                    sink.accept(build(taskId, "FAILED", null, processed, total,
                            "{\"reason\":\"TASK_ABORTED\"}"));
                    aborted = true;
                    return;
                }

                String relativeFilePath = projectRoot.relativize(file).toString();
                sink.accept(build(taskId, "PARSING", relativeFilePath, processed, total, "{}"));

                try {
                    List<UnifiedCodeSymbol> symbols = extractor.extractSymbols(
                            file.toFile(), relativeFilePath, projectRoot);
                    processed++;

                    // 硬伤C：仅发送本文件的增量符号载荷，杜绝带宽爆炸
                    symbolSink.persist(taskId, symbols);
                    String payloadJson = objectMapper.writeValueAsString(symbols);
                    sink.accept(build(taskId, "INDEXED", relativeFilePath, processed, total, payloadJson));

                } catch (Exception e) {
                    // 局部容灾：记录单文件失败，继续驱动主流程
                    log.warn("[CodeAgent] 解析单文件失败，跳过: file={}, error={}", relativeFilePath, e.getMessage());
                    processed++;
                    sink.accept(build(taskId, "FAILED", relativeFilePath, processed, total,
                            "{\"error\":\"" + e.getMessage() + "\",\"file\":\"" + relativeFilePath + "\"}"));
                }
            }
        } finally {
            // 打磨点：终态清理 ABORT 标志，防止同 taskId 复用被秒取消
            cancelRegistry.clear(CANCEL_PREFIX + taskId);
        }

        if (!aborted) {
            // 4. 终态回传
            sink.accept(build(taskId, "COMPLETED", "项目符号解析全量完成", processed, total, "{}"));
        }
    }

    private CodeAnalysisProgress build(String taskId, String stage, String currentFile,
                                       int processed, int total, String payload) {
        return new CodeAnalysisProgress(
                taskId,
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                stage,
                currentFile == null ? "" : currentFile,
                processed,
                total,
                payload
        );
    }
}
