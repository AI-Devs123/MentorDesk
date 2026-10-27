package com.nexusmind.api.dto;

/**
 * WebCurator 入库结果（公共契约 DTO）。
 *
 * <p>supervisor 侧据此获知落盘位置、全局 docId 与分块规模，供 Week 7 向量化消费；
 * 同时携带 {@code rawHtmlCharCount} / {@code fileSizeBytes}，使编排层能在 SSE 推流中
 * 给出<b>真实的</b>降噪比与资产体积，而非前端伪造的占位数字。
 */
public record WebCurateResult(
        /** 回显请求标识 */
        String requestId,
        /** 文档全局标识（URL SHA256 前 12 位，幂等） */
        String docId,
        /**
         * 相对沙箱根目录的落盘路径，如 {@code inbox/web/20261027221310_xxx.md}。
         * 由 PathSandboxValidator 解析为绝对路径，调用方不应传入绝对路径或 {@code ..}。
         */
        String sandboxSavedPath,
        /** 萃取出的结构化元数据 */
        WebDocMetadata metadata,
        /** 语义分块数量 */
        int chunkCount,
        /** 纯净正文字符数（去标记前的原始 markdown 长度） */
        int charCount,
        /** 规范化后的规范链接（canonical），可能与原始 url 不同 */
        String canonicalUrl,
        /** 本次是否实际走了无头浏览器渲染通道 */
        boolean renderJsUsed,
        /**
         * 抓取到的原始 HTML 字符数。与 {@code charCount} 联立即可算出真实降噪比；
         * 抓取通道异常（如空响应）时为 0，此时 {@link #noiseReductionPercent()} 返回 0。
         */
        int rawHtmlCharCount,
        /** 落盘 Markdown 资产的真实字节数（由沙箱服务回读，非字符数） */
        long fileSizeBytes
) {
    /**
     * 降噪比（百分比，0~100）：被剔除的 HTML 噪声占原始 HTML 的比例。
     *
     * <p>注意是「字符数」口径的近似值：原始 HTML 含大量标签与脚本，字符数与字节数不等价，
     * 但对前端展示「降噪 68%」这类进度文案已足够准确。
     */
    public int noiseReductionPercent() {
        if (rawHtmlCharCount <= 0 || charCount <= 0) {
            return 0;
        }
        long reduced = (long) rawHtmlCharCount - charCount;
        if (reduced <= 0) {
            return 0;
        }
        return (int) Math.min(100L, (reduced * 100L) / rawHtmlCharCount);
    }
}
