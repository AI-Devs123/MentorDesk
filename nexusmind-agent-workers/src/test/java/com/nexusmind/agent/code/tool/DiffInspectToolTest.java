package com.nexusmind.agent.code.tool;

import com.nexusmind.security.destructive.RiskLevel;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代码改动只读审查测试（CodeAgent 切片3）。
 *
 * <p>守护两点：① 破坏性判定必须基于真实行级 diff（而非"行数变少"的伪逻辑）；
 * ② 工具<b>绝不落盘</b>，{@code proposedContent} 只能用于比对。
 */
class DiffInspectToolTest {

    private static final String PROJECT = "demo";
    private static final String SOURCE = "src/Svc.java";

    @TempDir
    Path sandbox;

    private DiffInspectTool tool;
    private Path sourceFile;

    @BeforeEach
    void setUp() throws IOException {
        tool = new DiffInspectTool(new PathSandboxValidator(sandbox.toString()));
        Path projectDir = sandbox.resolve("projects").resolve(PROJECT).resolve("src");
        Files.createDirectories(projectDir);
        sourceFile = projectDir.resolve("Svc.java");
        Files.writeString(sourceFile, "line1\nline2\n", StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("纯追加: 行数增加应记为 added，而非破坏性改动")
    void detectsPureAppend() throws IOException {
        DiffInspectTool.DiffResponse resp = tool.execute(new DiffInspectTool.DiffRequest(
                "t1", PROJECT, SOURCE, "line1\nline2\nline3\n"));

        assertEquals(2, resp.originalLines());
        assertEquals(3, resp.proposedLines());
        assertEquals(1, resp.addedLines());
        assertEquals(0, resp.deletedLines());
        assertEquals(0, resp.modifiedLines());
        assertFalse(resp.destructive(), "纯追加不是破坏性改动");
        assertEquals(RiskLevel.MEDIUM, resp.riskLevel());
    }

    @Test
    @DisplayName("整体替换: 行数不变但内容被替换，必须识别为 modified（旧实现会漏报）")
    void detectsInPlaceReplacementEvenWhenLineCountUnchanged() throws IOException {
        DiffInspectTool.DiffResponse resp = tool.execute(new DiffInspectTool.DiffRequest(
                "t1", PROJECT, SOURCE, "line1\nTOTALLY_CHANGED\n"));

        assertEquals(2, resp.originalLines());
        assertEquals(2, resp.proposedLines());
        assertEquals(0, resp.addedLines());
        assertEquals(0, resp.deletedLines());
        assertEquals(1, resp.modifiedLines(), "行数不变不等于无改动，必须识别为修改行");
        assertTrue(resp.diffPreview().contains("TOTALLY_CHANGED"), "预览应包含真实 diff 内容");
    }

    @Test
    @DisplayName("关键文件: 触碰 pom.xml 等配置/构建文件直接判 CRITICAL")
    void flagsSensitivePathAsCritical() throws IOException {
        Files.writeString(sandbox.resolve("projects").resolve(PROJECT).resolve("pom.xml"),
                "<project/>\n", StandardCharsets.UTF_8);

        DiffInspectTool.DiffResponse resp = tool.execute(new DiffInspectTool.DiffRequest(
                "t1", PROJECT, "pom.xml", "<project><dependencies/></project>\n"));

        assertTrue(resp.touchesSensitivePath());
        assertEquals(RiskLevel.CRITICAL, resp.riskLevel());
        assertTrue(resp.destructive());
    }

    @Test
    @DisplayName("只读铁律: 审查前后原文件内容必须完全一致")
    void neverWritesProposedContent() throws IOException {
        String before = Files.readString(sourceFile, StandardCharsets.UTF_8);

        tool.execute(new DiffInspectTool.DiffRequest("t1", PROJECT, SOURCE, "HACKED\n"));

        assertEquals(before, Files.readString(sourceFile, StandardCharsets.UTF_8),
                "DiffInspectTool 必须保持只读，proposedContent 不得落盘");
    }

    @Test
    @DisplayName("新文件: 原文件不存在时按全新增处理")
    void handlesNonExistingFileAsAllAdded() throws IOException {
        DiffInspectTool.DiffResponse resp = tool.execute(new DiffInspectTool.DiffRequest(
                "t1", PROJECT, "src/New.java", "a\nb\nc\n"));

        assertEquals(0, resp.originalLines());
        assertEquals(3, resp.addedLines());
        assertEquals(0, resp.deletedLines());
    }

    @Test
    @DisplayName("越界防御: 相对路径穿越项目根必须被沙箱拦截")
    void rejectsTraversalOutsideProject() {
        assertThrows(SecurityException.class, () -> tool.execute(new DiffInspectTool.DiffRequest(
                "t1", PROJECT, "../../etc/passwd", "x")));
    }
}
