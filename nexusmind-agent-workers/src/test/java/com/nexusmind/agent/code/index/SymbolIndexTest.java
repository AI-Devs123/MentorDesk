package com.nexusmind.agent.code.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.api.dto.UnifiedCodeSymbol;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 符号倒排索引测试（CodeAgent 切片3）。
 *
 * <p>重点守护两件事：① 反向调用查询必须是<b>精确键</b>匹配，不能出现 {@code run} 命中
 * {@code runner} 的子串假阳性；② 分区隔离，多任务并发分析互不污染。
 */
class SymbolIndexTest {

    @TempDir
    Path sandbox;

    private final ObjectMapper om = new ObjectMapper();
    private SymbolIndex index;

    @BeforeEach
    void setUp() {
        index = new SymbolIndex(om, new PathSandboxValidator(sandbox.toString()));
    }

    @Test
    @DisplayName("精确性: run 的调用者不得混入 runner 的调用者（禁止子串匹配）")
    void callersMustMatchExactly() {
        index.registerAll("t1", List.of(
                symbol("com.demo.X#caller()", "caller", "METHOD", List.of("com.demo.A#run(int)")),
                symbol("com.demo.Y#other()", "other", "METHOD", List.of("com.demo.A#runner()"))
        ));

        List<String> callers = index.callersOf("t1", symbol("com.demo.A#run(int)", "run", "METHOD", List.of()));

        assertEquals(List.of("com.demo.X#caller()"), callers,
                "run 只应命中精确调用它自己的符号，runner 的调用者不得混入");
    }

    @Test
    @DisplayName("退化策略: 仅记录裸方法名时，可按方法签名键召回（仍是等值匹配）")
    void fallsBackToBareMethodNameKey() {
        index.registerAll("t1", List.of(
                symbol("com.demo.W#computeTotal()", "computeTotal", "METHOD", List.of())
        ));
        // 调用方只记录了裸方法名（三方缺失时的降级产物）
        index.registerAll("t1", List.of(
                symbol("com.demo.Z#checkout()", "checkout", "METHOD", List.of("computeTotal()"))
        ));

        List<String> callers = index.callersOf("t1",
                symbol("com.demo.W#computeTotal()", "computeTotal", "METHOD", List.of()));

        assertEquals(List.of("com.demo.Z#checkout()"), callers,
                "精确键未命中时应退化为方法签名/裸名等值键召回");
    }

    @Test
    @DisplayName("自调用不计入上游调用者")
    void selfCallIsExcluded() {
        index.registerAll("t1", List.of(
                symbol("com.demo.A#loop()", "loop", "METHOD", List.of("com.demo.A#loop()"))
        ));

        assertTrue(index.callersOf("t1", symbol("com.demo.A#loop()", "loop", "METHOD", List.of())).isEmpty(),
                "递归自调用不应把自己列为上游调用者");
    }

    @Test
    @DisplayName("分区隔离: t1 的符号不得出现在 t2 的检索结果中")
    void partitionsAreIsolated() {
        index.registerAll("t1", List.of(symbol("com.demo.A#one()", "one", "METHOD", List.of())));
        index.registerAll("t2", List.of(symbol("com.demo.B#two()", "two", "METHOD", List.of())));

        assertEquals(1, index.find("t1", null, null).size());
        assertEquals("com.demo.A#one()", index.find("t1", null, null).get(0).symbolId());
        assertEquals(1, index.find("t2", null, null).size());
        assertEquals(2, index.find(null, null, null).size(), "scope 为空时应跨分区聚合");
    }

    @Test
    @DisplayName("检索: 关键词 + kind 双重过滤")
    void findFiltersByKeywordAndKind() {
        index.registerAll("t1", List.of(
                symbol("com.demo.OrderService", "OrderService", "CLASS", List.of()),
                symbol("com.demo.OrderService#checkout(java.lang.Long)", "checkout", "METHOD", List.of())
        ));

        assertEquals(1, index.find("t1", "OrderService", "CLASS").size(), "kind=CLASS 应只命中类型符号");
        assertEquals(1, index.find("t1", "checkout", "METHOD").size());
        assertEquals(2, index.find("t1", "OrderService", null).size(), "不限 kind 时类型与方法都应命中");
    }

    @Test
    @DisplayName("数据源: 可从切片2 的 symbols.jsonl 装载索引")
    void loadsFromSymbolsJsonl() throws Exception {
        Path projectDir = sandbox.resolve("projects").resolve("t9");
        Files.createDirectories(projectDir);

        UnifiedCodeSymbol caller = symbol("com.demo.Svc#run()", "run", "METHOD",
                List.of("com.demo.Dao#save()"));
        UnifiedCodeSymbol dao = symbol("com.demo.Dao#save()", "save", "METHOD", List.of());
        String jsonl = om.writeValueAsString(caller) + "\n" + om.writeValueAsString(dao) + "\n";
        Files.writeString(projectDir.resolve("symbols.jsonl"), jsonl, StandardCharsets.UTF_8);

        int loaded = index.loadFromJsonl("t9");

        assertEquals(2, loaded);
        assertEquals(2, index.size("t9"));
        assertEquals(List.of("com.demo.Svc#run()"),
                index.callersOf("t9", dao), "装载后应能反查到真实调用者");
    }

    private static UnifiedCodeSymbol symbol(String symbolId, String name, String kind, List<String> callees) {
        return new UnifiedCodeSymbol(
                symbolId,
                null,
                "JAVA",
                name,
                kind,
                "Demo.java",
                1,
                10,
                List.of("public"),
                "void",
                List.of(),
                List.of(),
                name + "()",
                "",
                callees,
                List.of()
        );
    }
}
