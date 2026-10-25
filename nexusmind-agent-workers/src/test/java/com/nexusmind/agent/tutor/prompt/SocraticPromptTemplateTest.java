package com.nexusmind.agent.tutor.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.tutor.diagnostic.WeaknessDiagnosticService;
import com.nexusmind.agent.tutor.diagnostic.model.ConfusionAnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SocraticPromptTemplate 单元测试
 *
 * 重点覆盖切片3 评审修复点:
 * - schema 字段名三处统一 (questions，禁止 socraticQuestions 漂移)
 * - 三段式约束完整性
 * - 用户输入 null 兜底与注入隔离
 * - Mock 模板合法且 questions 非空 (保证 DTO 紧凑构造不失败)
 */
class SocraticPromptTemplateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("三段式结构约束齐全：原理剖析/对比辨析设问/反思实验")
    void shouldContainThreeSectionConstraints() {
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("principleAnalysis"));
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("reflectiveExperiment"));
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("原理剖析"));
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("对比辨析设问"));
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("反思实验"));
    }

    @Test
    @DisplayName("schema 字段名统一：questions 存在且 socraticQuestions 不存在")
    void shouldUnifyQuestionsFieldName() {
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("\"questions\""),
                "JSON Schema 必须使用 questions 字段名");
        assertFalse(SocraticPromptTemplate.SYSTEM_PROMPT.contains("socraticQuestions"),
                "标题与 Schema 字段名必须一致，历史 socraticQuestions 漂移已修复");
    }

    @Test
    @DisplayName("反喂答案铁律存在")
    void shouldForbidDirectAnswer() {
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("严格禁止直接给出端到端"));
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("概念A"));
        assertTrue(SocraticPromptTemplate.SYSTEM_PROMPT.contains("概念B"));
    }

    @Test
    @DisplayName("null 兜底：各字段 null 不得产生字面 null 字样")
    void shouldFallbackOnNullFields() {
        String prompt = SocraticPromptTemplate.buildUserPrompt(null, null, null, null);

        assertFalse(prompt.contains(">null<"), "null 字段必须被兜底替换");
        assertTrue(prompt.contains("未知现象"));
        assertTrue(prompt.contains("未明确根因"));
        assertTrue(prompt.contains("无特定混淆概念"));
        assertTrue(prompt.contains("General Architecture"));
    }

    @Test
    @DisplayName("用户内容 XML 隔离 + 长度截断")
    void shouldIsolateAndTruncateUserContent() {
        String longSymptom = "x".repeat(5000);
        String prompt = SocraticPromptTemplate.buildUserPrompt(longSymptom, "根因", "Domain", "hints");

        assertTrue(prompt.contains("<symptom>"), "用户内容必须 XML 标签隔离");
        assertTrue(prompt.contains("</symptom>"));
        assertTrue(prompt.contains("[truncated]"), "超长字段必须截断");
        assertTrue(prompt.length() <= 10000 + 64, "user prompt 总长受控");
    }

    @Test
    @DisplayName("Mock 模板：合法 JSON 且 questions 非空 (诊断命中时取首对概念)")
    void shouldProduceValidMockJson() throws Exception {
        var diagnostic = new ConfusionAnalysisResult(
                List.of(new ConfusionAnalysisResult.MatchedConfusionPair(
                        "Dubbo Triple", "Standard gRPC", "RPC/Network", "摘要", 5)),
                "RPC/Network", 0.65);

        String mockJson = SocraticPromptTemplate.getMockJsonTemplate(diagnostic);

        var root = objectMapper.readTree(mockJson);
        assertEquals("RPC/Network", root.path("targetDomain").asText());
        assertTrue(root.path("questions").isArray());
        assertFalse(root.path("questions").isEmpty(),
                "Mock 的 questions 必须非空，否则冻结 DTO 构造会抛异常");
        assertEquals("Dubbo Triple", root.path("questions").get(0).path("conceptA").asText());
        assertEquals("Standard gRPC", root.path("questions").get(0).path("conceptB").asText());
        assertFalse(root.path("reflectiveExperiment").asText().isBlank());
        assertFalse(root.path("principleAnalysis").asText().isBlank());
    }

    @Test
    @DisplayName("Mock 模板：无诊断结果时退化为经典概念对")
    void shouldFallbackToClassicPairWithoutDiagnostic() throws Exception {
        String mockJson = SocraticPromptTemplate.getMockJsonTemplate(null);

        var root = objectMapper.readTree(mockJson);
        assertEquals("General Architecture", root.path("targetDomain").asText());
        assertEquals("Virtual Thread (Java 21)", root.path("questions").get(0).path("conceptA").asText());
        assertFalse(root.path("questions").isEmpty());
    }

    @Test
    @DisplayName("默认域名与诊断器保持一致")
    void shouldShareDefaultDomainWithDiagnosticService() {
        assertEquals(WeaknessDiagnosticService.DEFAULT_DOMAIN, SocraticPromptTemplate.DEFAULT_DOMAIN);
    }
}
