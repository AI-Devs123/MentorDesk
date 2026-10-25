package com.nexusmind.agent.tutor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.llm.LLMClient;
import com.nexusmind.agent.tutor.diagnostic.WeaknessDiagnosticService;
import com.nexusmind.agent.tutor.diagnostic.model.ConfusionAnalysisResult;
import com.nexusmind.agent.tutor.prompt.SocraticPromptTemplate;
import com.nexusmind.api.dto.TutorReflectionPrompt;
import com.nexusmind.security.rbac.AgentSecured;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TutorAgent 核心服务 (agent-workers 层 - 只读认知引导能力)
 *
 * 职责闭环: 混淆度诊断预热 -> Few-Shot Prompt 填充 -> LLM/Mock 三段式推理
 *          -> 契约 DTO (TutorReflectionPrompt) 映射
 *
 * 执行边界 (READ_ONLY_RAG):
 * - 无本地文件写、无代码 AST、无外部持久化；仅读取 LLMClient 与内存诊断器
 * - 挂 @AgentSecured(readOnly=true): 编码"导师禁写"约束，切片4 经 Dubbo
 *   导出后由 AgentRbacDubboFilter 在 RPC 层强制执行
 *
 * 历史知识关联说明: 本版本暂不产出 KnowledgeLink (传空列表)。
 * 硬编码假历史 ID 触碰"杜绝空泛"交付红线 (切片3 评审 P0-3)，真实关联点
 * 待只读 JournalHistoryReader 落地后从历史复盘记录中挖掘。
 */
