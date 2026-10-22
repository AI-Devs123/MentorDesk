package com.nexusmind.security.sandbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱路径校验引擎
 * 强制所有文件操作必须通过此组件进行路径解析
 * 防止路径遍历、符号链接逃逸、字符截断等攻击
 */
@Slf4j
@Component
public class PathSandboxValidator {
    
    private final Path sandboxRoot;
    private final ConcurrentHashMap<String, Path> pathCache;
    private static final int MAX_PATH_LENGTH = 4096;
    private static final String[] BLOCKED_SEQUENCES = {"\0", "..", "~", "..\\", "%2e%2e", "%252e%252e"};
    
    public PathSandboxValidator(
            @Value("${nexusmind.security.sandbox-root:/data/nexusmind/sandbox}") String rootDir) {
        
        Objects.requireNonNull(rootDir, "Sandbox root directory cannot be null");
        
        this.sandboxRoot = Paths.get(rootDir)
                .toAbsolutePath()
                .normalize();
        this.pathCache = new ConcurrentHashMap<>();
        
        // 初始化沙箱目录
        try {
            if (!Files.exists(this.sandboxRoot)) {
                Files.createDirectories(this.sandboxRoot);
                log.info("Created sandbox root directory: {}", this.sandboxRoot);
            } else if (!Files.isDirectory(this.sandboxRoot)) {
                throw new IllegalArgumentException(
                        "Sandbox root is not a directory: " + rootDir);
            }
            
            log.info("PathSandboxValidator initialized with root: {}", this.sandboxRoot);
        } catch (IOException e) {
            throw new SecurityException(
                    "Failed to initialize sandbox root directory: " + rootDir, e);
        }
    }
    
    /**
     * 核心校验方法：将用户/Agent传入的相对路径解析为受控的安全绝对路径
     * 
     * @param rawPath 原始路径（通常是相对路径）
     * @return 安全的规范化路径
     * @throws SecurityException 如果路径包含恶意内容或尝试越界
     */
    public Path resolveSecurePath(String rawPath) throws SecurityException {
        
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new SecurityException(
                    "SecurityGuard 拦截: 访问路径不能为空");
        }
        
        String normalizedInput = rawPath.trim();
        
        // 长度检查
        if (normalizedInput.length() > MAX_PATH_LENGTH) {
            throw new SecurityException(
                    "SecurityGuard 拦截: 路径长度超过限制 (max: " + MAX_PATH_LENGTH + ")");
        }
        
        // 检查缓存
        if (pathCache.containsKey(normalizedInput)) {
            return pathCache.get(normalizedInput);
        }
        
        // 1. 阶段一：输入层硬拦截
        validateInputSequence(normalizedInput);
        
        // 2. 阶段二：路径规范化和边界检查
        Path resolvedPath = resolvePath(normalizedInput);
        
        // 3. 阶段三：符号链接检测（防御 Symlink 逃逸）
        validateSymlink(resolvedPath);
        
        // 4. 阶段四：最终边界检查
        validateBoundary(resolvedPath);
        
        // 缓存结果
        pathCache.put(normalizedInput, resolvedPath);
        
        log.debug("Path validated successfully: {} -> {}", 
                rawPath, resolvedPath);
        
