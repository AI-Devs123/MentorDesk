package com.nexusmind.agent.tutor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.llm.LLMClient;
import com.nexusmind.agent.tutor.diagnostic.WeaknessDiagnosticService;
import com.nexusmind.agent.tutor.diagnostic.model.ConfusionAnalysisResult;
import com.nexusmind.api.dto.TutorReflectionPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TutorAgentServiceImpl 单元测试
 *
 * 重点覆盖切片3 评审修复点:
 * - P0-1 三段式不丢失: principleAnalysis 回填 contextSummary,
 *   reflectiveExperiment 折叠进最后一条 questionText
 * - P0-2 questions 空降级: LLM 违反契约时降级 Mock 不崩
 * - P0-3 knowledgeLinks 空列表: 不挂硬编码假数据
 * - LLMClient 复用: api-key 未配置时走 mock 供应者
 */
class TutorAgentServiceImplTest {

    private final WeaknessDiagnosticService diagnosticService = new WeaknessDiagnosticService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** apiKey 未注入(null) 时 chatOrFallback 直接走 mock 供应者，不发起网络调用 */
    private TutorAgentServiceImpl newService() {
        return new TutorAgentServiceImpl(diagnosticService, new LLMClient(), objectMapper);
    }

    @Test
    @DisplayName("端到端: 无 API Key 时 mock 链路产出完整三段式 DTO")
    void shouldGenerateFromMockWhenNoApiKey() {
        TutorReflectionPrompt result = newService().generateSocraticReflection(
                "前端关闭连接后后端 virtual thread 仍在执行，对比 webflux doOnCancel",
                "取消信号没有逆向传播",
                "Concurrency");

        assertNotNull(result.promptId());
        assertTrue(result.promptId().startsWith("tutor-"));
        // 三段式: coreConflict + contextSummary(原理剖析回填) + questions 非空
        assertFalse(result.coreConflict().isBlank());
        assertFalse(result.contextSummary().isBlank(),
                "contextSummary 应由 principleAnalysis 回填而非空");
        assertFalse(result.questions().isEmpty(),
                "冻结 DTO 要求 questions 非空");
        // 反思实验折叠进最后一条设问
        String lastQuestion = result.questions().get(result.questions().size() - 1).questionText();
        assertTrue(lastQuestion.contains("【反思实验】"),
                "第三段 reflectiveExperiment 必须折叠保留");
        // P0-3: 不挂假历史关联
        assertTrue(result.knowledgeLinks().isEmpty(),
                "无真实历史库比对时 knowledgeLinks 必须为空，禁止硬编码假数据");
    }

    @Test
    @DisplayName("P0-2 降级: LLM 返回空 questions 时自动降级 Mock 不抛异常")
    void shouldFallbackWhenLlmReturnsEmptyQuestions() {
        TutorAgentServiceImpl service = new TutorAgentServiceImpl(diagnosticService, new LLMClient(), objectMapper) {
            @Override
            String executeInference(String userPrompt, ConfusionAnalysisResult diagnostic) {
                return "{\"targetDomain\": \"X\", \"questions\": []}";
            }
        };

        TutorReflectionPrompt result = service.generateSocraticReflection("现象", "根因", "Domain");

        assertFalse(result.questions().isEmpty(),
                "空 questions 必须触发 Mock 降级而非 DTO 构造异常");
    }

    @Test
    @DisplayName("P0-1 映射: principleAnalysis 与 reflectiveExperiment 均有承载位")
    void shouldMapAllThreeSections() {
        String llmJson = """
                {"targetDomain": "Concurrency",
                 "coreConflict": "同步阻塞心智 vs 异步事件驱动",
                 "principleAnalysis": "虚拟线程通过用户态调度解耦 OS 线程",
                 "questions": [{"index": 1, "conceptA": "A", "conceptB": "B", "questionText": "设问一"},
                               {"index": 9, "conceptA": "C", "conceptB": "D", "questionText": "设问二"}],
                 "reflectiveExperiment": "jstack 观察载体线程状态"}""";

        TutorReflectionPrompt result = newService().parseToTutorDto(llmJson, "Fallback", "原始上下文");

        assertEquals("Concurrency", result.targetDomain());
        assertEquals("虚拟线程通过用户态调度解耦 OS 线程", result.contextSummary(),
                "principleAnalysis 必须回填 contextSummary");
        assertEquals(2, result.questions().size());
        // index 不信任 LLM，统一从 1 重编号
        assertEquals(1, result.questions().get(0).index());
        assertEquals(2, result.questions().get(1).index());
        // 实验指引折叠进最后一条
        assertTrue(result.questions().get(1).questionText().endsWith("jstack 观察载体线程状态"));
        assertTrue(result.questions().get(1).questionText().contains("【反思实验】"));
        assertTrue(result.questions().get(0).questionText().contains("设问一"));
    }

    @Test
    @DisplayName("Markdown 包裹兼容: ```json 包裹的响应可正常剥离解析")
    void shouldStripMarkdownWrapper() {
        String wrapped = "```json\n{\"targetDomain\": \"D\", \"coreConflict\": \"C\", \"principleAnalysis\": \"P\", \"questions\": [{\"conceptA\": \"A\", \"conceptB\": \"B\", \"questionText\": \"Q\"}]}\n```";

        TutorReflectionPrompt result = newService().parseToTutorDto(wrapped, "Fallback", "ctx");

        assertEquals(1, result.questions().size());
        assertEquals("P", result.contextSummary());
    }

    @Test
    @DisplayName("字段不完整的辨析问题被跳过，完整的保留")
    void shouldSkipIncompleteQuestions() {
        String llmJson = """
                {"targetDomain": "D", "coreConflict": "C",
                 "questions": [{"conceptA": "A", "conceptB": "B"},
                               {"conceptA": "A", "conceptB": "B", "questionText": "完整设问"}]}""";

        TutorReflectionPrompt result = newService().parseToTutorDto(llmJson, "F", "ctx");

        assertEquals(1, result.questions().size());
        assertEquals("完整设问", result.questions().get(0).questionText());
    }

    @Test
    @DisplayName("入口 null 兜底: 各参数 null 不产生 NPE 与字面 null 字样")
    void shouldHandleNullInputsGracefully() {
        TutorReflectionPrompt result = newService().generateSocraticReflection(null, null, null);

        assertNotNull(result);
        assertFalse(result.questions().isEmpty());
        assertFalse(result.contextSummary().contains("null"),
                "null 参数不得把字面 null 拼进上下文");
    }
}
