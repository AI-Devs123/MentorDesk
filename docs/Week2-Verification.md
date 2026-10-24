# Week2 完整验证指南

**验证日期**: 2024-10-24  
**预计时间**: 30分钟  
**验证顺序**: PathSandbox → Sanitizer → RBAC

---

## 验证1️⃣: PathSandboxValidator（文件系统保护）

### 目标
验证路径遍历、符号链接、字符截断、编码绕过等攻击防护。

### 步骤1: 运行单元测试

```powershell
cd F:\my\workSpaces\MentorDesk
mvn test -Dtest=PathSandboxValidatorTest -pl nexusmind-security
```

### 预期输出

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.nexusmind.security.sandbox.PathSandboxValidatorTest

测试路径遍历防护 (4个测试)
✓ testPathTraversalAttack_ParentDirectory
✓ testPathTraversalAttack_AbsolutePath
✓ testPathTraversalAttack_DoubleEncoded
✓ testPathTraversalAttack_MixedSlashes

测试符号链接防护 (1个测试)
✓ testSymbolicLinkEscape

测试字符截断防护 (2个测试)
✓ testNullByteInjection
✓ testNullByteInMiddle

测试编码绕过防护 (2个测试)
✓ testUrlEncodedTraversal
✓ testUnicodeTraversal

测试边界检查 (3个测试)
✓ testEmptyPath
✓ testNullPath
✓ testVeryLongPath

测试相对路径 (2个测试)
✓ testRelativePath_Safe
✓ testRelativePath_Traversal

测试缓存机制 (2个测试)
✓ testCachePerformance
✓ testCacheInvalidation

测试特殊情况 (4个测试)
✓ testWindowsDeviceNames
✓ testHiddenFiles
✓ testSpacesInPath
✓ testSpecialCharacters

