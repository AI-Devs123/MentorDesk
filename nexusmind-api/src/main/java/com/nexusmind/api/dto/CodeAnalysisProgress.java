package com.nexusmind.api.dto;

/**
 * CodeAgent 分析进度帧（公共契约 DTO）。
 *
 * <p>作为服务端流式返回的单元：Supervisor / 网关将逐帧转译为 SSE 推流。
 * 关键约束（带宽治理）：仅 {@code INDEXED} 帧在 {@code payloadJson} 中携带
 * 当前单文件的符号增量，{@code SCANNING/PARSING} 帧只带状态与计数，杜绝 O(N²) 带宽爆炸。
 *
 * <p>stage 取值：SCANNING → PARSING → INDEXED → (COMPLETED | FAILED)。
 */
public record CodeAnalysisProgress(
        String taskId,
        String timestamp,
        /** SCANNING / PARSING / INDEXED / FAILED / COMPLETED */
        String stage,
        /** 当前处理文件（相对沙箱路径）；统计帧可为空串 */
        String currentFile,
        int processedFiles,
        int totalFiles,
        /** 仅 INDEXED 帧携带当前文件符号 JSON 数组；其余帧为 "{}" */
        String payloadJson
) {
}
