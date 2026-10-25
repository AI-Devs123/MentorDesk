package com.nexusmind.api.service;

import com.nexusmind.api.dto.DataPayload;

/**
 * 日记审查服务接口 - 公共契约
 * 
 * 该接口由 nexusmind-agent-workers 实现，
 * nexusmind-supervisor 通过该接口调用核心能力
 */
public interface IJournalReviewService {
    
    /**
     * 审查日记并落盘
     * 
     * @param rawContent 原始日记内容
     * @return 包含条目、路径、统计信息的数据载体
     */
    DataPayload reviewAndPersist(String rawContent);
}
