package com.nexusmind.security.rbac;

import java.lang.annotation.*;

/**
 * Agent 访问控制注解
 * 
 * 用于标注 Dubbo 服务接口或具体方法，指定哪些 Agent 角色可以调用
 * 
 * 使用方式：
 * 
 * 1. 标注在接口上（默认应用到所有方法）
 * @AgentSecured(allowedAgents = {"codeAgent"})
 * public interface CodeService {
 *     String analyzeCode(String code);
 * }
 * 
 * 2. 标注在具体方法上（优先级高于接口标注）
 * @Service
 * public class AgentService {
 *     @AgentSecured(allowedAgents = {"journalAgent", "supervisor"})
 *     public void appendLog(String logEntry) {
 *         // ...
 *     }
 * }
 * 
 * 3. 只读操作声明
 * @AgentSecured(
 *     allowedAgents = {"codeAgent"},
 *     readOnly = true
 * )
 * public String getCodeContent(String filePath) {
 *     // 只读操作
 * }
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentSecured {
    
    /**
     * 允许调用该服务/方法的 Agent 角色列表
     * 
     * 值格式对应 AgentRole 的 roleId：
     * - "supervisor" - 监督者
     * - "journalAgent" - 日记代理
     * - "codeAgent" - 代码代理
     * - "tutorAgent" - 导师代理
     * 
     * @return 允许的角色数组
     */
    String[] allowedAgents();
    
    /**
     * 是否为只读操作（默认为 false）
     * 
     * 当设置为 true 时，表示该方法只进行读操作，不修改任何数据。
     * 这可用于：
     * - 区分读写操作
     * - 进行更细粒度的权限控制
     * - 审计日志记录
     * 
     * @return 是否为只读操作
     */
    boolean readOnly() default false;
    
    /**
     * 权限检查失败时的错误消息
     * 
     * @return 自定义错误消息（为空则使用默认消息）
     */
    String errorMessage() default "";
    
    /**
     * 是否需要记录该操作到审计日志
     * 
     * 当设置为 true 时，任何该方法的调用都会被记录到审计日志中
     * 
     * @return 是否需要审计
     */
    boolean audit() default false;
}
