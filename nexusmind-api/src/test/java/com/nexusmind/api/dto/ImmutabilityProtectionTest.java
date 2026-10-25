package com.nexusmind.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 不可变保护测试套件
 * 验证 DTO 内部状态受到完整的防御性保护
 * 外部对 List/Map 的修改不应影响 DTO 内部状态
 */
@DisplayName("DTO 不可变保护验证")
class ImmutabilityProtectionTest {

    // =========================================================================
    // JournalEntryDTO 不可变保护
    // =========================================================================

    @Nested
    @DisplayName("JournalEntryDTO 不可变保护")
    class JournalEntryImmutabilityTests {

        @Test
        @DisplayName("外部修改 tags List 不影响 DTO 内部状态")
        void testTagsListImmutability() {
            // Arrange: 创建可修改的列表
            var mutableTags = new ArrayList<>(List.of("Java", "Spring"));
            
            var extraction = new JournalEntryDTO.StructuredExtraction(
                List.of("突破"),
                List.of(),
                List.of("行动")
            );
            
            var dto = new JournalEntryDTO(
                "2026-10-24-001",
                "日记内容",
                LocalDate.of(2026, 10, 24),
                mutableTags,  // 传入可修改列表
                extraction,
                System.currentTimeMillis()
            );

            // Act: 修改外部列表
            mutableTags.add("Dubbo");
            mutableTags.set(0, "Python");

            // Assert: DTO 内部状态不变
            assertEquals(2, dto.tags().size(), "DTO 内部 tags 应保持原大小");
            assertEquals("Java", dto.tags().get(0), "第一个标签应保持不变");
            assertEquals("Spring", dto.tags().get(1), "第二个标签应保持不变");
            assertFalse(dto.tags().contains("Dubbo"), "外部添加的元素不应出现在 DTO 内");
        }

        @Test
        @DisplayName("尝试修改返回的 tags List 应抛出 UnsupportedOperationException")
        void testReturningTagsListIsUnmodifiable() {
            var dto = new JournalEntryDTO(
                "2026-10-24-001",
                "日记内容",
                LocalDate.of(2026, 10, 24),
                List.of("Java", "Spring"),
                new JournalEntryDTO.StructuredExtraction(
                    List.of("突破"),
                    List.of(),
                    List.of("行动")
                ),
                System.currentTimeMillis()
            );

            // Act & Assert: 尝试修改返回的列表应触发异常
            assertThrows(UnsupportedOperationException.class, () ->
                dto.tags().add("Dubbo"),
                "修改返回的 tags List 应抛出异常"
            );

            assertThrows(UnsupportedOperationException.class, () ->
                dto.tags().set(0, "Python"),
                "修改返回的 tags List 元素应抛出异常"
            );
        }

        @Test
        @DisplayName("嵌套 StructuredExtraction 中的 breakthroughs 不可修改")
        void testBreakthroughsListImmutability() {
            var extraction = new JournalEntryDTO.StructuredExtraction(
                List.of("掌握 CompletableFuture"),
                List.of(),
                List.of()
            );

            var dto = new JournalEntryDTO(
                "2026-10-24-001",
                "日记内容",
                LocalDate.of(2026, 10, 24),
                List.of(),
                extraction,
                System.currentTimeMillis()
            );

            // Act & Assert
            assertThrows(UnsupportedOperationException.class, () ->
                dto.extraction().breakthroughs().add("新的突破"),
                "修改 breakthroughs 应抛出异常"
            );
        }

        @Test
        @DisplayName("嵌套 blockers List 不可修改")
        void testBlockersListImmutability() {
            var blocker = new JournalEntryDTO.BlockerItem("现象", "根因", "技术域");
            var extraction = new JournalEntryDTO.StructuredExtraction(
                List.of(),
                List.of(blocker),
                List.of()
            );

            var dto = new JournalEntryDTO(
                "2026-10-24-001",
                "日记内容",
                LocalDate.of(2026, 10, 24),
                List.of(),
                extraction,
                System.currentTimeMillis()
            );

            // Act & Assert
            assertThrows(UnsupportedOperationException.class, () ->
                dto.extraction().blockers().add(
                    new JournalEntryDTO.BlockerItem("新现象", "新根因", "新域")
                ),
                "修改 blockers 应抛出异常"
            );
        }
    }

