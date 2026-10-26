package com.nexusmind.api.dto;

/**
 * CodeAgent 项目符号分析请求（公共契约 DTO）。
 *
 * <p>由 nexusmind-supervisor 组装后经 @DubboReference 下发到 CodeAgentServiceImpl。
 * 对齐 {@code TutorReflectionPrompt} 等契约 DTO 的定位：纯数据传输，无业务逻辑。
 */
public record CodeAnalysisRequest(
        /** 任务唯一标识，用于 ABORT 令牌与进度帧关联 */
        String taskId,
        /** 相对于代码沙箱 projects/ 目录的子路径，如 "nexusmind-agent-workers" */
        String projectSubPath,
        /** 目标语言标记：JAVA / PYTHON / TYPESCRIPT（切片1 仅实现 JAVA） */
        String language
) {
}
