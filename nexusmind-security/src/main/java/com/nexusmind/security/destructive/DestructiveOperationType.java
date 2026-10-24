package com.nexusmind.security.destructive;

/**
 * 破坏性操作类型枚举
 * 定义需要人工二阶段确认的高危操作类型
 */
public enum DestructiveOperationType {
    /**
     * 文件删除操作
     */
    FILE_DELETE("文件删除"),
    
    /**
     * 文件覆盖写入
     */
    FILE_OVERWRITE("文件覆盖"),
    
    /**
     * Git 强制重置（Hard Reset/Force Push）
     */
    GIT_FORCE_RESET("Git强制重置"),
    
    /**
     * 系统命令执行（rm -rf, mkfs 等）
     */
    SYSTEM_COMMAND("系统命令");

    private final String description;

    DestructiveOperationType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
