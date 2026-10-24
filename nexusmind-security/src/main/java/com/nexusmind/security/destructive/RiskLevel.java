package com.nexusmind.security.destructive;

/**
 * 操作风险等级枚举
 */
public enum RiskLevel {
    /**
     * 中等风险：可恢复的操作（如临时文件覆盖）
     */
    MEDIUM(1),
    
    /**
     * 高风险：难以恢复的操作（如删除重要文件）
     */
    HIGH(2),
    
    /**
     * 极高风险：不可逆操作（如生产环境强制推送、系统级命令）
     */
    CRITICAL(3);

    private final int level;

    RiskLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public boolean isHigherThan(RiskLevel other) {
        return this.level > other.level;
    }
}
