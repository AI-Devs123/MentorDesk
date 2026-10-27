package com.nexusmind.api.dto;

/**
 * WebCurator 网页策展入库请求（公共契约 DTO）。
 *
 * <p>由 nexusmind-supervisor 组装后经 {@code @DubboReference} 下发到 WebCuratorAgentImpl，
 * 对齐 {@link CodeAnalysisRequest} 的定位：纯数据传输，无业务逻辑。
 */
public record WebCurateRequest(
        /** 请求唯一标识，用于进度关联与幂等去重 */
        String requestId,
        /** 目标网页 URL（经 SSRF 防护校验，仅允许公网地址） */
        String url,
        /** 是否强制走无头浏览器渲染通道（应对 SPA / 反爬登录墙） */
        boolean forceRenderJs
) {
}
