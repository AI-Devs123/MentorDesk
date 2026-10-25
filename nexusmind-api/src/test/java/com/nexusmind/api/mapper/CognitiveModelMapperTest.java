package com.nexusmind.api.mapper;

import com.nexusmind.api.dto.*;
import com.nexusmind.rpc.cognitive.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CognitiveModelMapper 测试套件
 * 验证 Protobuf <-> DTO 双向映射的无损性与完整性
 */
@DisplayName("认知契约映射器测试")
class CognitiveModelMapperTest {

    // =========================================================================
    // JournalEntry 双向映射测试
    // =========================================================================

    @Test
    @DisplayName("JournalEntryDTO 转 Proto 再转回 - 验证无字段截断")
    void testJournalEntryDtoToProtoAndBack() {
        // Arrange: 构造完整的 DTO（包含所有可选字段）
        var blocker = new JournalEntryDTO.BlockerItem(
            "内存泄漏导致 GC 暂停",
            "循环引用未释放",
            "Memory/GC"
        );
        
        var extraction = new JournalEntryDTO.StructuredExtraction(
            List.of("掌握了 CompletableFuture 并发模式", "理解 ForkJoinPool 工作窃取"),
            List.of(blocker),
            List.of("复审内存分析工具使用", "补充 GC 日志解读")
        );
        
        var original = new JournalEntryDTO(
            "2026-10-24-001",
            "# 日记标题\n## 今日回顾\n工作内容详述...",
            LocalDate.of(2026, 10, 24),
            List.of("Java", "Performance", "JVM"),
            extraction,
            1729780800000L
        );

        // Act: DTO -> Proto -> DTO
        var proto = CognitiveModelMapper.toProto(original);
        var reconstructed = CognitiveModelMapper.toDto(proto);

        // Assert: 逐字段验证完整性
        assertEquals(original.entryId(), reconstructed.entryId(), "entryId 应保持一致");
        assertEquals(original.rawContent(), reconstructed.rawContent(), "rawContent 应保持一致");
        assertEquals(original.recordDate(), reconstructed.recordDate(), "recordDate 应保持一致");
        assertEquals(original.tags(), reconstructed.tags(), "tags 列表应完全一致");
        assertEquals(original.createdAt(), reconstructed.createdAt(), "createdAt 时间戳应一致");

        // 验证嵌套的三段式提取
        assertEquals(
            original.extraction().breakthroughs(),
            reconstructed.extraction().breakthroughs(),
            "breakthroughs 应完全一致"
        );
        assertEquals(
            original.extraction().mvpActions(),
            reconstructed.extraction().mvpActions(),
            "mvpActions 应完全一致"
        );

        // 验证嵌套的阻碍项
        assertEquals(1, reconstructed.extraction().blockers().size(), "应有 1 个阻碍项");
        var reconstructedBlocker = reconstructed.extraction().blockers().get(0);
        assertEquals("内存泄漏导致 GC 暂停", reconstructedBlocker.symptom());
        assertEquals("循环引用未释放", reconstructedBlocker.rootCause());
        assertEquals("Memory/GC", reconstructedBlocker.technicalDomain());
    }

    @Test
    @DisplayName("空字段处理 - 空列表转换")
    void testJournalEntryWithEmptyFields() {
        var extraction = new JournalEntryDTO.StructuredExtraction(
            List.of(),  // 空 breakthroughs
            List.of(),  // 空 blockers
            List.of()   // 空 mvpActions
        );

        var dto = new JournalEntryDTO(
            "2026-10-24-002",
            "最小化日记",
            LocalDate.of(2026, 10, 24),
            List.of(),  // 空 tags
            extraction,
            System.currentTimeMillis()
        );

        // Act & Assert
        var proto = CognitiveModelMapper.toProto(dto);
        var reconstructed = CognitiveModelMapper.toDto(proto);

        assertTrue(reconstructed.extraction().breakthroughs().isEmpty());
        assertTrue(reconstructed.extraction().blockers().isEmpty());
        assertTrue(reconstructed.extraction().mvpActions().isEmpty());
        assertTrue(reconstructed.tags().isEmpty());
    }

    // =========================================================================
    // PersonaProfile 双向映射测试
    // =========================================================================

