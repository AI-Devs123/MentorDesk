package com.nexusmind.agent.code.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.code.CancelTokenRegistry;
import com.nexusmind.agent.code.index.SymbolIndex;
import com.nexusmind.agent.code.parser.JavaParserSymbolExtractor;
import com.nexusmind.agent.code.sink.SymbolSink;
import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.CodeAnalysisRequest;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSymbolAnalyzerTest {

    @TempDir Path tempDir;
    private final SymbolSink sink = Mockito.mock(SymbolSink.class);
    private final ObjectMapper om = new ObjectMapper();

    private ProjectSymbolAnalyzer analyzer() {
        return analyzer(new CancelTokenRegistry(), newSymbolIndex());
    }

    private ProjectSymbolAnalyzer analyzer(CancelTokenRegistry registry, SymbolIndex index) {
        PathSandboxValidator validator = new PathSandboxValidator(tempDir.toString());
        return new ProjectSymbolAnalyzer(
                new JavaParserSymbolExtractor(),
                validator,
                registry,
                sink,
                index,
                om);
    }

    private SymbolIndex newSymbolIndex() {
        return new SymbolIndex(om, new PathSandboxValidator(tempDir.toString()));
    }

    private void seedProject(Path sandbox, String projectName) throws Exception {
        Path proj = sandbox.resolve("projects").resolve(projectName);
        Files.createDirectories(proj);
        Files.copy(Path.of("src/test/resources/fixtures/SampleOrderService.java"),
                proj.resolve("SampleOrderService.java"));
    }

    @Test
    void streamsStagesAndPersistsIncrementally() throws Exception {
        seedProject(tempDir, "myproject");

        List<CodeAnalysisProgress> captured = new ArrayList<>();
        analyzer().analyze(new CodeAnalysisRequest("t1", "myproject", "JAVA"), captured::add);

        assertEquals("SCANNING", captured.get(0).stage());
        assertTrue(captured.stream().anyMatch(p -> p.stage().equals("INDEXED")),
                "应包含 INDEXED 帧");
        assertTrue(captured.stream().anyMatch(p -> p.stage().equals("COMPLETED")),
                "应包含 COMPLETED 终态");

        // 硬伤C：每文件增量落库
        Mockito.verify(sink).persist(Mockito.eq("t1"), Mockito.anyList());
    }

    @Test
    void marksSyntaxErrorFileAsLocalFailedAndContinues() throws Exception {
        Path proj = tempDir.resolve("projects").resolve("brokenproj");
        Files.createDirectories(proj);
        Files.copy(Path.of("src/test/resources/fixtures/SampleOrderService.java"),
                proj.resolve("SampleOrderService.java"));
        Files.copy(Path.of("src/test/resources/fixtures/BrokenSample.java"),
                proj.resolve("BrokenSample.java"));

        List<CodeAnalysisProgress> captured = new ArrayList<>();
        analyzer().analyze(new CodeAnalysisRequest("t3", "brokenproj", "JAVA"), captured::add);

        assertTrue(captured.stream().anyMatch(p ->
                        p.stage().equals("FAILED") && p.payloadJson().contains("BrokenSample.java")),
                "注入语法错误的文件应被标记局部 FAILED");
        assertTrue(captured.stream().anyMatch(p -> p.stage().equals("INDEXED")),
                "好文件仍应被正常索引 (INDEXED)");
        assertTrue(captured.stream().anyMatch(p -> p.stage().equals("COMPLETED")),
                "主管道不中断，整体任务仍应 COMPLETED");
    }

    @Test
    void abortsWhenCancelTokenSet() throws Exception {
        seedProject(tempDir, "myproject");
        CancelTokenRegistry registry = new CancelTokenRegistry();
        registry.markCancelled("code-analysis:t2");
        ProjectSymbolAnalyzer analyzer = analyzer(registry, newSymbolIndex());

        List<CodeAnalysisProgress> captured = new ArrayList<>();
        analyzer.analyze(new CodeAnalysisRequest("t2", "myproject", "JAVA"), captured::add);

        assertTrue(captured.stream().anyMatch(p ->
                        p.stage().equals("FAILED") && p.payloadJson().contains("TASK_ABORTED")),
                "ABORT 应命中并产出 FAILED(TASK_ABORTED)");
        Mockito.verify(sink, Mockito.never()).persist(Mockito.anyString(), Mockito.anyList());
    }

    @Test
    void feedsSymbolIndexWhileAnalyzing() throws Exception {
        seedProject(tempDir, "indexproj");
        SymbolIndex index = newSymbolIndex();

        List<CodeAnalysisProgress> captured = new ArrayList<>();
        analyzer(new CancelTokenRegistry(), index)
                .analyze(new CodeAnalysisRequest("t4", "indexproj", "JAVA"), captured::add);

        assertTrue(index.size("t4") > 0,
                "切片3 契约: 分析过程中应把符号实时灌入 SymbolIndex，供 SearchSymbolsTool 检索");
        assertTrue(index.find("t4", "SampleOrderService", "CLASS").size() > 0,
                "索引中应能检索到 SampleOrderService 类型符号");
    }
}
