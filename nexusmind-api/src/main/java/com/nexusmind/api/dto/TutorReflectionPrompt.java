package com.nexusmind.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 苏格拉底式启发提问提示词与关联槽位
 * 不可变设计，用于 TutorAgent 生成启发式问题链
 */
public record TutorReflectionPrompt(
    String promptId,                    // 提示词唯一标识
    String targetDomain,                // 针对的技术域
    String contextSummary,              // 阻碍背景摘要 (源自 JournalAgent)
    String coreConflict,                // 底层架构冲突/本质矛盾阐述
    List<ReflectionQuestion> questions, // 辨析性思考题
    List<KnowledgeLink> knowledgeLinks  // 历史新旧知识关联线索槽位
) implements Serializable {
    
    public TutorReflectionPrompt {
        Objects.requireNonNull(promptId, "promptId 不能为空");
        Objects.requireNonNull(targetDomain, "targetDomain 不能为空");
        Objects.requireNonNull(coreConflict, "coreConflict 底层本质矛盾阐述不能为空");
        
        questions = questions == null ? List.of() : List.copyOf(questions);
        knowledgeLinks = knowledgeLinks == null ? List.of() : List.copyOf(knowledgeLinks);
        
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("必须至少包含一个反思问题");
        }
    }

    /**
     * 反思问题：对比两个概念进行辨析
     */
    public record ReflectionQuestion(
        int index,                // 序号
        String conceptA,          // 对比概念 A
        String conceptB,          // 对比概念 B
        String questionText       // 辨析式问题正文
    ) implements Serializable {
        
        public ReflectionQuestion {
            Objects.requireNonNull(conceptA, "conceptA 不能为空");
            Objects.requireNonNull(conceptB, "conceptB 不能为空");
            Objects.requireNonNull(questionText, "questionText 不能为空");
            
            if (index < 0) {
                throw new IllegalArgumentException("问题序号不能为负数");
            }
        }
    }

    /**
     * 知识关联链接：关联历史日记或知识库文档
     */
    public record KnowledgeLink(
        String historicalEntryId,   // 关联的历史日记/知识库文档 ID
        String relationType,        // 关系类型 (如: "EVOLUTION_OF", "CONFLICT_WITH")
        String insight              // 关联洞察说明
    ) implements Serializable {
        
        public KnowledgeLink {
            Objects.requireNonNull(historicalEntryId, "historicalEntryId 不能为空");
            Objects.requireNonNull(relationType, "relationType 不能为空");
            Objects.requireNonNull(insight, "insight 不能为空");
        }
    }
}
