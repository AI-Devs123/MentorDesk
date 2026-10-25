package com.nexusmind.api.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 日记条目 DTO：承载结构化日记三段式提取与原始内容
 * 不可变设计，确保状态一致性
 */
public record JournalEntryDTO(
    String entryId,           // 唯一标识 (UUID 或 YYYY-MM-DD-seq)
    String rawContent,        // 原始 Markdown 文本
    LocalDate recordDate,     // 业务日期 (ISO-8601: YYYY-MM-DD)
    List<String> tags,        // 领域标签 (如: ["Spring Boot", "RAG", "Dubbo"])
    StructuredExtraction extraction,  // 三段式强制抽取实体
    long createdAt           // 时间戳 (Epoch Millis)
) implements Serializable {
    
    public JournalEntryDTO {
        Objects.requireNonNull(rawContent, "rawContent 不能为空");
        Objects.requireNonNull(recordDate, "recordDate 不能为空");
        Objects.requireNonNull(extraction, "三段式 extraction 不能为空");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /**
     * 三段式结构化提取实体
     */
    public record StructuredExtraction(
        List<String> breakthroughs,   // 【今日突破】核心技术与认知进展
        List<BlockerItem> blockers,   // 【阻碍与因果归因】技术阻碍及归因分析
        List<String> mvpActions       // 【次日 MVP 行动清单】高可执行动作
    ) implements Serializable {
        
        public StructuredExtraction {
            breakthroughs = breakthroughs == null ? List.of() : List.copyOf(breakthroughs);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            mvpActions = mvpActions == null ? List.of() : List.copyOf(mvpActions);
        }
    }

    /**
     * 阻碍项：记录现象、根因、关联技术域
     */
    public record BlockerItem(
        String symptom,           // 阻碍现象/表征
        String rootCause,         // 根因分析
        String technicalDomain    // 关联技术域 (如: "Memory/GC", "Network/HTTP2")
    ) implements Serializable {
        
        public BlockerItem {
            Objects.requireNonNull(symptom, "symptom 不能为空");
            Objects.requireNonNull(rootCause, "rootCause 不能为空");
            Objects.requireNonNull(technicalDomain, "technicalDomain 不能为空");
        }
    }
}
