package com.nexusmind.security.destructive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 破坏性文件操作回收站管理服务
 * 提供软删除备份能力，确保误操作可恢复
 */
@Service
public class TrashBackupService {
    private static final Logger log = LoggerFactory.getLogger(TrashBackupService.class);

    @Value("${nexusmind.sandbox.trash-dir:.trash}")
    private String trashDirName;

    /**
     * 将目标文件备份至沙箱回收站
     * 使用时间戳隔离不同备份，避免冲突
     * 
     * @param targetFilePath 待备份文件的绝对路径
     * @return 备份后的物理绝对路径；若源文件不存在则返回 null
     * @throws IOException 文件操作失败
     */
    public String backupToTrash(String targetFilePath) throws IOException {
        Path targetPath = Paths.get(targetFilePath).toAbsolutePath().normalize();
        
        if (!Files.exists(targetPath)) {
            log.warn("⚠️ [TrashBackup] 源文件不存在，无法备份: {}", targetFilePath);
            return null;
        }

        // 确保 .trash 目录存在
        Path trashRoot = Paths.get(trashDirName).toAbsolutePath().normalize();
        if (!Files.exists(trashRoot)) {
            Files.createDirectories(trashRoot);
            log.info("📁 [TrashBackup] 回收站目录已创建: {}", trashRoot);
        }

        // 生成带时间戳的备份文件名
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        String backupFileName = timestamp + "_" + targetPath.getFileName().toString();
        Path backupTargetPath = trashRoot.resolve(backupFileName);

        try {
            // 使用物理 COPY 备份，保留原文件供后续标准删除流程使用
            Files.copy(targetPath, backupTargetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("✅ [TrashBackup] 破坏性文件已成功归档至回收站: {} → {}", 
                    targetFilePath, backupTargetPath);
            return backupTargetPath.toString();
        } catch (IOException e) {
            log.error("❌ [TrashBackup] 备份操作失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 从回收站恢复文件
     * 支持用户误操作后的文件恢复
     * 
     * @param backupFilePath 回收站中的备份文件路径
     * @param restoreTargetPath 恢复目标路径
     * @return 恢复是否成功
     * @throws IOException 文件操作失败
     */
    public boolean restoreFromTrash(String backupFilePath, String restoreTargetPath) throws IOException {
        Path backupPath = Paths.get(backupFilePath).toAbsolutePath().normalize();
        Path targetPath = Paths.get(restoreTargetPath).toAbsolutePath().normalize();

        if (!Files.exists(backupPath)) {
            log.warn("⚠️ [TrashBackup] 备份文件不存在: {}", backupFilePath);
            return false;
        }

        try {
            Files.copy(backupPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("✅ [TrashBackup] 文件已从回收站恢复: {} → {}", 
                    backupFilePath, restoreTargetPath);
            return true;
        } catch (IOException e) {
            log.error("❌ [TrashBackup] 恢复操作失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 清空回收站（谨慎使用）
     * 仅在确认无需恢复时调用
     */
    public void emptyTrash() throws IOException {
        Path trashRoot = Paths.get(trashDirName).toAbsolutePath().normalize();
        
        if (!Files.exists(trashRoot)) {
            log.warn("⚠️ [TrashBackup] 回收站不存在: {}", trashRoot);
            return;
        }

        try {
            Files.walk(trashRoot)
                    .sorted((p1, p2) -> p2.compareTo(p1))  // 逆序遍历
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                            log.debug("🗑️ [TrashBackup] 已删除: {}", path);
                        } catch (IOException e) {
                            log.warn("⚠️ [TrashBackup] 删除失败: {}", path);
                        }
                    });
            log.info("✅ [TrashBackup] 回收站已清空");
        } catch (IOException e) {
            log.error("❌ [TrashBackup] 清空回收站失败: {}", e.getMessage(), e);
            throw e;
        }
    }
}