@Service
public class TutorAgentServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(TutorAgentServiceImpl.class);

    /** 苏格拉底式提问允许更高温度鼓励发散，与 journal 链路的 0.3 严格抽取区分 */
    private static final double TUTOR_TEMPERATURE = 0.7;

    private static final int TUTOR_MAX_TOKENS = 1500;

    private final WeaknessDiagnosticService diagnosticService;

    private final LLMClient llmClient;

    private final ObjectMapper objectMapper;

    public TutorAgentServiceImpl(WeaknessDiagnosticService diagnosticService,
                                 LLMClient llmClient,
                                 ObjectMapper objectMapper) {
        this.diagnosticService = diagnosticService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 基于技术阻碍点生成苏格拉底启发反思包
     *
     * @param symptom   故障现象
     * @param rootCause 开发者自判根因
     * @param domain    涉及技术域
     * @return 切片1 冻结契约 DTO (questions 保证非空)
     */
    @AgentSecured(allowedAgents = {"supervisor", "tutorAgent"}, readOnly = true, audit = true)
    public TutorReflectionPrompt generateSocraticReflection(String symptom, String rootCause, String domain) {
        log.info("[TutorAgent] 收到启发反思请求, domain={}", domain);

        // Step 1: 本地轻量级混淆度诊断 (入口做 null 兜底，防止 "null" 字样污染 prompt)
        String safeSymptom = symptom != null ? symptom : "";
        String safeRootCause = rootCause != null ? rootCause : "";
        String safeDomain = domain != null ? domain : "";
        String combinedContext = (safeSymptom + " " + safeRootCause + " " + safeDomain).trim();

        ConfusionAnalysisResult diagnostic = diagnosticService.analyzeText(combinedContext);
        log.info("[TutorAgent] 混淆度诊断完成: 命中 {} 对, topDomain={}, confidence={}",
                diagnostic.matchedPairs().size(), diagnostic.topDomain(), diagnostic.confusionConfidence());

        // Step 2: 组装混淆概念提示词
        StringBuilder hintsBuilder = new StringBuilder();
        for (var pair : diagnostic.matchedPairs()) {
            hintsBuilder.append(String.format("[%s vs %s: %s]; ",
                    pair.conceptA(), pair.conceptB(), pair.coreDifferenceSummary()));
        }

        // Step 3: 组装 user prompt 并执行推理 (LLM 失败自动降级 Mock)
        String userPrompt = SocraticPromptTemplate.buildUserPrompt(
                safeSymptom, safeRootCause, diagnostic.topDomain(), hintsBuilder.toString());
        String rawJson = executeInference(userPrompt, diagnostic);

        // Step 4: 解析转换为冻结契约 DTO
        TutorReflectionPrompt result = parseToTutorDto(rawJson, diagnostic.topDomain(), combinedContext);
        log.info("[TutorAgent] 启发反思生成完成: {} 个辨析问题", result.questions().size());
        return result;
    }

    /**
     * 执行 LLM 推理，失败降级 Mock
     *
     * 包级可见便于单元测试覆盖 (测试子类可重写返回畸形 JSON 验证降级链路)。
     */
    String executeInference(String userPrompt, ConfusionAnalysisResult diagnostic) {
        return llmClient.chatOrFallback(
                SocraticPromptTemplate.SYSTEM_PROMPT,
                userPrompt,
                TUTOR_TEMPERATURE,
                TUTOR_MAX_TOKENS,
                () -> SocraticPromptTemplate.getMockJsonTemplate(diagnostic));
    }

    /**
     * 解析 LLM 三段式 JSON 响应为冻结契约 DTO
     *
     * 字段映射 (切片3 P0-1 修复):
     * - principleAnalysis 回填 contextSummary (为空时退化为原始上下文)
     * - reflectiveExperiment 折叠进最后一条 questionText，保证三段式不丢失
     * - questions 为空时降级 Mock 重试一次 (切片3 P0-2 修复)，仍失败才抛异常
     */
    TutorReflectionPrompt parseToTutorDto(String rawJson, String fallbackDomain, String contextSummary) {
        return parseToTutorDto(rawJson, fallbackDomain, contextSummary, true);
    }

    /**
     * @param allowFallback false 时禁止再走 Mock 降级，防止极端情况无限递归
     */
    private TutorReflectionPrompt parseToTutorDto(String rawJson, String fallbackDomain,
                                                  String contextSummary, boolean allowFallback) {
        try {
            JsonNode root = objectMapper.readTree(cleanMarkdown(rawJson));

            String targetDomain = root.path("targetDomain").asText(fallbackDomain);
            String coreConflict = root.path("coreConflict").asText("底层系统设计权衡冲突");
            String principleAnalysis = root.path("principleAnalysis").asText("");

            List<TutorReflectionPrompt.ReflectionQuestion> questions = parseQuestions(root);
            if (questions.isEmpty()) {
                if (allowFallback) {
                    // LLM 违反三段式契约: 降级 Mock 重试一次，避免直接触发 DTO 紧凑构造异常
                    log.warn("[TutorAgent] LLM 未返回 questions，违反三段式契约，降级 Mock 重试");
                    return parseToTutorDto(SocraticPromptTemplate.getMockJsonTemplate(null),
                            fallbackDomain, contextSummary, false);
                }
                throw new IllegalArgumentException("LLM 与 Mock 降级均未返回有效 questions，疑似三段式契约违背");
            }

            // 第三段 (反思实验) 折叠进最后一条设问，保持"先思后验"的阅读顺序
            String reflectiveExperiment = root.path("reflectiveExperiment").asText("");
            if (!reflectiveExperiment.isBlank()) {
                int last = questions.size() - 1;
                var lastQ = questions.get(last);
                questions.set(last, new TutorReflectionPrompt.ReflectionQuestion(
                        lastQ.index(), lastQ.conceptA(), lastQ.conceptB(),
                        lastQ.questionText() + "\n【反思实验】" + reflectiveExperiment));
            }

            // 历史知识关联点: 暂无只读历史加载器，传空列表而非硬编码假数据
            List<TutorReflectionPrompt.KnowledgeLink> knowledgeLinks = List.of();

            String summary = !principleAnalysis.isBlank() ? principleAnalysis : contextSummary;
            return new TutorReflectionPrompt(
                    "tutor-" + UUID.randomUUID().toString().substring(0, 8),
                    targetDomain,
                    summary,
                    coreConflict,
                    questions,
                    knowledgeLinks);

        } catch (Exception e) {
            log.error("[TutorAgent] 解析 LLM 启发提问响应失败", e);
            throw new RuntimeException("TutorAgent 生成启发提问失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 questions 数组；LLM 给出的 index 不信任，统一按 1 起重编号防乱序
     */
    private List<TutorReflectionPrompt.ReflectionQuestion> parseQuestions(JsonNode root) {
        List<TutorReflectionPrompt.ReflectionQuestion> questions = new ArrayList<>();
        JsonNode qNodes = root.path("questions");
        if (qNodes.isArray()) {
            int idx = 1;
            for (JsonNode qn : qNodes) {
                String conceptA = qn.path("conceptA").asText("");
                String conceptB = qn.path("conceptB").asText("");
                String questionText = qn.path("questionText").asText("");
                if (conceptA.isBlank() || conceptB.isBlank() || questionText.isBlank()) {
                    log.warn("[TutorAgent] 跳过字段不完整的辨析问题: conceptA={}, conceptB={}", conceptA, conceptB);
                    continue;
                }
                questions.add(new TutorReflectionPrompt.ReflectionQuestion(
                        idx++, conceptA, conceptB, questionText));
            }
        }
        return questions;
    }

    /**
     * 剥离 LLM 响应可能的 Markdown 代码块包裹 (兼容 ```json / ``` 前缀后缀)
     */
    String cleanMarkdown(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```json")) {
            s = s.substring(7);
        } else if (s.startsWith("```")) {
            s = s.substring(3);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }
}
