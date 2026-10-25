package com.nexusmind.api.dto;

import java.io.Serializable;

/**
 * 日记审查请求DTO
 */
public record JournalReviewRequest(
        String rawContent
) implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // 字段约束
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 10000;
}
