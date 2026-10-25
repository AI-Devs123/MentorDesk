package com.nexusmind.api.dto;

import java.io.Serializable;

/**
 * 日记审查响应 DTO - 标准化 API 响应格式
 *
 * 字段说明:
 * - success: 处理是否成功
 * - data: 业务数据载体 (包含 entry, physicalPath, processingStats)，错误路径下为 null
 * - message: 可选的业务消息
 */
public record JournalReviewResponse(
        boolean success,
        DataPayload data,
        String message
) implements Serializable {

    private static final long serialVersionUID = 1L;

    // 注意：不要在紧凑构造器里强制 data 非空。
    // 错误路径（校验失败/处理异常）下 controller 会传 data=null，
    // requireNonNull 会让"构造错误响应体"这个动作本身抛 NPE，
    // 把真实错误掩盖成莫名的 500。
}
