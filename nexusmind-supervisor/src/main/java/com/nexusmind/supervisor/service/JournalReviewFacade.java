package com.nexusmind.supervisor.service;

import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.api.service.IJournalReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 日记审查门面服务 (supervisor 层 - 薄层)
 * 
 * 职责:
 * - 调用 nexusmind-agent-workers 的核心服务
 * - 暴露给 Controller 层
 * - 不涉及核心业务逻辑 (那些都在 agent-workers 中)
 * 
 * 注意: 这是一个门面(Facade) 模式的实现，用于维持层级分离
 */
@Service
public class JournalReviewFacade {
    
    /**
     * 注入 agent-workers 的核心服务
     * 通过公共 API 契约接口
     */
    @Autowired
    private IJournalReviewService journalReviewService;
    
    /**
     * 审查日记并落盘
     * 委托给 agent-workers 的实现
     * 
     * @param rawContent 原始日记内容
     * @return 包含条目、路径、统计信息的数据载体
     */
    public DataPayload reviewAndPersist(String rawContent) {
        return journalReviewService.reviewAndPersist(rawContent);
    }
}
