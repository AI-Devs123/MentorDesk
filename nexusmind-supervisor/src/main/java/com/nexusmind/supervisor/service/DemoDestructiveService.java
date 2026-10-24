package com.nexusmind.supervisor.service;

import com.nexusmind.security.destructive.DestructiveOperation;
import com.nexusmind.security.destructive.DestructiveOperationType;
import com.nexusmind.security.destructive.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 破坏性操作演示服务
 * 用于测试 AOP 拦截和二阶段确认机制
 * 
 * 🔥 关键：该 Bean 必须被 Spring CGLIB 代理化，AOP 切面才能生效
 * 确保此类被扫描到并注册到 Spring 容器，且启用了 @EnableAspectJAutoProxy
 */
@Service
public class DemoDestructiveService {
    private static final Logger log = LoggerFactory.getLogger(DemoDestructiveService.class);
    
    /**
     * 模拟文件删除操作（高风险）
     * 
     * 当调用此方法时，DestructiveActionAdvisor 的 @Around 切面应该在执行前拦截
     * 并抛出 ExecutionSuspendedException，使方法体无法执行
     */
    @DestructiveOperation(
        type = DestructiveOperationType.FILE_DELETE,
        riskLevel = RiskLevel.HIGH,
        description = "删除旧文件 old_file.py",
        requiresDiff = true
    )
    public void deleteOldFile(String filePath) {
        log.info("🗑️ 执行文件删除: filePath={}", filePath);
        // 实际删除逻辑
        // 如果代理生效，这里绝对不会被执行到（会被 AOP 切面拦截）
    }
    
    /**
     * 模拟文件覆盖操作（高风险）
     */
    @DestructiveOperation(
        type = DestructiveOperationType.FILE_OVERWRITE,
        riskLevel = RiskLevel.HIGH,
        description = "覆盖配置文件 config.yml",
        requiresDiff = true
    )
    public void overwriteConfig(String filePath, String newContent) {
        log.info("📝 执行文件覆盖: filePath={}, contentLength={}", filePath, newContent.length());
        // 实际覆盖逻辑
    }
    
    /**
     * 模拟 Git 强制重置（极高风险）
     */
    @DestructiveOperation(
        type = DestructiveOperationType.GIT_FORCE_RESET,
        riskLevel = RiskLevel.CRITICAL,
        description = "强制重置 Git 仓库",
        requiresDiff = false
    )
    public void gitForceReset(String commitHash) {
        log.info("⚠️ 执行 Git 强制重置: commitHash={}", commitHash);
        // 实际 Git 操作
    }
    
    /**
     * 模拟系统命令执行（极高风险）
     */
    @DestructiveOperation(
        type = DestructiveOperationType.SYSTEM_COMMAND,
        riskLevel = RiskLevel.CRITICAL,
        description = "执行系统级命令",
        requiresDiff = false
    )
    public void executeSystemCommand(String command) {
        log.info("💻 执行系统命令: command={}", command);
        // 实际命令执行
    }
}
