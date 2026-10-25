package com.nexusmind.agent.journal;

import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.api.dto.JournalEntryDTO;
import com.nexusmind.api.service.IJournalReviewService;

/**
 * 日记审查服务接口 (agent-workers 层)
 * 核心能力库，由 supervisor 调用
 * 实现公共 API 契约
 */
public interface JournalReviewService extends IJournalReviewService {
    
    /**
     * 审查日记并落盘
     * 
     * @param rawContent 原始日记内容
     * @return 包含条目、路径、统计信息的数据载体
     * @throws IllegalArgumentException 输入不合法
     * @throws RuntimeException 处理失败
     */
    DataPayload reviewAndPersist(String rawContent);
    
    /**
     * 解析日记并提取三要素
     * 
     * @param rawContent 原始日记内容
     * @return 结构化日记条目
     */
    JournalEntryDTO parseAndExtract(String rawContent);
    
    /**
     * 持久化日记条目
     * 
     * @param entry 日记条目
     * @return 物理路径 (相对路径)
     */
    String persistJournalEntry(JournalEntryDTO entry);
}
