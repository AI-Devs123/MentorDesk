package com.nexusmind.api.dto;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * WebCurator 入库进度帧（公共契约 DTO）。
 *
 * <p>对齐 {@link CodeAnalysisProgress} 的定位：作为「Supervisor 编排层 → 网关 SSE」的传输单元。
 * 关键差异是代码分析流天然多帧（逐文件 INDEXED），而网页入库的 {@code IWebCuratorService}
 * 是<b>单次 unary 调用</b>——编排层拿不到 RPC 内部的真实中间态。
 *
 * <p>因此本帧序列的语义是「<b>确定性的阶段回执</b>」而非「细粒度实时流」：
 * 调用前下发 SECURITY_CHECK / FETCHING（声明即将执行的阶段），调用成功后依据<b>真实返回值</b>
 * 下发 EXTRACTED / STAGED / COMPLETED（数字全部来自结果对象，不做伪造）。
 * 待 Week 7 把 curator 改造为 Dubbo 服务端流后，可无缝替换为真实增量帧而前端契约不变。
 *
 * <p><b>stage 取值：</b>
 * <ul>
 *   <li>{@code SECURITY_CHECK} — 目标 URL 准入声明（真正拦截在 agent 内 fail-closed）</li>
 *   <li>{@code FETCHING}       — 双通道抓取 + 正文提纯</li>
 *   <li>{@code EXTRACTED}      — LLM 元数据萃取 + 语义分块（带真实 chunkCount / 降噪比）</li>
 *   <li>{@code STAGED}         — 沙箱落盘完成（带相对路径与真实字节数）</li>
 *   <li>{@code COMPLETED}      — 全流程完成（payloadJson = WebCurateResult JSON）</li>
 *   <li>{@code FAILED}         — 失败（content 可能含 SecurityGuard / SSRF 关键词）</li>
 * </ul>
 */
public record WebCurateProgress(
        /** 任务标识，与请求 requestId 同源 */
        String taskId,
        /** 墙钟时间戳 {@code HH:mm:ss}，供前端控制台按原设计稿样式渲染 {@code [10:14:02]} */
        String timestamp,
        /** SECURITY_CHECK / FETCHING / EXTRACTED / STAGED / COMPLETED / FAILED */
        String stage,
        /** 语义归属 Agent 名：SecurityGuard / WebCuratorAgent / Supervisor */
        String agentName,
        /** 阶段文本描述 */
        String message,
        /** 仅 STAGED / COMPLETED 帧携带结构化 JSON；其余帧为 "{}" */
        String payloadJson
) {
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public WebCurateProgress {
        taskId = taskId == null ? "" : taskId;
        // 未显式指定时取当前墙钟时间，保证前端时间戳永不为空
        timestamp = (timestamp == null || timestamp.isBlank())
                ? LocalTime.now().format(HHMMSS) : timestamp;
        stage = stage == null ? "" : stage;
        agentName = agentName == null ? "Supervisor" : agentName;
        message = message == null ? "" : message;
        payloadJson = payloadJson == null ? "{}" : payloadJson;
    }

    /** 无负载的阶段帧（payloadJson 固定为 "{}"） */
    public static WebCurateProgress progress(String taskId, String stage, String agentName, String message) {
        return new WebCurateProgress(taskId, null, stage, agentName, message, "{}");
    }

    /** 带结构化负载的帧（STAGED / COMPLETED） */
    public static WebCurateProgress withPayload(String taskId, String stage, String agentName,
                                                String message, String payloadJson) {
        return new WebCurateProgress(taskId, null, stage, agentName, message, payloadJson);
    }
}
