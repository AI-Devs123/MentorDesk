package com.nexusmind.agent.code.parser;

import com.nexusmind.api.dto.UnifiedCodeSymbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaParserSymbolExtractorTest {

    private final JavaParserSymbolExtractor extractor = new JavaParserSymbolExtractor();

    @Test
    void extractsClassMethodAndCallEdges(@TempDir Path tempDir) throws Exception {
        Path proj = tempDir.resolve("src");
        Files.createDirectories(proj);
        Path file = proj.resolve("SampleOrderService.java");
        Files.copy(Path.of("src/test/resources/fixtures/SampleOrderService.java"), file);

        List<UnifiedCodeSymbol> symbols = extractor.extractSymbols(
                file.toFile(), "src/SampleOrderService.java", tempDir);

        assertTrue(symbols.stream().anyMatch(s -> s.kind().equals("CLASS")), "应提取到类节点");
        assertTrue(symbols.stream().anyMatch(s -> s.kind().equals("METHOD")), "应提取到方法节点");

        List<UnifiedCodeSymbol> methods = symbols.stream()
                .filter(s -> s.kind().equals("METHOD")).toList();
        assertFalse(methods.isEmpty(), "应存在方法符号");

        // callees 不应为空：checkout/persist 等调用了同仓私有方法，应解析为全限定签名；
        // 即使外部依赖解析失败也降级为裸名，提取流不中断
        boolean hasCallees = methods.stream()
                .anyMatch(m -> m.callees() != null && !m.callees().isEmpty());
        assertTrue(hasCallees, "至少应提取到方法调用边（callees 非空）");
    }

    @Test
    void throwsOnSyntaxError(@TempDir Path tempDir) throws Exception {
        Path proj = tempDir.resolve("src");
        Files.createDirectories(proj);
        Path file = proj.resolve("BrokenSample.java");
        Files.copy(Path.of("src/test/resources/fixtures/BrokenSample.java"), file);

        // 语法错误：getProblems() 非空抛 IOException，或 getResult() 为空抛 IOException
        assertThrows(java.io.IOException.class, () ->
                extractor.extractSymbols(file.toFile(), "src/BrokenSample.java", tempDir));
    }
}
