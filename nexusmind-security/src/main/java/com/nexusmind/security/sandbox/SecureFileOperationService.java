package com.nexusmind.security.sandbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 安全文件操作服务
 * 
 * 通过此服务执行所有文件 I/O 操作，已内置 PathSandboxValidator 校验
 * 业务代码应该使用这个服务而不是直接操作 File/Files
 */
@Slf4j
@Service
public class SecureFileOperationService {
    
    private final PathSandboxValidator pathValidator;
    
    public SecureFileOperationService(PathSandboxValidator pathValidator) {
        this.pathValidator = pathValidator;
    }
    
    // ============ 读操作 ============
    
    /**
     * 读取文件全部内容
     */
    public String readFileAsString(String relativePath) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        if (!Files.exists(safePath)) {
            throw new IOException("File not found: " + relativePath);
        }
        
        if (!Files.isReadable(safePath)) {
            throw new SecurityException("File is not readable: " + relativePath);
        }
        
        log.debug("Reading file: {}", safePath);
        return Files.readString(safePath, StandardCharsets.UTF_8);
    }
    
    /**
     * 读取文件按行
     */
    public List<String> readFileAsLines(String relativePath) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        if (!Files.exists(safePath)) {
            throw new IOException("File not found: " + relativePath);
        }
        
        log.debug("Reading file as lines: {}", safePath);
        return Files.readAllLines(safePath, StandardCharsets.UTF_8);
    }
    
    /**
     * 读取文件字节
     */
    public byte[] readFileAsBytes(String relativePath) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        if (!Files.exists(safePath)) {
            throw new IOException("File not found: " + relativePath);
        }
        
        log.debug("Reading file as bytes: {}", safePath);
        return Files.readAllBytes(safePath);
    }
    
    // ============ 写操作 ============
    
    /**
     * 写入文件内容（覆盖）
     */
    public void writeFile(String relativePath, String content) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        // 确保父目录存在
        Path parent = safePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        
        if (Files.exists(safePath) && !Files.isWritable(safePath)) {
            throw new SecurityException("File is not writable: " + relativePath);
        }
        
        log.info("Writing file: {}", safePath);
        Files.writeString(safePath, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    /**
     * 追加内容到文件
     */
    public void appendToFile(String relativePath, String content) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        // 确保父目录存在
        Path parent = safePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        
        log.info("Appending to file: {}", safePath);
        Files.writeString(safePath, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
    
    /**
     * 写入字节到文件
     */
    public void writeBytes(String relativePath, byte[] data) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        Path parent = safePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        
        log.info("Writing bytes to file: {}", safePath);
        Files.write(safePath, data,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    // ============ 文件系统操作 ============
    
    /**
     * 检查文件是否存在
     */
    public boolean fileExists(String relativePath) throws SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        return Files.exists(safePath);
    }
    
    /**
     * 检查是否为目录
     */
    public boolean isDirectory(String relativePath) throws SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        return Files.isDirectory(safePath);
    }
    
    /**
     * 创建目录
     */
    public void createDirectory(String relativePath) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        if (!Files.exists(safePath)) {
            log.info("Creating directory: {}", safePath);
            Files.createDirectories(safePath);
        }
    }
    
    /**
     * 删除文件
     */
    public void deleteFile(String relativePath) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        if (Files.exists(safePath)) {
            log.info("Deleting file: {}", safePath);
            Files.delete(safePath);
        }
    }
    
    /**
     * 获取文件大小
     */
    public long getFileSize(String relativePath) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        if (!Files.exists(safePath)) {
            throw new IOException("File not found: " + relativePath);
        }
        
        return Files.size(safePath);
    }
    
    /**
     * 列出目录内容
     */
    public List<String> listDirectory(String relativePath) throws IOException, SecurityException {
        Path safePath = pathValidator.resolveSecurePath(relativePath);
        
        if (!Files.isDirectory(safePath)) {
            throw new IOException("Not a directory: " + relativePath);
        }
        
        log.debug("Listing directory: {}", safePath);
        
        try (var stream = Files.list(safePath)) {
            return stream
                    .map(p -> safePath.relativize(p).toString())
                    .toList();
        }
    }
    
    /**
     * 获取安全验证后的 File 对象（仅用于需要 File 类型参数的场景）
     */
    public File getValidatedFile(String relativePath) throws SecurityException {
        return pathValidator.getValidatedFile(relativePath);
    }
    
    /**
     * 获取安全验证后的 Path 对象
     */
    public Path getValidatedPath(String relativePath) throws SecurityException {
        return pathValidator.resolveSecurePath(relativePath);
    }
}