    // =========================================================================
    // PersonaProfile 不可变保护
    // =========================================================================

    @Nested
    @DisplayName("PersonaProfile 不可变保护")
    class PersonaProfileImmutabilityTests {

        @Test
        @DisplayName("外部修改 biases List 不影响 DTO 内部状态")
        void testBiasesListImmutability() {
            // Arrange
            var mutableBiases = new ArrayList<>(List.of(
                new PersonaProfile.CognitiveBias(
                    "乐观预估",
                    "表现",
                    3,
                    PersonaProfile.BiasConfidence.MEDIUM
                )
            ));

            var profile = new PersonaProfile(
                "user-001",
                System.currentTimeMillis(),
                mutableBiases,
                Map.of(),
                List.of(),
                0
            );

            // Act: 修改外部列表
            mutableBiases.add(
                new PersonaProfile.CognitiveBias(
                    "新偏置",
                    "新表现",
                    1,
                    PersonaProfile.BiasConfidence.LOW
                )
            );

            // Assert
            assertEquals(1, profile.biases().size(), "内部 biases 应保持原大小");
            assertEquals("乐观预估", profile.biases().get(0).biasName());
        }

        @Test
        @DisplayName("返回的 biases List 不可修改")
        void testReturningBiasesListIsUnmodifiable() {
            var profile = new PersonaProfile(
                "user-001",
                System.currentTimeMillis(),
                List.of(
                    new PersonaProfile.CognitiveBias(
                        "乐观预估",
                        "表现",
                        3,
                        PersonaProfile.BiasConfidence.MEDIUM
                    )
                ),
                Map.of(),
                List.of(),
                0
            );

            assertThrows(UnsupportedOperationException.class, () ->
                profile.biases().add(
                    new PersonaProfile.CognitiveBias(
                        "新偏置",
                        "新表现",
                        1,
                        PersonaProfile.BiasConfidence.LOW
                    )
                ),
                "修改返回的 biases List 应抛出异常"
            );
        }

        @Test
        @DisplayName("外部修改 blindSpots Map 不影响 DTO 内部状态")
        void testBlindSpotsMapImmutability() {
            // Arrange
            var mutableMap = new HashMap<>(Map.of("Memory", 0.8, "Network", 0.5));

            var profile = new PersonaProfile(
                "user-001",
                System.currentTimeMillis(),
                List.of(),
                mutableMap,
                List.of(),
                0
            );

            // Act: 修改外部 Map
            mutableMap.put("Memory", 0.2);
            mutableMap.put("NewDomain", 0.9);

            // Assert
            assertEquals(0.8, profile.blindSpots().get("Memory"), 
                "内部 Map 中 Memory 值应保持不变");
            assertFalse(profile.blindSpots().containsKey("NewDomain"),
                "外部添加的键不应出现在内部 Map");
        }

        @Test
        @DisplayName("返回的 blindSpots Map 不可修改")
        void testReturningBlindSpotsMapIsUnmodifiable() {
            var profile = new PersonaProfile(
                "user-001",
                System.currentTimeMillis(),
                List.of(),
                Map.of("Memory", 0.8),
                List.of(),
                0
            );

            assertThrows(UnsupportedOperationException.class, () ->
                profile.blindSpots().put("Network", 0.5),
                "修改返回的 blindSpots Map 应抛出异常"
            );
        }

        @Test
        @DisplayName("返回的 attentionTags List 不可修改")
        void testReturningAttentionTagsIsUnmodifiable() {
            var profile = new PersonaProfile(
                "user-001",
                System.currentTimeMillis(),
                List.of(),
                Map.of(),
                List.of("High-Energy: Morning"),
                0
            );

            assertThrows(UnsupportedOperationException.class, () ->
                profile.attentionTags().add("New Tag"),
                "修改返回的 attentionTags List 应抛出异常"
            );
        }
    }

