package com.nexusmind.agent.code;

import com.nexusmind.agent.code.service.CodeAgentServiceImpl;
import com.nexusmind.security.rbac.AgentSecured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeAgent RBAC 与代码工作区隔离契约测试（对标 TutorRbacContractTest）。
 *
 * 单元测试验证行为正确性后，本测试做确定性<b>静态断言</b>：
 * 注解存在性 + 源码级禁用导入扫描，堵住"安全声明缺失 / 越权访问私密目录"的契约盲区。
 */
class CodeAgentRbacContractTest {

    private static final Path CODE_SOURCE_DIR =
            Path.of("src/main/java/com/nexusmind/agent/code");

    @Test
    @DisplayName("RBAC 契约: analyzeProject 挂 @AgentSecured 且精确放行 supervisor/codeAgent")
    void shouldDeclareSecuredAnnotation() throws Exception {
        Method method = CodeAgentServiceImpl.class.getMethod(
                "analyzeProject", com.nexusmind.api.dto.CodeAnalysisRequest.class);

        AgentSecured secured = method.getAnnotation(AgentSecured.class);
        assertNotNull(secured, "CodeAgent 对外服务方法必须挂 @AgentSecured，否则 Dubbo 导出后无 RBAC 拦截");

        assertTrue(secured.audit(), "CodeAgent 分析链路应开启审计");
        List<String> allowed = Arrays.asList(secured.allowedAgents());
        assertTrue(allowed.contains("supervisor"), "Supervisor 编排调用必须放行");
        assertTrue(allowed.contains("codeAgent"), "CodeAgent 自身角色必须放行");
        assertFalse(allowed.contains("journalAgent"), "日记代理不得调用代码分析");
        assertFalse(allowed.contains("tutorAgent"), "导师代理（只读RAG）不得调用代码分析");
    }

    @Test
    @DisplayName("隔离边界: code 包源码禁止导入 journal / 遗留 storage.SandboxPathValidator")
    void shouldNotImportJournalOrLegacySandbox() throws IOException {
        // 确定性源码扫描: 任一禁用导入出现即失败（无人工判断空间）
        List<String> forbiddenImports = List.of(
                "com.nexusmind.agent.journal",                          // 禁止访问日记私密数据
                "com.nexusmind.agent.storage.SandboxPathValidator"      // 必须用 security 的 PathSandboxValidator
        );

        try (Stream<Path> files = Files.walk(CODE_SOURCE_DIR)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty(), "code 源目录应存在，工作目录校验失败则本测试无意义");

            for (Path file : javaFiles) {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }
                    for (String forbidden : forbiddenImports) {
                        assertFalse(trimmed.contains(forbidden),
                                "隔离边界违背: " + file.getFileName() + " 导入了禁用 API -> " + trimmed);
                    }
                }
            }
        }
    }
}
