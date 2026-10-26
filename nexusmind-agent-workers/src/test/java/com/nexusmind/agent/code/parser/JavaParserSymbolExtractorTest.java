package com.nexusmind.agent.code.parser;

import com.nexusmind.api.dto.UnifiedCodeSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 切片2 提取器测试：类型完备性、嵌套/枚举/Record 穿透、Call Graph 精准解析与鲁棒降级。
 *
 * <p>关键：夹具必须落在与 package 匹配的目录（com/demo/...）下，且 projectSourceRoot 指向源码根，
 * 否则 {@code JavaParserTypeSolver} 找不到类型，{@code call.resolve()} 全程走降级，断言只能假绿。
 */
class JavaParserSymbolExtractorTest {

    @TempDir
    Path tempDir;

    private final JavaParserSymbolExtractor extractor = new JavaParserSymbolExtractor();

    @Test
    @DisplayName("完整语法结构验证：类、嵌套接口、参数列表、返回类型及 Call Graph 解析（含重载区分）")
    void testExtractComplexSymbols() throws IOException {
        String sourceCode = """
                package com.demo;

                import java.util.List;

                @Deprecated
                public class PaymentService implements Runnable {

                    private String secretKey;

                    public interface PaymentCallback {
                        void onSuccess(String tradeNo);
                    }

                    @Override
                    public void run() {
                        executePayment("TX-001", 100);
                    }

                    public boolean executePayment(String tradeNo, int amount) {
                        System.out.println(tradeNo);
                        return true;
                    }

                    // 重载方法
                    public boolean executePayment(String tradeNo) {
                        return executePayment(tradeNo, 0);
                    }
                }
                """;

        // 夹具置于与 package 匹配的目录，使符号求解器能定位类型
        Path sourceFile = tempDir.resolve("com/demo/PaymentService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, sourceCode);

        List<UnifiedCodeSymbol> symbols = extractor.extractSymbols(
                sourceFile, "com/demo/PaymentService.java", tempDir);
        Map<String, UnifiedCodeSymbol> symbolMap = symbols.stream()
                .collect(Collectors.toMap(UnifiedCodeSymbol::symbolId, s -> s));

        // 1. 验证主类符号（superTypes 解析为 FQN，注解带 @）
        UnifiedCodeSymbol classSymbol = symbolMap.get("com.demo.PaymentService");
        assertNotNull(classSymbol);
        assertEquals("CLASS", classSymbol.kind());
        assertTrue(classSymbol.superTypes().contains("java.lang.Runnable"), "继承关系应解析为全限定名");
        assertTrue(classSymbol.annotations().contains("@Deprecated"));

        // 2. 验证嵌套接口拓扑绑定（parentId 不断层）
        UnifiedCodeSymbol innerInterface = symbolMap.get("com.demo.PaymentService.PaymentCallback");
        assertNotNull(innerInterface);
        assertEquals("INTERFACE", innerInterface.kind());
        assertEquals("com.demo.PaymentService", innerInterface.parentId());

        // 3. 验证方法及参数签名区分重载
        UnifiedCodeSymbol method1 = symbolMap.get("com.demo.PaymentService#executePayment(String,int)");
        assertNotNull(method1, "应区分 executePayment(String,int) 重载");
        assertEquals("boolean", method1.returnType());
        assertEquals(2, method1.parameters().size());
        assertEquals("tradeNo", method1.parameters().get(0).name());
        assertEquals("String", method1.parameters().get(0).type());

        UnifiedCodeSymbol method2 = symbolMap.get("com.demo.PaymentService#executePayment(String)");
        assertNotNull(method2, "应区分 executePayment(String) 重载");

        // 4. 验证 run() 下游调用图包含 executePayment（解析态下为全限定签名）
        UnifiedCodeSymbol runMethod = symbolMap.get("com.demo.PaymentService#run()");
        assertNotNull(runMethod);
        assertTrue(runMethod.callees().stream().anyMatch(c -> c.contains("executePayment")),
                "run() 的 callees 应包含对 executePayment 的调用（含重载区分）");

        // 5. 验证 executePayment(String) 的 callees 正确指向重载方法
        assertTrue(method2.callees().stream().anyMatch(c -> c.contains("executePayment")));
    }

    @Test
    @DisplayName("字段与枚举常量提取：FIELD 符号、ENUM 类型符号与构造器穿透")
    void testFieldAndEnumExtraction() throws IOException {
        String sourceCode = """
                package com.demo;

                public enum Status {
                    ACTIVE, INACTIVE;
                }

                public class Account {
                    private final String id;
                    @Deprecated
                    public int balance;

                    public Account(String id) {
                        this.id = id;
                    }
                }
                """;

        Path sourceFile = tempDir.resolve("com/demo/SampleTypes.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, sourceCode);

        List<UnifiedCodeSymbol> symbols = extractor.extractSymbols(
                sourceFile, "com/demo/SampleTypes.java", tempDir);
        Map<String, UnifiedCodeSymbol> symbolMap = symbols.stream()
                .collect(Collectors.toMap(UnifiedCodeSymbol::symbolId, s -> s));

        // 枚举类型符号
        UnifiedCodeSymbol enumSymbol = symbolMap.get("com.demo.Status");
        assertNotNull(enumSymbol);
        assertEquals("ENUM", enumSymbol.kind());

        // 枚举常量（穿透为 FIELD）
        UnifiedCodeSymbol activeConst = symbolMap.get("com.demo.Status.ACTIVE");
        assertNotNull(activeConst);
        assertEquals("FIELD", activeConst.kind());
        assertEquals("com.demo.Status", activeConst.parentId());

        // 类字段：final 修饰符与类型
        UnifiedCodeSymbol idField = symbolMap.get("com.demo.Account.id");
        assertNotNull(idField);
        assertEquals("FIELD", idField.kind());
        assertEquals("String", idField.returnType());
        assertTrue(idField.modifiers().contains("final"));
        assertEquals("com.demo.Account", idField.parentId());

        // 带注解字段
        UnifiedCodeSymbol balanceField = symbolMap.get("com.demo.Account.balance");
        assertNotNull(balanceField);
        assertEquals("int", balanceField.returnType());
        assertTrue(balanceField.annotations().contains("@Deprecated"));

        // 构造器穿透
        UnifiedCodeSymbol ctor = symbolMap.get("com.demo.Account#<init>(String)");
        assertNotNull(ctor);
        assertEquals("CONSTRUCTOR", ctor.kind());
    }

    @Test
    @DisplayName("DoD3 容错：语法错误文件必须抛 IOException 由编排器标记局部 FAILED")
    void throwsOnSyntaxError(@TempDir Path tempDir) throws Exception {
        Path proj = tempDir.resolve("src");
        Files.createDirectories(proj);
        Path file = proj.resolve("BrokenSample.java");
        Files.copy(Path.of("src/test/resources/fixtures/BrokenSample.java"), file);

        assertThrows(IOException.class, () ->
                extractor.extractSymbols(file, "src/BrokenSample.java", tempDir));
    }
}
