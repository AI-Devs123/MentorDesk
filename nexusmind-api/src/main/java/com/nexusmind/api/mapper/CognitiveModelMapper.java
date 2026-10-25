package com.nexusmind.api.mapper;

import com.nexusmind.api.dto.*;
import com.nexusmind.rpc.cognitive.*;
import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * 认知契约 Mapper：负责 Protobuf <-> Java DTO 的双向转换
 * 屏蔽序列化细节，提供清晰的业务层接口
 */
public final class CognitiveModelMapper {
    
    private CognitiveModelMapper() {
        // 工具类，禁止实例化
    }

    // =====================================================================
    // JournalEntryDTO <-> JournalEntryProto 双向转换
    // =====================================================================

    /**
     * 将 Protobuf JournalEntryProto 转换为 DTO
     */
    public static JournalEntryDTO toDto(JournalEntryProto proto) {
        if (proto == null) return null;

        var extraction = new JournalEntryDTO.StructuredExtraction(
            proto.getExtraction().getBreakthroughsList(),
            proto.getExtraction().getBlockersList().stream()
                .map(b -> new JournalEntryDTO.BlockerItem(
                    b.getSymptom(),
                    b.getRootCause(),
                    b.getTechnicalDomain()
                ))
                .toList(),
            proto.getExtraction().getMvpActionsList()
        );

        return new JournalEntryDTO(
            proto.getEntryId(),
            proto.getRawContent(),
            LocalDate.parse(proto.getRecordDate()),
            proto.getTagsList(),
            extraction,
            proto.getCreatedAt()
        );
    }

    /**
     * 将 DTO JournalEntryDTO 转换为 Protobuf
     */
    public static JournalEntryProto toProto(JournalEntryDTO dto) {
        if (dto == null) return null;

        var extractionBuilder = StructuredExtraction.newBuilder()
            .addAllBreakthroughs(dto.extraction().breakthroughs())
            .addAllMvpActions(dto.extraction().mvpActions());

        dto.extraction().blockers().forEach(b ->
            extractionBuilder.addBlockers(
                BlockerItem.newBuilder()
                    .setSymptom(b.symptom())
                    .setRootCause(b.rootCause())
                    .setTechnicalDomain(b.technicalDomain())
                    .build()
            )
        );

        return JournalEntryProto.newBuilder()
            .setEntryId(dto.entryId())
            .setRawContent(dto.rawContent())
            .setRecordDate(dto.recordDate().toString())
            .addAllTags(dto.tags())
            .setExtraction(extractionBuilder.build())
            .setCreatedAt(dto.createdAt())
            .build();
    }

    // =====================================================================
    // PersonaProfile <-> PersonaProfileProto 双向转换
    // =====================================================================

    /**
     * 将 Protobuf PersonaProfileProto 转换为 DTO
     */
    public static PersonaProfile toDto(PersonaProfileProto proto) {
        if (proto == null) return null;

        var biases = proto.getBiasesList().stream()
            .map(b -> new PersonaProfile.CognitiveBias(
                b.getBiasName(),
                b.getManifestation(),
                b.getFrequency(),
                PersonaProfile.BiasConfidence.valueOf(b.getConfidenceLevel())
            ))
            .toList();

        return new PersonaProfile(
            proto.getProfileId(),
            proto.getUpdatedAt(),
            biases,
            proto.getBlindSpotsMap(),
            proto.getAttentionTagsList(),
            proto.getTotalReflectiveDays()
        );
    }

    /**
     * 将 DTO PersonaProfile 转换为 Protobuf
     */
    public static PersonaProfileProto toProto(PersonaProfile dto) {
        if (dto == null) return null;

        var builder = PersonaProfileProto.newBuilder()
            .setProfileId(dto.profileId())
            .setUpdatedAt(dto.updatedAt())
            .putAllBlindSpots(dto.blindSpots())
            .addAllAttentionTags(dto.attentionTags())
            .setTotalReflectiveDays(dto.totalReflectiveDays());

        dto.biases().forEach(b ->
            builder.addBiases(
                CognitiveBias.newBuilder()
                    .setBiasName(b.biasName())
                    .setManifestation(b.manifestation())
                    .setFrequency(b.frequency())
                    .setConfidenceLevel(b.confidenceLevel().name())
                    .build()
            )
        );

        return builder.build();
    }

    // =====================================================================
    // TutorReflectionPrompt <-> TutorReflectionPromptProto 双向转换
    // =====================================================================

    /**
     * 将 Protobuf TutorReflectionPromptProto 转换为 DTO
     */
    public static TutorReflectionPrompt toDto(TutorReflectionPromptProto proto) {
        if (proto == null) return null;

        var questions = proto.getQuestionsList().stream()
            .map(q -> new TutorReflectionPrompt.ReflectionQuestion(
                q.getIndex(),
                q.getConceptA(),
                q.getConceptB(),
                q.getQuestionText()
            ))
            .toList();

        var links = proto.getKnowledgeLinksList().stream()
            .map(l -> new TutorReflectionPrompt.KnowledgeLink(
                l.getHistoricalEntryId(),
                l.getRelationType(),
                l.getInsight()
            ))
            .toList();

        return new TutorReflectionPrompt(
            proto.getPromptId(),
            proto.getTargetDomain(),
            proto.getContextSummary(),
            proto.getCoreConflict(),
            questions,
            links
        );
    }

    /**
     * 将 DTO TutorReflectionPrompt 转换为 Protobuf
     */
    public static TutorReflectionPromptProto toProto(TutorReflectionPrompt dto) {
        if (dto == null) return null;

        var builder = TutorReflectionPromptProto.newBuilder()
            .setPromptId(dto.promptId())
            .setTargetDomain(dto.targetDomain())
            .setContextSummary(dto.contextSummary())
            .setCoreConflict(dto.coreConflict());

        dto.questions().forEach(q ->
            builder.addQuestions(
                ReflectionQuestion.newBuilder()
                    .setIndex(q.index())
                    .setConceptA(q.conceptA())
                    .setConceptB(q.conceptB())
                    .setQuestionText(q.questionText())
                    .build()
            )
        );

        dto.knowledgeLinks().forEach(l ->
            builder.addKnowledgeLinks(
                KnowledgeLink.newBuilder()
                    .setHistoricalEntryId(l.historicalEntryId())
                    .setRelationType(l.relationType())
                    .setInsight(l.insight())
                    .build()
            )
        );

        return builder.build();
    }
}
