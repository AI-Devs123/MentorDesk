package com.nexusmind.agent.tutor.service;

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
 * TutorAgent RBAC 与只读边界契约测试
 *
 * 背景: 单元测试验证行为正确性，但"安全声明是否真的挂在代码上"
 * (注解缺失 / 越权放行 / 只读边界被写 API 破坏) 属于契约层盲区。
 * 本测试做确定性静态断言: 注解存在性 + 源码级禁用导入扫描。
 */
class TutorRbacContractTest {

    private static final Path TUTOR_SOURCE_DIR =
            Path.of("src/main/java/com/nexusmind/agent/tutor");

    @Test
    @DisplayName("RBAC 契约: generateSocraticReflection 挂只读注解且放行 supervisor/tutorAgent")
    void shouldDeclareReadOnlySecuredAnnotation() throws Exception {
        Method method = TutorAgentServiceImpl.class.getMethod(
                "generateSocraticReflection", String.class, String.class, String.class);

        AgentSecured secured = method.getAnnotation(AgentSecured.class);
        assertNotNull(secured, "TutorAgent 对外服务方法必须挂 @AgentSecured，否则 Dubbo 导出后无 RBAC 拦截");

        assertTrue(secured.readOnly(),
                "TutorAgent 执行边界为 READ_ONLY_RAG，必须声明 readOnly=true");
        assertTrue(secured.audit(),
                "TutorAgent 推理链路应开启审计");

        List<String> allowed = Arrays.asList(secured.allowedAgents());
        assertTrue(allowed.contains("supervisor"), "Supervisor 编排调用必须放行");
        assertTrue(allowed.contains("tutorAgent"), "TutorAgent 自身角色必须放行");
    }

    @Test
    @DisplayName("只读边界: tutor 包源码禁用文件写/AST 解析危险 API")
    void shouldNotImportWriteOrAstApis() throws IOException {
        // 确定性源码扫描: 任一禁用导入出现即失败 (无人工判断空间)
        List<String> forbiddenImports = List.of(
                "com.nexusmind.agent.storage.AppendOnlyFileChannelWriter",
                "com.nexusmind.agent.storage.SandboxPathValidator",
                "java.nio.file.Files",
                "java.io.FileOutputStream",
                "java.io.FileWriter",
                "com.github.javaparser",
                "org.eclipse.jgit");

        try (Stream<Path> files = Files.walk(TUTOR_SOURCE_DIR)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty(), "tutor 源目录应存在，工作目录校验失败则本测试无意义");

            for (Path file : javaFiles) {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }
                    for (String forbidden : forbiddenImports) {
                        assertFalse(trimmed.contains(forbidden),
                                "只读边界违背: " + file.getFileName() + " 导入了禁用 API -> " + trimmed);
                    }
                }
            }
        }
    }
}
