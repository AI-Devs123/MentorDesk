package com.nexusmind.agent.tutor.prompt;

import com.nexusmind.agent.tutor.diagnostic.model.ConfusionAnalysisResult;

/**
 * 苏格拉底式启发提问提示词模板库 (TutorAgent)
 *
 * 职责:
 * 1. 固化 system prompt：严格禁止直接给端到端答案，强制三段式结构化输出
 * 2. 组装 user prompt：把诊断器捕获的混淆概念对注入上下文，使辨析题直击薄弱点
 * 3. 提供 Mock 降级模板：LLM 不可用时保证全链路可联调
 *
 * 契约对齐说明 (切片1 冻结 DTO):
 * - 本模板输出的 JSON 字段与 TutorReflectionPrompt 的映射关系:
 *   targetDomain       -> targetDomain
 *   coreConflict       -> coreConflict
 *   principleAnalysis  -> contextSummary (原理剖析回填，切片3 P0-1 修复)
 *   questions[]        -> questions[] (reflectiveExperiment 折叠进最后一条 questionText)
 * - 字段名三处 (system 标题 / JSON Schema / 解析方) 必须保持统一为 questions，
 *   历史版本曾出现标题写 socraticQuestions 而 Schema 写 questions 的不一致，
 *   会导致 LLM 输出空 questions 触发 DTO 紧凑构造异常 (切片3 P1-2 修复)。
 */
public final class SocraticPromptTemplate {

    /** 单字段最大长度，防止超长日记文本撑爆 prompt 上下文 */
    private static final int MAX_FIELD_LENGTH = 2000;

    /** user prompt 总长度上限，对齐 JournalReviewRequest.MAX_LENGTH */
    private static final int MAX_PROMPT_LENGTH = 10000;

    private SocraticPromptTemplate() {}

    /**
     * 系统提示词 - 定义角色、铁律与三段式 JSON 输出契约
     */
    public static final String SYSTEM_PROMPT = """
            你是 NexusMind 的技术认知导师 (TutorAgent)。你的核心任务是通过苏格拉底式反思与追问，驱动开发者洞悉架构底层机制。

            【铁律准则】：
            1. 严格禁止直接给出端到端的修复代码或完整解决方案！
            2. 你的核心使命是引导提问，输出必须强制拆解为标准三段式：
               - [原理剖析 (principleAnalysis)]: 指出该技术在底层所要调和的核心系统矛盾/权衡点（如吞吐量 vs 响应延迟、数据一致性 vs 可用性、CPU 开销 vs 内存占用）。
               - [对比辨析设问 (questions)]: 提出 1~2 个具有高度底层辨析度的思考题，要求对比两个关联技术机制的本质边界差异（需标明 conceptA 与 conceptB）。
               - [反思实验 (reflectiveExperiment)]: 给出一条在本地或测试环境中可以通过打日志、看断点、观察指标（Metrics）直观验证该机制的极简操作指引。
            3. 必须输出合法的纯 JSON 字符串，严禁在最外层包裹 ```json 或 ``` 标记，严禁附带额外寒暄。

            【JSON Schema 格式】：
            {"targetDomain": "技术领域(如: Concurrency / RPC / Database)","coreConflict": "底层本质矛盾阐述 (1-2 句话)","principleAnalysis": "原理深度剖析正文","questions": [{"index": 1,"conceptA": "概念A","conceptB": "概念B","questionText": "对比辨析设问正文"}],"reflectiveExperiment": "极简本地验证实验指引"}
            """;

    /**
     * 组装用户提示词
     *
     * 安全说明: symptom/rootCause 来自用户自由文本，存在 prompt 注入面
     * (如文本中夹带"忽略以上指令")。此处采用 XML 标签隔离 + 长度截断双重防御，
     * 注入内容最坏效果是污染单次会话，无法越出 READ_ONLY_RAG 执行边界。
     *
     * @param symptom        故障现象
     * @param rootCause      开发者自判根因
     * @param domain         诊断器判定的主技术域 (topDomain)
     * @param confusionHints 诊断器捕获的混淆概念对摘要
     */
    public static String buildUserPrompt(String symptom, String rootCause, String domain, String confusionHints) {
        String prompt = """
                【开发者今日遭遇的技术阻碍与卡点】：
                <domain>%s</domain>
                <symptom>%s</symptom>
                <rootCause>%s</rootCause>
                <confusionHints>%s</confusionHints>

                请基于以上上下文，不要直接提供修复补丁，以苏格拉底导师身份进行反思启发，输出标准三段式 JSON。
                """.formatted(
                truncate(domain != null ? domain : DEFAULT_DOMAIN),
                truncate(symptom != null ? symptom : "未知现象"),
                truncate(rootCause != null ? rootCause : "未明确根因"),
                truncate(confusionHints != null && !confusionHints.isBlank() ? confusionHints : "无特定混淆概念"));

        return truncate(prompt, MAX_PROMPT_LENGTH);
    }

    /** 诊断无命中时的统一默认域名，与 WeaknessDiagnosticService.DEFAULT_DOMAIN 保持一致 */
    public static final String DEFAULT_DOMAIN = "General Architecture";

    /**
     * Mock JSON 模板 - LLM 不可用时的降级数据
     *
     * 基于诊断结果动态选择概念对：命中混淆对时用第一对，未命中退化为
     * Virtual Threads vs WebFlux 经典对。保证 JSON 合法且 questions 非空，
     * 使 TutorReflectionPrompt 紧凑构造不会因降级数据而失败。
     *
     * @param diagnostic 混淆度诊断结果 (可为 null)
     */
    public static String getMockJsonTemplate(ConfusionAnalysisResult diagnostic) {
        String pairA = "Virtual Thread (Java 21)";
        String pairB = "Reactive Streams (Spring WebFlux)";
        String topDomain = DEFAULT_DOMAIN;

        if (diagnostic != null) {
            topDomain = diagnostic.topDomain() != null ? diagnostic.topDomain() : DEFAULT_DOMAIN;
            if (!diagnostic.matchedPairs().isEmpty()) {
                var first = diagnostic.matchedPairs().get(0);
                pairA = first.conceptA();
                pairB = first.conceptB();
            }
        }

        return """
                {"targetDomain": "%s","coreConflict": "并发编程中的直观同步阻塞模型 vs 异步非阻塞事件驱动模型之间的心智负担与吞吐权衡","principleAnalysis": "Virtual Threads 试图在不改变同步编码心智模型的前提下，通过用户态调度把操作系统线程解耦；而 WebFlux 是完全基于响应式流与发布订阅机制，通过事件循环与有限的工作线程驱动吞吐。二者在处理取消信号（Cancellation）时传播通道完全不同。","questions": [{"index": 1,"conceptA": "%s","conceptB": "%s","questionText": "当客户端主动关闭连接触发 RST_STREAM 时，WebFlux 的 doOnCancel 钩子与 Virtual Thread 的 Thread.interrupt() 是如何各自感知并逆向传播取消信号的？"}],"reflectiveExperiment": "在本地断开前端 SSE 长连接，通过 jstack 查看后端载体线程（Carrier Thread）是否仍处于 RUNNABLE 状态，并观察是否输出 InterruptedException。"}"""
                .formatted(topDomain, pairA, pairB);
    }

    /**
     * 截断保护：null 安全 + 超长裁剪 + 省略号标记
     */
    static String truncate(String value) {
        return truncate(value, MAX_FIELD_LENGTH);
    }

    static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }
}
