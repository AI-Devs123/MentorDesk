package com.nexusmind.api.dto;

/**
 * WebCurator 入库结果（公共契约 DTO）。
 *
 * <p>supervisor 侧据此获知落盘位置、全局 docId 与分块规模，供 Week 7 向量化消费。
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
        boolean renderJsUsed
) {
}
