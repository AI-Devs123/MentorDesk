package com.nexusmind.security.rbac;

/**
 * Agent 角色定义
 * 
 * 定义系统中所有可能的 Agent 角色及其权限
 */
public enum AgentRole {
    
    /**
     * 监督者 - 全量编排权限
     * 职责：
     * - 负责任务编排和协调
     * - 不直接操作底层文件
     * - 必须下发子指令给具体 Agent
     * 
     * 权限：
     * - ✅ 调用所有服务
     * - ✅ 编排 DAG
     * - ❌ 直接文件操作
     */
    SUPERVISOR("supervisor", "监督者"),
    
    /**
     * 日记代理 - 日记与追加日志
     * 职责：
     * - 管理日记和日志
     * - 只能追加内容，不能修改或删除
     * 
     * 权限：
     * - ✅ 日记目录读取
     * - ✅ 日记目录追加写
     * - ❌ 代码库访问
     * - ❌ 系统命令执行
     * - ❌ 凭证库访问
     */
    JOURNAL_AGENT("journalAgent", "日记代理"),
    
    /**
     * 代码代理 - 代码库与 Git 操作
     * 职责：
     * - 管理代码库
     * - 执行 AST 解析
     * - 进行 Git 操作
     * 
     * 权限：
     * - ✅ 代码工作区读写
     * - ✅ AST 解析
     * - ✅ Git 操作
     * - ❌ 日记目录访问
     * - ❌ 健康私密数据访问
     * - ❌ 凭证库访问
     * - ❌ 系统命令执行
     */
    CODE_AGENT("codeAgent", "代码代理"),
    
    /**
     * 导师代理 - RAG 知识检索
     * 职责：
     * - 提供 RAG 知识检索
     * - 只读访问知识库
     * 
     * 权限：
     * - ✅ RAG 知识库只读访问
     * - ✅ 知识检索
     * - ❌ 任何本地物理文件写操作
     * - ❌ 代码执行
     */
    TUTOR_AGENT("tutorAgent", "导师代理"),

    /**
     * 网页策展代理 - 网页抓取 / 去噪 / 知识萃取
     * 职责：
     * - 经 SSRF 防护的双通道抓取网页
     * - 正文提纯与 LLM 结构化萃取、自适应语义分块
     * - 入库载荷落盘沙箱 inbox/web/
     * 
     * 权限：
     * - ✅ 网页入库（WEB_INGEST）
     * - ❌ 代码库访问
     * - ❌ 日记目录访问
     * - ❌ 健康私密数据访问
     */
    WEB_CURATOR("webCurator", "网页策展代理");

    /**
     * 角色标识符
     */
    private final String roleId;
    
    /**
     * 角色描述
     */
    private final String description;
    
    AgentRole(String roleId, String description) {
        this.roleId = roleId;
        this.description = description;
    }
    
    /**
     * 根据角色 ID 查找角色
     */
    public static AgentRole fromId(String roleId) {
        if (roleId == null || roleId.trim().isEmpty()) {
            return null;
        }
        
        for (AgentRole role : AgentRole.values()) {
            if (role.roleId.equalsIgnoreCase(roleId)) {
                return role;
            }
        }
        
        return null;
    }
    
    public String getRoleId() {
        return roleId;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 检查该角色是否具有指定权限
     */
    public boolean hasPermission(Permission permission) {
        return permission.isAllowedFor(this);
    }
}
