package com.nexusmind.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DTO 边界防御测试套件
 * 验证所有非法输入能精准触发异常
 */
@DisplayName("DTO 边界防御验证")
class DtoBoundaryValidationTest {

    // =========================================================================
    // JournalEntryDTO 边界测试
    // =========================================================================

    @Nested
    @DisplayName("JournalEntryDTO 边界验证")
    class JournalEntryBoundaryTests {

        @Test
        @DisplayName("rawContent 为 null 时应抛出 NullPointerException")
        void testRawContentNull() {
            assertThrows(NullPointerException.class, () ->
                new JournalEntryDTO(
                    "2026-10-24-001",
                    null,  // 非法：rawContent 不能为空
                    LocalDate.of(2026, 10, 24),
                    List.of(),
                    createValidExtraction(),
                    System.currentTimeMillis()
                ),
                "rawContent 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("recordDate 为 null 时应抛出 NullPointerException")
        void testRecordDateNull() {
            assertThrows(NullPointerException.class, () ->
                new JournalEntryDTO(
                    "2026-10-24-001",
                    "日记内容",
                    null,  // 非法：recordDate 不能为空
                    List.of(),
                    createValidExtraction(),
                    System.currentTimeMillis()
                ),
                "recordDate 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("extraction 为 null 时应抛出 NullPointerException")
        void testExtractionNull() {
            assertThrows(NullPointerException.class, () ->
                new JournalEntryDTO(
                    "2026-10-24-001",
                    "日记内容",
                    LocalDate.of(2026, 10, 24),
                    List.of(),
                    null,  // 非法：extraction 不能为空
                    System.currentTimeMillis()
                ),
                "extraction 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("tags 为 null 时应转换为空列表")
        void testTagsNullConvertedToEmpty() {
            var dto = new JournalEntryDTO(
                "2026-10-24-001",
                "日记内容",
                LocalDate.of(2026, 10, 24),
                null,  // 转换为 List.of()
                createValidExtraction(),
                System.currentTimeMillis()
            );

            assertTrue(dto.tags().isEmpty(), "null tags 应转换为空列表");
        }

        private JournalEntryDTO.StructuredExtraction createValidExtraction() {
            return new JournalEntryDTO.StructuredExtraction(
                List.of("突破1"),
                List.of(),
                List.of("行动1")
            );
        }
    }

    @Nested
    @DisplayName("BlockerItem 边界验证")
    class BlockerItemBoundaryTests {

        @Test
        @DisplayName("symptom 为 null 时应抛出 NullPointerException")
        void testSymptomNull() {
            assertThrows(NullPointerException.class, () ->
                new JournalEntryDTO.BlockerItem(
                    null,  // 非法
                    "根因分析",
                    "技术域"
                ),
                "symptom 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("rootCause 为 null 时应抛出 NullPointerException")
        void testRootCauseNull() {
            assertThrows(NullPointerException.class, () ->
                new JournalEntryDTO.BlockerItem(
                    "现象",
                    null,  // 非法
                    "技术域"
                ),
                "rootCause 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("technicalDomain 为 null 时应抛出 NullPointerException")
        void testTechnicalDomainNull() {
            assertThrows(NullPointerException.class, () ->
                new JournalEntryDTO.BlockerItem(
                    "现象",
                    "根因分析",
                    null  // 非法
                ),
                "technicalDomain 为 null 应抛出异常"
            );
        }
    }

    // =========================================================================
    // PersonaProfile 边界测试
    // =========================================================================

    @Nested
    @DisplayName("PersonaProfile 边界验证")
    class PersonaProfileBoundaryTests {

        @Test
        @DisplayName("profileId 为 null 时应抛出 NullPointerException")
        void testProfileIdNull() {
            assertThrows(NullPointerException.class, () ->
                new PersonaProfile(
                    null,  // 非法
                    System.currentTimeMillis(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    0
                ),
                "profileId 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("blindSpots 评分超出 [0.0, 1.0] 范围时应抛出 IllegalArgumentException")
        void testBlindSpotsOutOfRange() {
            // 测试上界越界
            assertThrows(IllegalArgumentException.class, () ->
                new PersonaProfile(
                    "user-001",
                    System.currentTimeMillis(),
                    List.of(),
                    Map.of("Memory", 1.5),  // 非法：超过 1.0
                    List.of(),
                    0
                ),
                "盲区评分 > 1.0 应抛出异常"
            );

            // 测试下界越界
            assertThrows(IllegalArgumentException.class, () ->
                new PersonaProfile(
                    "user-001",
                    System.currentTimeMillis(),
                    List.of(),
                    Map.of("Memory", -0.1),  // 非法：小于 0.0
                    List.of(),
                    0
                ),
                "盲区评分 < 0.0 应抛出异常"
            );
        }

        @Test
        @DisplayName("blindSpots 边界值 0.0 和 1.0 应合法")
        void testBlindSpotsValidBoundary() {
            var profile = new PersonaProfile(
                "user-001",
                System.currentTimeMillis(),
                List.of(),
                Map.of("Memory", 0.0, "Network", 1.0),  // 边界值合法
                List.of(),
                0
            );

            assertEquals(0.0, profile.blindSpots().get("Memory"));
            assertEquals(1.0, profile.blindSpots().get("Network"));
        }

        @Test
        @DisplayName("totalReflectiveDays 为负数时应抛出 IllegalArgumentException")
        void testNegativeTotalReflectiveDays() {
            assertThrows(IllegalArgumentException.class, () ->
                new PersonaProfile(
                    "user-001",
                    System.currentTimeMillis(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    -1  // 非法
                ),
                "负数复盘天数应抛出异常"
            );
        }

        @Test
        @DisplayName("totalReflectiveDays 为 0 时应合法")
        void testZeroTotalReflectiveDays() {
            var profile = new PersonaProfile(
                "user-001",
                System.currentTimeMillis(),
                List.of(),
                Map.of(),
                List.of(),
                0  // 合法：新用户
            );

            assertEquals(0, profile.totalReflectiveDays());
        }
    }

    @Nested
    @DisplayName("CognitiveBias 边界验证")
    class CognitiveBiasBoundaryTests {

        @Test
        @DisplayName("biasName 为 null 时应抛出 NullPointerException")
        void testBiasNameNull() {
            assertThrows(NullPointerException.class, () ->
                new PersonaProfile.CognitiveBias(
                    null,  // 非法
                    "表现",
                    1,
                    PersonaProfile.BiasConfidence.MEDIUM
                ),
                "biasName 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("frequency 为负数时应抛出 IllegalArgumentException")
        void testNegativeFrequency() {
            assertThrows(IllegalArgumentException.class, () ->
                new PersonaProfile.CognitiveBias(
                    "偏置名",
                    "表现",
                    -1,  // 非法
                    PersonaProfile.BiasConfidence.MEDIUM
                ),
                "负数频次应抛出异常"
            );
        }

        @Test
        @DisplayName("frequency 为 0 时应合法")
        void testZeroFrequency() {
            var bias = new PersonaProfile.CognitiveBias(
                "偏置名",
                "表现",
                0,  // 合法：新观察
                PersonaProfile.BiasConfidence.LOW
            );

            assertEquals(0, bias.frequency());
        }
    }

    // =========================================================================
    // TutorReflectionPrompt 边界测试
    // =========================================================================

    @Nested
    @DisplayName("TutorReflectionPrompt 边界验证")
    class TutorReflectionPromptBoundaryTests {

        @Test
        @DisplayName("promptId 为 null 时应抛出 NullPointerException")
        void testPromptIdNull() {
            assertThrows(NullPointerException.class, () ->
                new TutorReflectionPrompt(
                    null,  // 非法
                    "domain",
                    "context",
                    "conflict",
                    List.of(createValidQuestion()),
                    List.of()
                ),
                "promptId 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("coreConflict 为 null 时应抛出 NullPointerException")
        void testCoreConflictNull() {
            assertThrows(NullPointerException.class, () ->
                new TutorReflectionPrompt(
                    "prompt-001",
                    "domain",
                    "context",
                    null,  // 非法
                    List.of(createValidQuestion()),
                    List.of()
                ),
                "coreConflict 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("questions 为空列表时应抛出 IllegalArgumentException")
        void testQuestionsEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                new TutorReflectionPrompt(
                    "prompt-001",
                    "domain",
                    "context",
                    "conflict",
                    List.of(),  // 非法：至少需要一个问题
                    List.of()
                ),
                "空问题列表应抛出异常"
            );
        }

        @Test
        @DisplayName("questions 不为空时应合法")
        void testQuestionsNonEmpty() {
            var prompt = new TutorReflectionPrompt(
                "prompt-001",
                "domain",
                "context",
                "conflict",
                List.of(createValidQuestion()),
                List.of()
            );

            assertEquals(1, prompt.questions().size());
        }

        private TutorReflectionPrompt.ReflectionQuestion createValidQuestion() {
            return new TutorReflectionPrompt.ReflectionQuestion(
                1,
                "概念A",
                "概念B",
                "问题文本"
            );
        }
    }

    @Nested
    @DisplayName("ReflectionQuestion 边界验证")
    class ReflectionQuestionBoundaryTests {

        @Test
        @DisplayName("conceptA 为 null 时应抛出 NullPointerException")
        void testConceptANull() {
            assertThrows(NullPointerException.class, () ->
                new TutorReflectionPrompt.ReflectionQuestion(
                    1,
                    null,  // 非法
                    "B",
                    "问题"
                ),
                "conceptA 为 null 应抛出异常"
            );
        }

        @Test
        @DisplayName("index 为负数时应抛出 IllegalArgumentException")
        void testNegativeIndex() {
            assertThrows(IllegalArgumentException.class, () ->
                new TutorReflectionPrompt.ReflectionQuestion(
                    -1,  // 非法
                    "A",
                    "B",
                    "问题"
                ),
                "负数 index 应抛出异常"
            );
        }

        @Test
        @DisplayName("index 为 0 时应合法")
        void testZeroIndex() {
            var question = new TutorReflectionPrompt.ReflectionQuestion(
                0,  // 合法
                "A",
                "B",
                "问题"
            );

            assertEquals(0, question.index());
        }
    }
}
