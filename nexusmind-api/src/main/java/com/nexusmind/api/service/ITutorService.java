package com.nexusmind.api.service;

import com.nexusmind.api.dto.TutorReflectionPrompt;

/**
 * TutorAgent 启发式反思服务接口 - 公共契约
 *
 * 由 nexusmind-agent-workers 的 TutorAgentServiceImpl 实现并导出，
 * nexusmind-supervisor 的 TutorFacade 通过 @DubboReference 远程调用。
 *
 * 切片3 落地时该类为 agent 进程内组件，切片4 提升为跨进程公共契约：
 * supervisor 不得本地注入 agent 实现类（SupervisorApplication 注释明令禁止），
 * 必须经本接口 + Dubbo 薄门面访问。
 */
public interface ITutorService {

    /**
     * 基于技术阻碍点生成苏格拉底启发反思包
     *
     * @param symptom   故障现象
     * @param rootCause 开发者自判根因
     * @param domain    涉及技术域
     * @return 三段式启发提问契约 DTO（questions 保证非空）
     */
    TutorReflectionPrompt generateSocraticReflection(String symptom, String rootCause, String domain);
}
