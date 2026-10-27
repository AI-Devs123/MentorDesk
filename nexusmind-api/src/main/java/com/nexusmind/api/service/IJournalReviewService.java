package com.nexusmind.api.service;

import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.security.rbac.AgentSecured;

/**
 * 日记审查服务接口 - 公共契约
 * 
 * 该接口由 nexusmind-agent-workers 实现，
 * nexusmind-supervisor 通过该接口调用核心能力
 *
 * <p><b>RBAC（切片5 闭环修复）：</b>公共契约方法必须挂 {@link AgentSecured}。
 * {@code AgentRbacDubboFilter} 仅从 <b>接口</b> 读取注解（invoker.getInterface()），
 * 实现类方法上的注解对它无效——此前日记服务缺接口级注解，导致 codeAgent 可越权调用，
 * 属于真实安全缺口。此处显式声明仅 supervisor / journalAgent 可调用，并开启审计。
 */
public interface IJournalReviewService {
    
    /**
     * 审查日记并落盘
     * 
     * @param rawContent 原始日记内容
     * @return 包含条目、路径、统计信息的数据载体
     */
    @AgentSecured(allowedAgents = {"supervisor", "journalAgent"}, audit = true)
    DataPayload reviewAndPersist(String rawContent);
}
