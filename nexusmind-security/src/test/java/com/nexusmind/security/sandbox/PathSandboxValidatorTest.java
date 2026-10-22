package com.nexusmind.security.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PathSandboxValidator Security Tests")
class PathSandboxValidatorTest {
    
    @TempDir
    Path sandboxDir;
    
    private PathSandboxValidator validator;
    
    @BeforeEach
    void setUp() {
        validator = new PathSandboxValidator(sandboxDir.toString());
    }
    
    // ============ 正常场景 ============
    
    @Test
    @DisplayName("应该允许有效的相对路径")
    void testValidRelativePath() {
        assertDoesNotThrow(() -> {
            Path result = validator.resolveSecurePath("logs/app.log");
            assertTrue(result.toString().contains("logs"));
        });
    }
    
    @Test
    @DisplayName("应该允许简单的文件名")
    void testSimpleFileName() {
        assertDoesNotThrow(() -> {
            Path result = validator.resolveSecurePath("file.txt");
            assertTrue(result.toString().endsWith("file.txt"));
        });
    }
    
    @Test
    @DisplayName("应该允许嵌套目录")
    void testNestedDirectories() {
        assertDoesNotThrow(() -> {
            Path result = validator.resolveSecurePath("data/2024/logs/app.log");
            assertTrue(result.toString().contains("data"));
        });
    }
    
    // ============ 路径遍历攻击 ============
    
    @Test
    @DisplayName("应该拒绝 .. 路径遍历")
    void testPathTraversalDetection() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("../etc/passwd")
        );
    }
    
    @Test
    @DisplayName("应该拒绝多层 .. 遍历")
    void testMultiLevelPathTraversal() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("../../../../../../etc/passwd")
        );
    }
    
    @Test
    @DisplayName("应该拒绝中间的 .. 序列")
    void testEmbeddedPathTraversal() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("logs/../../../etc/passwd")
        );
    }
    
    @Test
    @DisplayName("应该拒绝反斜杠的 .. 遍历（Windows）")
    void testBackslashPathTraversal() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("..\\..\\etc\\passwd")
        );
    }
    
    // ============ 字符截断攻击 ============
    
    @Test
    @DisplayName("应该拒绝空字节（Null Byte）注入")
    void testNullByteInjection() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("file.txt\0.jpg")
        );
    }
    
    @Test
    @DisplayName("应该拒绝 UTF-8 空字节编码")
    void testUtf8NullByte() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("file.txt\u0000.jpg")
        );
    }
    
    @Test
    @DisplayName("应该拒绝控制字符")
    void testControlCharacters() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("file\u001a.txt")  // EOF 字符
        );
    }
    
    // ============ 编码和绕过技巧 ============
    
    @Test
    @DisplayName("应该拒绝 URL 编码的 .. (%2e%2e)")
    void testUrlEncodedPathTraversal() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("logs/%2e%2e/etc/passwd")
        );
    }
    
    @Test
    @DisplayName("应该拒绝双 URL 编码的 .. (%252e%252e)")
    void testDoubleUrlEncodedPathTraversal() {
        assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("logs/%252e%252e/etc/passwd")
        );
    }
    
    // ============ 符号链接逃逸 ============
    
    @Test
    @DisplayName("应该拒绝符号链接逃逸攻击")
    void testSymlinkEscapeDetection() throws Exception {
        // 创建一个指向沙箱外的符号链接
        Path symlink = sandboxDir.resolve("evil_link");
        Path outside = Paths.get("/etc");  // 系统目录（通常存在）
        
        // 尝试创建符号链接（可能失败，因为权限或系统限制）
        try {
            Files.createSymbolicLink(symlink, outside);
            
            // 如果成功创建，应该拒绝访问
            SecurityException ex = assertThrows(SecurityException.class, () ->
                validator.resolveSecurePath("evil_link/passwd")
            );
            assertTrue(ex.getMessage().contains("符号链接指向沙箱外部"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 跳过：系统不支持符号链接或权限不足
        }
    }
    
    // ============ 特殊情况 ============
    
    @Test
    @DisplayName("应该拒绝空路径")
    void testEmptyPath() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("")
        );
        assertTrue(ex.getMessage().contains("路径不能为空"));
    }
    
    @Test
    @DisplayName("应该拒绝 null 路径")
    void testNullPath() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath(null)
        );
        assertTrue(ex.getMessage().contains("路径不能为空"));
    }
    
    @Test
    @DisplayName("应该拒绝仅空格的路径")
    void testWhitespacePath() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath("   ")
        );
        assertTrue(ex.getMessage().contains("路径不能为空"));
    }
    
    @Test
    @DisplayName("应该拒绝超长路径")
    void testOverlongPath() {
        String longPath = "a".repeat(5000);
        SecurityException ex = assertThrows(SecurityException.class, () ->
            validator.resolveSecurePath(longPath)
        );
        assertTrue(ex.getMessage().contains("路径长度超过限制"));
    }
    
    // ============ 缓存功能 ============
    
    @Test
    @DisplayName("路径缓存应该正常工作")
    void testPathCaching() {
        Path path1 = validator.resolveSecurePath("logs/app.log");
        Path path2 = validator.resolveSecurePath("logs/app.log");
        
        // 应该返回相同的 Path 对象（从缓存）
        assertEquals(path1, path2);
        assertEquals(1, validator.getCacheSize());
    }
    
    @Test
    @DisplayName("缓存清空应该有效")
    void testCacheClear() {
        validator.resolveSecurePath("logs/app.log");
        assertEquals(1, validator.getCacheSize());
        
        validator.clearCache();
        assertEquals(0, validator.getCacheSize());
    }
    
    // ============ 相对路径计算 ============
    
    @Test
    @DisplayName("应该能计算相对路径")
    void testRelativePathCalculation() {
        Path absolute = validator.resolveSecurePath("data/file.txt");
        Path relative = validator.getRelativePath(absolute);
        
        // 跨平台兼容：在 Windows 上会使用 \，Unix 上使用 /
        // 直接检查相对路径是否包含期望的文件名
        assertTrue(relative.toString().contains("data"));
        assertTrue(relative.toString().contains("file.txt"));
    }
    
    @Test
    @DisplayName("超出沙箱的路径不能计算相对路径")
    void testRelativePathOutsideSandbox() {
        Path outside = Paths.get("/etc/passwd");
        
        assertThrows(SecurityException.class, () ->
            validator.getRelativePath(outside)
        );
    }
}
