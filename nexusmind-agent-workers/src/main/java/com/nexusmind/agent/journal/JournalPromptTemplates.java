package com.nexusmind.agent.journal;

/**
 * 日记分析 Few-Shot 提示词模板库
 * 固化 LLM 提示词，确保一致的 JSON 输出格式
 */
public class JournalPromptTemplates {
    
    /**
     * 系统提示词 - 定义 LLM 角色和输出格式
     */
    public static String getSystemPrompt() {
        return """
                你是一个日记分析专家。根据用户提供的日记原文，你需要提取出以下三个部分，并以 JSON 格式返回：
                
                # 输出要求
                
                ## 1. breakthroughs (今日突破)
                - 列出今天的主要技术进展或验证成果
                - 必须至少有 1 项
                - 每项 10-100 字符
                - 例: "Dubbo 3 Triple 协议与 SSE 转发打通"
                
                ## 2. blockers (阻碍分析)
                - 列出今天遇到的技术阻碍
                - 每个阻碍必须包含三个字段:
                  - symptom: 问题表现症状 (10-200 字符) - 可见的问题表现
                  - rootCause: 根本原因分析 (10-200 字符) - 为什么会出现这个问题
                  - technicalDomain: 涉及的技术域 (e.g., "WebFlux", "Concurrency", "Dubbo")
                - 可以没有阻碍（空数组），但如果有，每项都要完整
                - 例: {"symptom": "前端关闭后后端线程未释放", "rootCause": "WebFlux doOnCancel 未挂到 Dubbo", "technicalDomain": "WebFlux"}
                
                ## 3. mvpActions (次日MVP行动)
                - 列出明天要做的MVP级别高优先级动作
                - 必须至少有 1 项
                - 每项 10-100 字符
                - 优先级高且可立即执行
                - 例: "打通取消信号传播机制"
                
                # 输出格式
                
                返回 **有效的纯 JSON 对象**，不要包含额外的文本或代码块标记。
                
                示例格式:
                ```json
                {
                  "breakthroughs": [
                    "突破点1",
                    "突破点2"
                  ],
                  "blockers": [
                    {
                      "symptom": "问题症状",
                      "rootCause": "根本原因",
                      "technicalDomain": "技术域"
                    }
                  ],
                  "mvpActions": [
                    "行动1",
                    "行动2"
                  ]
                }
                ```
                
                # 关键约束
                - 输出必须是有效的 JSON (不要 Markdown 包裹)
                - 所有字符串必须用双引号 (")
                - 禁止在 JSON 中添加注释
                - 禁止输出 ```json 标记
                """;
    }
    
    /**
     * 用户提示词 - 包含日记内容
     */
    public static String getUserPrompt(String rawContent) {
        return "请分析以下日记内容，并按照 JSON 格式提取三要素:\n\n" + rawContent;
    }
    
    /**
     * Mock 数据模板 - 当 LLM 不可用时使用
     * 确保格式完全合规
     */
    public static String getMockJsonTemplate() {
        return """
                {
                  "breakthroughs": [
                    "日常工作推进中"
                  ],
                  "blockers": [],
                  "mvpActions": [
                    "继续当前工作"
                  ]
                }
                """;
    }
}