    // =========================================================================
    // TutorReflectionPrompt 不可变保护
    // =========================================================================

    @Nested
    @DisplayName("TutorReflectionPrompt 不可变保护")
    class TutorReflectionPromptImmutabilityTests {

        @Test
        @DisplayName("返回的 questions List 不可修改")
        void testReturningQuestionsListIsUnmodifiable() {
            var prompt = new TutorReflectionPrompt(
                "prompt-001",
                "Java/Concurrency",
                "context",
                "conflict",
                List.of(
                    new TutorReflectionPrompt.ReflectionQuestion(
                        1,
                        "Sync",
                        "Async",
                        "问题"
                    )
                ),
                List.of()
            );

            assertThrows(UnsupportedOperationException.class, () ->
                prompt.questions().add(
                    new TutorReflectionPrompt.ReflectionQuestion(
                        2,
                        "A",
                        "B",
                        "新问题"
                    )
                ),
                "修改返回的 questions List 应抛出异常"
            );
        }

        @Test
        @DisplayName("返回的 knowledgeLinks List 不可修改")
        void testReturningKnowledgeLinksIsUnmodifiable() {
            var prompt = new TutorReflectionPrompt(
                "prompt-001",
                "Java/Concurrency",
                "context",
                "conflict",
                List.of(
                    new TutorReflectionPrompt.ReflectionQuestion(
                        1,
                        "A",
                        "B",
                        "问题"
                    )
                ),
                List.of(
                    new TutorReflectionPrompt.KnowledgeLink(
                        "entry-001",
                        "EVOLUTION_OF",
                        "insight"
                    )
                )
            );

            assertThrows(UnsupportedOperationException.class, () ->
                prompt.knowledgeLinks().add(
                    new TutorReflectionPrompt.KnowledgeLink(
                        "entry-002",
                        "CONFLICT_WITH",
                        "new insight"
                    )
                ),
                "修改返回的 knowledgeLinks List 应抛出异常"
            );
        }

        @Test
        @DisplayName("外部修改传入的 List 不影响内部状态")
        void testExternalListModificationDoesNotAffectPrompt() {
            // Arrange
            var mutableQuestions = new ArrayList<>(List.of(
                new TutorReflectionPrompt.ReflectionQuestion(
                    1,
                    "A",
                    "B",
                    "问题1"
                )
            ));

            var prompt = new TutorReflectionPrompt(
                "prompt-001",
                "Java/Concurrency",
                "context",
                "conflict",
                mutableQuestions,
                List.of()
            );

            // Act: 修改外部列表
            mutableQuestions.add(
                new TutorReflectionPrompt.ReflectionQuestion(
                    2,
                    "C",
                    "D",
                    "问题2"
                )
            );

            // Assert
            assertEquals(1, prompt.questions().size(),
                "内部 questions 应保持原大小");
        }
    }

    // =========================================================================
    // Record 类型本质验证
    // =========================================================================

    @Nested
    @DisplayName("Java Record 不可变性质验证")
    class RecordImmutabilityEssenceTests {

        @Test
        @DisplayName("Record 类型不提供 setter 方法")
        void testRecordNoSetterMethods() {
            var dto = new JournalEntryDTO(
                "2026-10-24-001",
                "日记内容",
                LocalDate.of(2026, 10, 24),
                List.of(),
                new JournalEntryDTO.StructuredExtraction(
                    List.of(),
                    List.of(),
                    List.of()
                ),
                System.currentTimeMillis()
            );

            // Record 不提供 setters，只能通过访问器方法读取
            // 这是编译期强制的
            String content = dto.rawContent();
            assertEquals("日记内容", content);

            // 不存在 setRawContent() 方法
            // 验证通过 reflection 也无法获取 setter
            var methods = dto.getClass().getMethods();
            var hasRawContentSetter = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.getName().equals("setRawContent"));
            
            assertFalse(hasRawContentSetter, "Record 应不提供 setter 方法");
        }
    }
}
