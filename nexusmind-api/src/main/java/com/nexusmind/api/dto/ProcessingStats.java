package com.nexusmind.api.dto;

import java.io.Serializable;

/**
 * 日记处理统计信息
 * 
 * 用于前端展示处理进度和结果统计
 */
public record ProcessingStats(
        int breakthroughCount,    // 突破点数量
        int blockerCount,         // 阻碍数量
        int actionCount,          // 行动项数量
        long processingTimeMs     // 总处理耗时 (毫秒)
) implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public ProcessingStats {
        if (breakthroughCount < 0) {
            throw new IllegalArgumentException("breakthroughCount 不能为负数");
        }
        if (blockerCount < 0) {
            throw new IllegalArgumentException("blockerCount 不能为负数");
        }
        if (actionCount < 0) {
            throw new IllegalArgumentException("actionCount 不能为负数");
        }
        if (processingTimeMs < 0) {
            throw new IllegalArgumentException("processingTimeMs 不能为负数");
        }
    }
}