[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### ✅ 验证标准
- **所有21个测试全部通过**
- 没有Failures或Errors
- 执行时间 < 10秒

### ❌ 如果失败
1. 检查测试报告：`nexusmind-security/target/surefire-reports/`
2. 查看具体失败的测试用例
3. 检查`PathSandboxValidator`实现是否正确

---

## 验证2️⃣: SensitiveDataSanitizer（敏感信息脱敏）

### 目标
验证邮箱、电话、身份证、银行卡等15+种敏感信息的检测和脱敏。

### 步骤1: 运行单元测试

```powershell
mvn test -Dtest=SensitiveDataSanitizerTest -pl nexusmind-security
```

### 预期输出

```
[INFO] Running com.nexusmind.security.sanitizer.SensitiveDataSanitizerTest

测试邮箱脱敏
✓ testSanitizeEmail_Standard
✓ testSanitizeEmail_Multiple

测试电话脱敏
✓ testSanitizePhone_Mobile
✓ testSanitizePhone_Landline

测试身份证脱敏
✓ testSanitizeIdCard_18Digit
✓ testSanitizeIdCard_15Digit

测试银行卡脱敏
✓ testSanitizeBankCard_16Digit
✓ testSanitizeBankCard_19Digit

测试地址脱敏
✓ testSanitizeAddress

测试姓名脱敏
✓ testSanitizeName_Chinese
✓ testSanitizeName_English

测试混合内容脱敏
✓ testSanitizeMixedContent

测试无敏感信息
✓ testNoSensitiveData

测试空值处理
✓ testNullAndEmpty

[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 预期脱敏效果示例

```java
输入: "我的邮箱是john@example.com，电话是13812345678"
输出: "我的邮箱是j***@example.com，电话是138****5678"

输入: "身份证：110101199001011234"
输出: "身份证：110101********1234"

输入: "银行卡：6222021234567890123"
输出: "银行卡：622202**********123"
```

### ✅ 验证标准
- **所有测试通过**
- 敏感信息正确脱敏
- 非敏感信息不受影响

### ❌ 如果失败
1. 检查正则表达式模式是否正确
2. 验证脱敏逻辑是否符合预期
3. 查看是否有边界情况未处理

---

## 验证3️⃣: Agent RBAC（访问控制）- 已修复Bug

### 目标
验证基于角色的访问控制，确保不同Agent有不同权限。

### 🔧 前提：确认Bug已修复

**关键修复**：AgentRbacDubboFilter必须使用`RpcContext.getServerAttachment()`

检查修复：
```powershell
# 查看Filter代码，确认使用了正确的API
cat nexusmind-security/src/main/java/com/nexusmind/security/rbac/AgentRbacDubboFilter.java | Select-String "getServerAttachment"
```

**预期**：应该看到 `RpcContext.getServerAttachment().getAttachment()`

---

### 步骤1: 运行单元测试

```powershell
# 运行所有RBAC相关测试
mvn test -pl nexusmind-security -Dtest=*Rbac*
```

### 预期输出

```
[INFO] Running com.nexusmind.security.rbac.PermissionTest
✓ testPermissionAllowedForSupervisor (9个测试)
[INFO] Tests run: 9, Failures: 0, Errors: 0

[INFO] Running com.nexusmind.security.rbac.AgentRbacContextTest
✓ testSetAndGetCurrentAgent (9个测试)
[INFO] Tests run: 9, Failures: 0, Errors: 0

[INFO] Running com.nexusmind.security.rbac.AgentRbacDubboFilterTest
✓ testFilterInvocation (4个测试)
[INFO] Tests run: 4, Failures: 0, Errors: 0

[INFO] Running com.nexusmind.security.rbac.AgentRbacIntegrationTest
✓ testIntegration (8个测试)
[INFO] Tests run: 8, Failures: 0, Errors: 0

[INFO] ------------------------------------------------------------------------
[INFO] Total tests run: 30+, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
```

---

### 步骤2: 检查Filter注册

```powershell
cat nexusmind-security/src/main/resources/META-INF/dubbo/org.apache.dubbo.rpc.Filter
```

### 预期输出

```
destructiveOperationDubboFilter=com.nexusmind.security.destructive.DestructiveOperationDubboFilter
agentRbacDubboFilter=com.nexusmind.security.rbac.AgentRbacDubboFilter
```

---

### 步骤3: 重新编译和启动服务

```powershell
# 3.1 停止服务
Stop-Process -Name java -Force

# 3.2 重新编译（包含Bug修复）
cd F:\my\workSpaces\MentorDesk
mvn clean package -pl nexusmind-security,nexusmind-supervisor -am -DskipTests

# 3.3 启动Supervisor
cd nexusmind-supervisor/target
java -jar nexusmind-supervisor-1.0.0-SNAPSHOT.jar
```

### 预期日志（Supervisor启动）

```log
INFO  o.a.d.c.d.DubboBootstrap - DubboBootstrap is ready
INFO  Started SupervisorApplication in x.xxx seconds
```

---

### 步骤4: 集成测试 - Supervisor访问（应该成功）

```powershell
curl.exe -N "http://localhost:8080/api/v1/agent/stream?prompt=test&agentRole=supervisor"
```

### 预期输出

```json
{"stage":"SECURITY_GUARD_SCAN","content":"【安全网关】执行 RAG 安全防护检测..."}
{"stage":"DAG_ORCHESTRATION","content":"【任务编排】生成 DAG 拓扑..."}
{"stage":"WORKER_DISPATCH","content":"【任务分发】向具体的 WorkerAgent 集群分发执行任务..."}
{"stage":"RAG_RETRIEVAL","content":"【知识检索】混合召回..."}
{"stage":"TASK_COMPLETED","content":"【执行完毕】已生成执行报告并归档..."}
```

### 预期日志（Supervisor）

```log
🔍 [RBAC-Filter] 拦截RPC调用: interface=AgentStreamingFacade, method=executeTaskStream
🔍 [RBAC-Filter] 从RpcContext提取Agent身份: supervisor
DEBUG Authorization passed: Agent [supervisor] can access [AgentStreamingFacade::executeTaskStream]
INFO  [Supervisor] 收到任务下发
```

---

### 步骤5: 集成测试 - JournalAgent访问（应该被拒绝）

```powershell
curl.exe -N "http://localhost:8080/api/v1/agent/stream?prompt=test&agentRole=journalAgent"
```

### 预期输出

```json
{
  "taskId": "TASK-xxx",
  "timestamp": 1792825000000,
  "stage": "RBAC_ACCESS_DENIED",
  "content": "【RBAC访问拒绝】SecurityGuard RBAC 拦截: Agent [journalAgent] 无权访问 [AgentStreamingFacade::executeTaskStream]，允许的角色: [supervisor]"
}
```

### 预期日志（Supervisor）

```log
🔍 [RBAC-Filter] 拦截RPC调用: interface=AgentStreamingFacade, method=executeTaskStream
🔍 [RBAC-Filter] 从RpcContext提取Agent身份: journalAgent
ERROR c.n.s.r.AgentRbacDubboFilter - SecurityGuard RBAC 拦截: Agent [journalAgent] 无权访问 [AgentStreamingFacade::executeTaskStream]，允许的角色: [supervisor]
```

### 预期日志（Gateway）

```log
🛑 [Gateway] RBAC拦截: SecurityGuard RBAC 拦截: Agent [journalAgent] 无权访问...
```

---

### 步骤6: 测试其他Agent（都应该被拒绝）

```powershell
# CodeAgent
curl.exe -N "http://localhost:8080/api/v1/agent/stream?prompt=test&agentRole=codeAgent"

# TutorAgent
curl.exe -N "http://localhost:8080/api/v1/agent/stream?prompt=test&agentRole=tutorAgent"
```

### 预期输出
与步骤5相同，都返回`RBAC_ACCESS_DENIED`

---

### 步骤7: 测试无效角色

```powershell
curl.exe -N "http://localhost:8080/api/v1/agent/stream?prompt=test&agentRole=invalidRole"
```

### 预期输出

```json
{
  "timestamp": "...",
  "status": 500,
  "error": "Internal Server Error",
  "message": "Invalid Agent role ID: invalidRole"
}
```

---

## ✅ 验证完成检查清单

### PathSandboxValidator
- [ ] 21个单元测试全部通过
- [ ] 无编译错误
- [ ] 执行时间合理

### SensitiveDataSanitizer
- [ ] 14个单元测试全部通过
- [ ] 脱敏效果符合预期
- [ ] 无误伤（非敏感数据不受影响）

### Agent RBAC
- [ ] 30+单元测试全部通过
- [ ] Filter SPI已注册
- [ ] Bug已修复（getServerAttachment）
- [ ] Supervisor启动成功
- [ ] supervisor访问成功 ✅
- [ ] journalAgent被拒绝 ❌
- [ ] codeAgent被拒绝 ❌
- [ ] tutorAgent被拒绝 ❌
- [ ] invalidRole返回500 ❌
- [ ] Supervisor日志有RBAC拦截记录
- [ ] Gateway日志有RBAC拦截记录

---

## 📊 最终验证报告模板

```markdown
## Week2 验证报告

**验证日期**: 2024-10-24
**验证人**: [你的名字]

### 1. PathSandboxValidator
- 单元测试: ✅ 21/21 通过
- 状态: 完全验证通过

### 2. SensitiveDataSanitizer
- 单元测试: ✅ 14/14 通过
- 脱敏效果: ✅ 符合预期
- 状态: 完全验证通过

### 3. Agent RBAC
- 单元测试: ✅ 30+/30+ 通过
- Bug修复: ✅ getServerAttachment已修复
- 集成测试:
  - supervisor访问: ✅ 成功
  - journalAgent访问: ✅ 被拒绝
  - codeAgent访问: ✅ 被拒绝
  - tutorAgent访问: ✅ 被拒绝
  - invalidRole: ✅ 500错误
- 状态: 完全验证通过

### 4. 破坏性操作Token确认
- 状态: ✅ 已验证通过（之前完成）

### 总体评价
- 完成度: 100%
- 质量评级: ⭐⭐⭐⭐⭐
- 生产就绪: 是
```

---

## 🚨 常见问题排查

### Q1: PathSandbox测试失败
**症状**: 某些测试用例失败  
**排查**:
1. 检查沙箱根目录配置
2. 验证文件系统权限
3. 查看测试日志详细错误

### Q2: Sanitizer测试失败
**症状**: 脱敏效果不符合预期  
**排查**:
1. 检查正则表达式
2. 验证脱敏逻辑
3. 测试边界情况

### Q3: RBAC仍然不生效
**症状**: 所有Agent都能访问  
**排查**:
1. **确认Bug已修复**：Filter使用`getServerAttachment()`
2. 查看Supervisor日志是否有"🔍 [RBAC-Filter]"
3. 检查Filter是否在application.yml中配置
4. 验证接口上是否有`@AgentSecured`注解

### Q4: 看不到RBAC日志
**症状**: Supervisor没有RBAC相关日志  
**原因**: Filter未被调用  
**排查**:
1. 检查SPI配置文件是否存在
2. 确认Filter在jar包中
3. 查看application.yml的filter配置

---

**预计总时间**: 30-45分钟  
**难度**: ⭐⭐⭐ 中等  
**建议**: 按顺序执行，每个验证完成后再进行下一个

开始验证吧！遇到问题随时告诉我。