    @Test
    @DisplayName("PersonaProfile 转 Proto 再转回 - 验证无数据丢失")
    void testPersonaProfileDtoToProtoAndBack() {
        // Arrange
        var bias = new PersonaProfile.CognitiveBias(
            "乐观时间预估",
            "总是低估了解细节的时间成本",
            5,
            PersonaProfile.BiasConfidence.MEDIUM
        );

        var original = new PersonaProfile(
            "user-2026-10-24",
            1729780800000L,
            List.of(bias),
            Map.of("Memory/GC", 0.75, "Network", 0.3),
            List.of("High-Energy: Morning", "Focus: 10:00-12:00"),
            42
        );

        // Act: DTO -> Proto -> DTO
        var proto = CognitiveModelMapper.toProto(original);
        var reconstructed = CognitiveModelMapper.toDto(proto);

        // Assert
        assertEquals(original.profileId(), reconstructed.profileId());
        assertEquals(original.updatedAt(), reconstructed.updatedAt());
        assertEquals(original.totalReflectiveDays(), reconstructed.totalReflectiveDays());
        assertEquals(original.attentionTags(), reconstructed.attentionTags());
        assertEquals(original.blindSpots(), reconstructed.blindSpots());

        // 验证偏置项
        assertEquals(1, reconstructed.biases().size());
        var reconstructedBias = reconstructed.biases().get(0);
        assertEquals("乐观时间预估", reconstructedBias.biasName());
        assertEquals("总是低估了解细节的时间成本", reconstructedBias.manifestation());
        assertEquals(5, reconstructedBias.frequency());
        assertEquals(PersonaProfile.BiasConfidence.MEDIUM, reconstructedBias.confidenceLevel());
    }

    // =========================================================================
    // TutorReflectionPrompt 双向映射测试
    // =========================================================================

    @Test
    @DisplayName("TutorReflectionPrompt 转 Proto 再转回 - 验证问题链完整性")
    void testTutorReflectionPromptDtoToProtoAndBack() {
        // Arrange
        var question = new TutorReflectionPrompt.ReflectionQuestion(
            1,
            "同步 I/O 阻塞模型",
            "异步 Future 非阻塞模型",
            "在高并发场景下，两者性能差异的根本原因是什么？"
        );

        var link = new TutorReflectionPrompt.KnowledgeLink(
            "2026-10-23-001",
            "EVOLUTION_OF",
            "昨日学习的 ThreadPool 模型现在升级为 ForkJoinPool"
        );

        var original = new TutorReflectionPrompt(
            "prompt-2026-10-24-001",
            "Java/Concurrency",
            "用户遇到的问题：CompletableFuture 为何比 ExecutorService 更高效",
            "本质矛盾：线程上下文切换开销 vs 事件驱动轮询开销的权衡",
            List.of(question),
            List.of(link)
        );

        // Act
        var proto = CognitiveModelMapper.toProto(original);
        var reconstructed = CognitiveModelMapper.toDto(proto);

        // Assert
        assertEquals(original.promptId(), reconstructed.promptId());
        assertEquals(original.targetDomain(), reconstructed.targetDomain());
        assertEquals(original.contextSummary(), reconstructed.contextSummary());
        assertEquals(original.coreConflict(), reconstructed.coreConflict());

        assertEquals(1, reconstructed.questions().size());
        var q = reconstructed.questions().get(0);
        assertEquals(1, q.index());
        assertEquals("同步 I/O 阻塞模型", q.conceptA());
        assertEquals("异步 Future 非阻塞模型", q.conceptB());

        assertEquals(1, reconstructed.knowledgeLinks().size());
        var l = reconstructed.knowledgeLinks().get(0);
        assertEquals("2026-10-23-001", l.historicalEntryId());
        assertEquals("EVOLUTION_OF", l.relationType());
    }

    // =========================================================================
    // Null 防护测试
    // =========================================================================

    @Test
    @DisplayName("Null 输入处理 - Proto 为 null 时返回 null")
    void testNullProtoInput() {
        assertNull(CognitiveModelMapper.toDto((JournalEntryProto) null));
        assertNull(CognitiveModelMapper.toDto((PersonaProfileProto) null));
        assertNull(CognitiveModelMapper.toDto((TutorReflectionPromptProto) null));
    }

    @Test
    @DisplayName("Null 输入处理 - DTO 为 null 时返回 null")
    void testNullDtoInput() {
        assertNull(CognitiveModelMapper.toProto((JournalEntryDTO) null));
        assertNull(CognitiveModelMapper.toProto((PersonaProfile) null));
        assertNull(CognitiveModelMapper.toProto((TutorReflectionPrompt) null));
    }
}
