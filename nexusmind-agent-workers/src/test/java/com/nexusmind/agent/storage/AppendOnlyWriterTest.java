package com.nexusmind.agent.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Append-Only 写入器单元测试
 * 验证截断拦截、连续追加、目录自愈能力
 */
@DisplayName("Append-Only 文件写入测试")
class AppendOnlyWriterTest {
    
    private static final Path TEST_DIR = Paths.get("data/nexusmind/sandbox/journals/test");
    private Path testFile;
    
    @BeforeEach
    void setUp() throws IOException {
        // 先彻底清空（含上一轮残留的嵌套目录），保证每个用例的前置条件干净
        deleteRecursively(TEST_DIR);
        Files.createDirectories(TEST_DIR);
        testFile = TEST_DIR.resolve("test-entry.json");
    }
    
    @AfterEach
    void tearDown() throws IOException {
        // 递归清理整棵目录树：自愈用例会在 TEST_DIR 下产生 2026/10/25 嵌套子目录，
        // 只删单文件 + deleteIfExists(空目录) 会抛 DirectoryNotEmptyException
        deleteRecursively(TEST_DIR);
    }
    
    /**
     * 后序遍历递归删除目录树（先删深层文件，再删目录）
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
    
    @Test
    @DisplayName("测试1: 新文件创建与初始写入")
    void testCreateNewFileAndWrite() throws IOException {
        String content1 = "第一行内容\n";
        
        AppendOnlyFileChannelWriter.appendToFile(testFile, content1);
        
        assertTrue(Files.exists(testFile), "文件应该被创建");
        String result = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(content1, result, "内容应该匹配");
    }
    
    @Test
    @DisplayName("测试2: 连续追加 (不截断)")
    void testContinuousAppend() throws IOException {
        String content1 = "<!-- ENTRY_START: entry1 -->\n";
        String content2 = "```json\n{\"id\":1}\n```\n";
        String content3 = "<!-- ENTRY_END -->\n";
        
        // 第一次写入
        AppendOnlyFileChannelWriter.appendToFile(testFile, content1);
        String afterFirst = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(content1, afterFirst, "第一次写入成功");
        
        // 第二次追加 (而非截断)
        AppendOnlyFileChannelWriter.appendToFile(testFile, content2);
        String afterSecond = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(content1 + content2, afterSecond, "第二次追加成功，不截断");
        
        // 第三次追加
        AppendOnlyFileChannelWriter.appendToFile(testFile, content3);
        String afterThird = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(content1 + content2 + content3, afterThird, "第三次追加成功");
    }
    
    @Test
    @DisplayName("测试3: 目录自愈 (自动创建父目录)")
    void testDirectorySelfHealing() throws IOException {
        Path nestedFile = TEST_DIR.resolve("2026/10/25/entry-test.json");
        assertFalse(Files.exists(nestedFile.getParent()), "父目录不应该存在");
        
        String content = "测试内容\n";
        AppendOnlyFileChannelWriter.appendToFile(nestedFile, content);
        
        assertTrue(Files.exists(nestedFile), "文件应该被创建");
        assertTrue(Files.exists(nestedFile.getParent()), "父目录应该被自动创建");
        String result = Files.readString(nestedFile, StandardCharsets.UTF_8);
        assertEquals(content, result, "内容应该正确写入");
    }
    
    @Test
    @DisplayName("测试4: 目录穿越防护")
    void testPathTraversalProtection() {
        Path maliciousPath = Paths.get("../../etc/passwd");
        
        assertThrows(IllegalArgumentException.class, 
                () -> AppendOnlyFileChannelWriter.appendToFile(maliciousPath, "恶意内容"),
                "应该拒绝包含 .. 的路径");
    }
    
    @Test
    @DisplayName("测试5: 空路径拒绝")
    void testNullPathRejection() {
        assertThrows(IllegalArgumentException.class,
                () -> AppendOnlyFileChannelWriter.appendToFile(null, "内容"),
                "应该拒绝空路径");
    }
    
    @Test
    @DisplayName("测试6: 并发追加 (多个线程)")
    void testConcurrentAppend() throws IOException, InterruptedException {
        String content1 = "线程1\n";
        String content2 = "线程2\n";
        String content3 = "线程3\n";
        
        // 启动三个并发线程
        Thread t1 = new Thread(() -> {
            try {
                AppendOnlyFileChannelWriter.appendToFile(testFile, content1);
            } catch (IOException e) {
                fail("线程1 写入失败: " + e.getMessage());
            }
        });
        
        Thread t2 = new Thread(() -> {
            try {
                AppendOnlyFileChannelWriter.appendToFile(testFile, content2);
            } catch (IOException e) {
                fail("线程2 写入失败: " + e.getMessage());
            }
        });
        
        Thread t3 = new Thread(() -> {
            try {
                AppendOnlyFileChannelWriter.appendToFile(testFile, content3);
            } catch (IOException e) {
                fail("线程3 写入失败: " + e.getMessage());
            }
        });
        
        t1.start();
        t2.start();
        t3.start();
        
        t1.join();
        t2.join();
        t3.join();
        
        String result = Files.readString(testFile, StandardCharsets.UTF_8);
        
        // 验证所有内容都被写入 (顺序可能不同，但内容应该都存在)
        assertTrue(result.contains("线程1"), "应该包含线程1内容");
        assertTrue(result.contains("线程2"), "应该包含线程2内容");
        assertTrue(result.contains("线程3"), "应该包含线程3内容");
        
        // 验证没有重复或丢失
        int expectedLength = content1.length() + content2.length() + content3.length();
        assertEquals(expectedLength, result.length(), "长度应该正确");
    }
}