        return resolvedPath;
    }
    
    /**
     * 输入层硬拦截：检测常见的攻击序列
     */
    private void validateInputSequence(String input) throws SecurityException {
        
        // 检查被禁用的字符序列
        for (String blocked : BLOCKED_SEQUENCES) {
            // 精确匹配和大小写变体
            if (input.contains(blocked)) {
                throw new SecurityException(
                        "SecurityGuard 拦截: 检测到被禁止的字符序列 -> " + blocked);
            }
        }
        
        // 检查特殊字符（null byte、control characters）
        for (char c : input.toCharArray()) {
            if (c < 32 && c != '\t') {  // 除了 tab，其他控制字符禁止
                throw new SecurityException(
                        "SecurityGuard 拦截: 检测到非法控制字符 -> \\x" + 
                        String.format("%02x", (int) c));
            }
        }
        
        // 检查过度编码的路径遍历 (URL 编码、Unicode 编码等)
        if (input.matches(".*(%2[eE]|%252[eE]|%u002[eE]).*")) {
            throw new SecurityException(
                    "SecurityGuard 拦截: 检测到编码的路径遍历序列");
        }
    }
    
    /**
     * 路径规范化和边界检查
     */
    private Path resolvePath(String rawPath) throws SecurityException {
        try {
            Path basePath;
            
            // 判断是否为绝对路径
            if (Paths.get(rawPath).isAbsolute()) {
                // 绝对路径直接使用（后续边界检查会拦截不合法的）
                basePath = Paths.get(rawPath);
            } else {
                // 相对路径相对于沙箱根目录解析
                basePath = sandboxRoot.resolve(rawPath);
            }
            
            // 规范化：消除 . 和 ..
            Path normalized = basePath.toAbsolutePath().normalize();
            
            // 检查规范化后是否仍试图逃逸
            if (!normalized.startsWith(sandboxRoot)) {
                throw new SecurityException(
                        "SecurityGuard 拦截: 检测到非法目录穿越企图 -> " + rawPath + 
                        " (resolved to: " + normalized + ")");
            }
            
            return normalized;
            
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException(
                    "SecurityGuard 拦截: 路径解析失败 -> " + rawPath, e);
        }
    }
    
    /**
     * 符号链接检测：防御 Symlink 逃逸攻击
     */
    private void validateSymlink(Path resolvedPath) throws SecurityException {
        try {
            // 检查每个路径组件是否为符号链接
            Path current = sandboxRoot;
            
            for (Path part : resolvedPath.relativize(sandboxRoot).equals(Paths.get("")) 
                    ? resolvedPath.subpath(sandboxRoot.getNameCount(), resolvedPath.getNameCount())
                    : resolvedPath) {
                
                current = current.resolve(part);
                
                // 如果该路径存在且是符号链接
                if (Files.isSymbolicLink(current)) {
                    Path linkTarget = Files.readSymbolicLink(current);
                    Path realTarget = current.toRealPath();
                    
                    // 检查符号链接目标是否在沙箱内
                    if (!realTarget.startsWith(sandboxRoot.toRealPath())) {
                        throw new SecurityException(
                                "SecurityGuard 拦截: 检测到符号链接指向沙箱外部 -> " + 
                                current + " -> " + realTarget);
                    }
                    
                    log.warn("Symlink detected but contained: {} -> {}", 
                            current, realTarget);
                }
            }
            
            // 最终检查：如果路径存在，确保真实路径在沙箱内
            if (Files.exists(resolvedPath)) {
                Path realPath = resolvedPath.toRealPath();
                if (!realPath.startsWith(sandboxRoot.toRealPath())) {
                    throw new SecurityException(
                            "SecurityGuard 拦截: 检测到真实路径在沙箱外部 -> " + 
                            resolvedPath + " (real: " + realPath + ")");
                }
            }
            
        } catch (SecurityException e) {
            throw e;
        } catch (IOException e) {
            throw new SecurityException(
                    "SecurityGuard 拦截: 无法解析路径物理地址", e);
        }
    }
    
    /**
     * 最终边界检查
     */
    private void validateBoundary(Path resolvedPath) throws SecurityException {
        
        try {
            Path realSandbox = sandboxRoot.toRealPath();
            Path realPath = Files.exists(resolvedPath) 
                    ? resolvedPath.toRealPath() 
                    : resolvedPath.toAbsolutePath();
            
            if (!realPath.startsWith(realSandbox)) {
                throw new SecurityException(
                        "SecurityGuard 拦截: 路径超出沙箱边界 -> " + realPath);
            }
            
        } catch (SecurityException e) {
            throw e;
        } catch (IOException e) {
            throw new SecurityException(
                    "SecurityGuard 拦截: 边界检查失败", e);
        }
    }
    
    /**
     * 为遗留代码提供 File 对象（已验证）
     * 仅用于必须使用 File API 的场景
     */
    public File getValidatedFile(String relativePath) throws SecurityException {
        Path safePath = resolveSecurePath(relativePath);
        File file = safePath.toFile();
        
        log.debug("Returning validated File object: {}", file.getAbsolutePath());
        return file;
    }
    
    /**
     * 获取沙箱根目录路径
     */
    public Path getSandboxRoot() {
        return sandboxRoot;
    }
    
    /**
     * 获取相对于沙箱的相对路径
     */
    public Path getRelativePath(Path absolutePath) throws SecurityException {
        try {
            if (!absolutePath.startsWith(sandboxRoot)) {
                throw new SecurityException(
                        "SecurityGuard 拦截: 路径不在沙箱内 -> " + absolutePath);
            }
            return sandboxRoot.relativize(absolutePath);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException(
                    "SecurityGuard 拦截: 无法计算相对路径", e);
        }
    }
    
    /**
     * 清空缓存（主要用于测试）
     */
    public void clearCache() {
        pathCache.clear();
    }
    
    /**
     * 获取缓存大小（用于监控）
     */
    public int getCacheSize() {
        return pathCache.size();
    }
}
