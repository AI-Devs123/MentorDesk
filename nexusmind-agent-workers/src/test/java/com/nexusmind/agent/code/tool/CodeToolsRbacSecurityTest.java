package com.nexusmind.agent.code.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.code.index.SymbolIndex;
import com.nexusmind.agent.code.parser.JavaParserSymbolExtractor;
import com.nexusmind.agent.code.security.CodeToolSecurityAspect;
import com.nexusmind.security.rbac.AgentRole;
import com.nexusmind.security.rbac.AgentSecurityContextHolder;
import com.nexusmind.security.rbac.AgentSecurityException;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 原子工具集 RBAC 攻防测试（CodeAgent 切片3）。
 *
 * <p>与既有 {@code CodeAgentRbacContractTest}（Dubbo 边界静态契约）互补：本测试验证<b>工具层</b>
 * {@link CodeToolSecurityAspect} 的实际拦截行为——合规角色放行、越权角色 / 缺失上下文一律熔断。
 *
 * <p>使用真实 {@link PathSandboxValidator}（临时沙箱）+ 真实
 * {@link JavaParserSymbolExtractor}，避免 mock 掩盖"工具与沙箱/解析器 API 不匹配"的问题。
 */
class CodeToolsRbacSecurityTest {

    private static final String PROJECT = "demo";
    private static final String SOURCE_FILE = "SampleOrderService.java";

    @TempDir
    Path sandbox;

    private SymbolIndex symbolIndex;
    private ParseAstTool parseAstTool;
    private SearchSymbolsTool searchSymbolsTool;
    private DiffInspectTool diffInspectTool;

    @BeforeEach
    void setUp() throws IOException {
        PathSandboxValidator validator = new PathSandboxValidator(sandbox.toString());
        symbolIndex = new SymbolIndex(new ObjectMapper(), validator);

        Path projectDir = sandbox.resolve("projects").resolve(PROJECT);
        Files.createDirectories(projectDir);
        Files.copy(Path.of("src/test/resources/fixtures/SampleOrderService.java"),
                projectDir.resolve(SOURCE_FILE));

        CodeToolSecurityAspect aspect = new CodeToolSecurityAspect();
        parseAstTool = proxy(new ParseAstTool(new JavaParserSymbolExtractor(), validator, symbolIndex), aspect);
        searchSymbolsTool = proxy(new SearchSymbolsTool(symbolIndex), aspect);
        diffInspectTool = proxy(new DiffInspectTool(validator), aspect);
    }

    @AfterEach
    void tearDown() {
        AgentSecurityContextHolder.clear();
    }

    private static <T> T proxy(T target, CodeToolSecurityAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    @DisplayName("合规调用: codeAgent 可正常执行 ParseAst / SearchSymbols / DiffInspect")
    void shouldAllowCodeAgentOnAllTools() throws IOException {
        AgentSecurityContextHolder.setRole(AgentRole.CODE_AGENT);

        ParseAstTool.ParseAstResponse parsed = parseAstTool.execute(
                new ParseAstTool.ParseAstRequest("t1", PROJECT, SOURCE_FILE));
        assertTrue(parsed.symbolCount() > 0, "应提取到符号");
        assertTrue(parsed.indexSize() > 0, "解析后应同步写入符号索引");
        assertTrue(symbolIndex.size("t1") > 0, "索引分区应可查询");

        List<SearchSymbolsTool.SearchResult> found = searchSymbolsTool.execute(
                new SearchSymbolsTool.SearchRequest("t1", "SampleOrderService", "CLASS", true, true));
        assertFalse(found.isEmpty(), "应检索到已解析的类型符号");

        DiffInspectTool.DiffResponse diff = diffInspectTool.execute(
                new DiffInspectTool.DiffRequest("t1", PROJECT, SOURCE_FILE,
                        "class SampleOrderService {}\n"));
        assertTrue(diff.originalLines() > 0, "应读取到原文件行数");
    }

    @Test
    @DisplayName("越权拦截: journalAgent 调任一原子工具都必须被熔断")
    void shouldBlockJournalAgent() {
        AgentSecurityContextHolder.setRole(AgentRole.JOURNAL_AGENT);

        assertAll(
                () -> assertThrows(AgentSecurityException.class, () -> parseAstTool.execute(
                        new ParseAstTool.ParseAstRequest("t1", PROJECT, SOURCE_FILE))),
                () -> assertThrows(AgentSecurityException.class, () -> searchSymbolsTool.execute(
                        new SearchSymbolsTool.SearchRequest("t1", "SampleOrderService", null, false, false))),
                () -> assertThrows(AgentSecurityException.class, () -> diffInspectTool.execute(
                        new DiffInspectTool.DiffRequest("t1", PROJECT, SOURCE_FILE, "class X {}")))
        );
    }

    @Test
    @DisplayName("越权拦截: tutorAgent（只读 RAG）不得触碰代码工具")
    void shouldBlockTutorAgent() {
        AgentSecurityContextHolder.setRole(AgentRole.TUTOR_AGENT);

        assertThrows(AgentSecurityException.class, () -> parseAstTool.execute(
                new ParseAstTool.ParseAstRequest("t1", PROJECT, SOURCE_FILE)));
        assertThrows(AgentSecurityException.class, () -> searchSymbolsTool.execute(
                new SearchSymbolsTool.SearchRequest("t1", "OrderService", null, false, false)));
    }

    @Test
    @DisplayName("纵深防御: supervisor 在 Dubbo 边界可调用服务，但不得直接调用原子工具")
    void shouldBlockSupervisorAtToolLayer() {
        AgentSecurityContextHolder.setRole(AgentRole.SUPERVISOR);

        assertThrows(AgentSecurityException.class, () -> parseAstTool.execute(
                new ParseAstTool.ParseAstRequest("t1", PROJECT, SOURCE_FILE)));
    }

    @Test
    @DisplayName("缺失上下文: 匿名调用直接熔断（fail-closed）")
    void shouldBlockWhenContextMissing() {
        AgentSecurityContextHolder.clear();

        AgentSecurityException ex = assertThrows(AgentSecurityException.class, () -> parseAstTool.execute(
                new ParseAstTool.ParseAstRequest("t1", PROJECT, SOURCE_FILE)));
        assertTrue(ex.getMessage().contains("角色上下文"), "异常应明确指向缺失角色上下文");
    }

    @Test
    @DisplayName("异常语义: 安全拦截必须是 RuntimeException，不能被 catch(IOException) 吞掉")
    void securityExceptionMustNotBeIoException() {
        AgentSecurityContextHolder.setRole(AgentRole.TUTOR_AGENT);

        AgentSecurityException ex = assertThrows(AgentSecurityException.class, () -> searchSymbolsTool.execute(
                new SearchSymbolsTool.SearchRequest("t1", "A", null, false, false)));

        // 用 isInstance 反射判定：直接 instanceof IOException 属"类型不可转换"，无法通过编译
        assertFalse(IOException.class.isInstance(ex),
                "安全拦截不可复用 java.nio.file.AccessDeniedException（会被 IO 异常捕获吞掉）");
        assertTrue(RuntimeException.class.isInstance(ex));
    }
}
