package com.nexusmind.agent.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 沙箱路径校验器 - 防止目录穿越攻击
 * 确保所有文件操作都在沙箱目录内
 */
public class SandboxPathValidator {
    
    private static final String SANDBOX_BASE = "data/nexusmind/sandbox";
    private static final String JOURNALS_DIR = "journals";
    
    /**
     * 校验并解析相对路径，防止目录穿越
     * 
     * @param relativePath 相对路径 (e.g., "2026/10/25/entry-2026-10-25-001.json")
     * @return 规范化的安全路径
     * @throws IllegalArgumentException 如果路径试图穿越沙箱
     */
    public static Path validateAndResolve(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        
        // 规范化路径 (消除 . 和 ..)
        Path safePath = Paths.get(relativePath).normalize();
        
        // 检查是否包含 ".." (目录穿越尝试)
        if (safePath.toString().contains("..")) {
            throw new IllegalArgumentException("拒绝包含 .. 的路径: " + relativePath);
        }
        
        // 构建完整路径
        Path sandboxJournals = Paths.get(SANDBOX_BASE, JOURNALS_DIR).normalize();
        Path resolvedPath = sandboxJournals.resolve(safePath).normalize();
        
        // 二次检查: 确保最终路径在沙箱内
        if (!resolvedPath.startsWith(sandboxJournals)) {
            throw new IllegalArgumentException("路径穿越检查失败: " + relativePath);
        }
        
        return resolvedPath;
    }
    
    /**
     * 验证并自愈目录结构
     * 如果目录不存在则自动创建
     * 
     * @param filePath 文件路径
     * @throws IOException 创建目录失败
     */
    public static void ensureDirectory(Path filePath) throws IOException {
        Path parentDir = filePath.getParent();
        
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
    }
    
    /**
     * 获取沙箱基础目录
     */
    public static String getSandboxBase() {
        return SANDBOX_BASE;
    }
}
