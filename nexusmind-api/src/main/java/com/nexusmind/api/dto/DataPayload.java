package com.nexusmind.api.dto;

import java.io.Serializable;
import java.util.Objects;

/**
 * 日记审查响应的数据载体
 * 
 * 包含:
 * - entry: 完整的日记条目结构
 * - physicalPath: 文件落盘相对路径 (YYYY/MM/DD/entry-{id}.json)
 * - processingStats: 处理统计信息 (耗时、计数等)
 */
public record DataPayload(
        JournalEntryDTO entry,
        String physicalPath,
        ProcessingStats processingStats
) implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public DataPayload {
        Objects.requireNonNull(entry, "entry 不能为空");
        Objects.requireNonNull(physicalPath, "physicalPath 不能为空");
        Objects.requireNonNull(processingStats, "processingStats 不能为空");
        
        if (physicalPath.trim().isEmpty()) {
            throw new IllegalArgumentException("physicalPath 不能为空字符串");
        }
    }
}
