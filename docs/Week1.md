# NexusMind 项目开发笔记

## 📋 项目概览

**项目名称：** NexusMind - Agent 协同执行平台  
**核心目标：** 支持多个 AI Agent 的任务编排、流式执行和实时交互  
**架构模式：** 微服务 + 流式计算 + 实时推送

---

## 🏗️ 系统架构

```
┌──────────────────┐
│   前端浏览器      │
│  (SSE 长连接)     │
└────────┬─────────┘
         │ HTTP GET /api/v1/agent/stream
         │
┌────────▼──────────────────┐
│  nexusmind-gateway         │
│  (HTTP/SSE + Dubbo 客户端) │
└────────┬──────────────────┘
         │ Dubbo Triple (gRPC)
         │
┌────────▼──────────────────┐
│ nexusmind-supervisor       │
│ (核心执行 + 流式推送)      │
└────────────────────────────┘
         │
      Nacos 注册中心
```

### 模块职责

| 模块 | 职责 | 技术栈 |
|------|------|--------|
| **nexusmind-api** | RPC 契约定义（Request/Response/Proto） | Protobuf 3 |
| **nexusmind-gateway** | HTTP 网关，负责流式长连接转发 | Spring WebFlux + Dubbo Client |
| **nexusmind-supervisor** | 后端执行引擎，流式推送任务结果 | Spring Boot + Dubbo Server |

---

## 🔄 核心流程

### 正常执行流程

```
1. 前端发起 SSE 请求
   GET /api/v1/agent/stream?prompt=问题&agent=Supervisor

2. 网关处理
   - 生成唯一 taskId
   - 发起 Dubbo Triple 流式调用到 Supervisor

3. Supervisor 执行
   - 注册任务到 TASK_CANCELLATION_FLAGS（中断标志表）
   - 注册执行线程到 RUNNING_THREADS（线程注册表）
   - 模拟多阶段执行（安全防护→DAG编排→任务分发→RAG检索→完成报告）
   - 逐帧推送 AgentStreamResponse

4. 网关转发
   - 接收每帧数据
   - 通过 SSE 推送给前端

5. 流程完成
   - Supervisor: onCompleted()
   - 网关: 关闭 SSE 连接
```

### 中断流程（前端断连）

```
1. 前端关闭/刷新页面
   → 浏览器关闭 SSE 连接

2. 网关捕获断连事件
   → sink.onCancel() 触发

3. 网关异步调用
   → Supervisor.cancelTask(taskId)

4. Supervisor 处理中断
   - 置位 TASK_CANCELLATION_FLAGS[taskId] = true
   - 调用 RUNNING_THREADS[taskId].interrupt()

5. Supervisor 线程感知中断
   - 下个执行阶段检查点检测到 InterruptedException
   - 提前返回，放弃剩余阶段

6. 响应网关
   → {found=true, interrupted=true, activeTaskCount=N}
```

---

## 🛠️ 技术栈详解

### 核心依赖

```xml
<!-- Spring Boot 3.3.4 -->
<spring-boot-version>3.3.4</spring-boot-version>

<!-- Dubbo 3.2.14（RPC 框架）-->
<dubbo.version>3.2.14</dubbo.version>

<!-- Protobuf 3.25.3（消息序列化）-->
<protobuf.version>3.25.3</protobuf.version>

<!-- Spring WebFlux（响应式）-->
<!-- 包含在 Spring Boot 中 -->

<!-- Nacos 客户端（注册中心）-->
<nacos.version>由 Dubbo 管理</nacos.version>
```

### 关键技术点

#### 1. Dubbo Triple (gRPC)
- 后端服务间通信协议
- 支持服务端流式、客户端流式、双向流式
- 自动与 Nacos 集成

#### 2. Spring WebFlux
- 响应式 Web 框架
- 高效处理 HTTP SSE 长连接
- 支持背压（Backpressure）处理

#### 3. Server-Sent Events (SSE)
- 前后端通信协议
- 单向推送（服务端 → 客户端）
- 自动断连重连机制

#### 4. 并发管理
- `ConcurrentHashMap<String, AtomicBoolean>` - 任务中断标志表
- `ConcurrentHashMap<String, Thread>` - 执行线程注册表
- `Thread.interrupt()` - 立即中断机制

---

## 📝 关键代码设计

### 1. 网关长连接处理（AgentGatewayController）

**关键特性：**
- SSE 推流：通过 `Flux<ServerSentEvent<String>>` 向前端推送数据
- 异步中断感知：监听 `Disposable` 的断连事件
- 参数传递：支持 `prompt` 和 `agent` 参数

**处理流程：**
```java
@GetMapping("/stream")
public Flux<ServerSentEvent<String>> streamAgent(
    @RequestParam String prompt,
    @RequestParam String agent) {
    
    // 1. 生成 taskId
    String taskId = generateTaskId();
    
    // 2. 调用 Dubbo Triple
    Flux<AgentStreamResponse> responses = 
        dubboClient.executeTaskStream(request);
    
    // 3. 转换为 SSE
    return responses
        .map(resp -> ServerSentEvent.builder()
            .data(resp.getData())
            .build())
        .doFinally(signal -> {
            // 4. 监听断连事件
            if (signal == SignalType.CANCEL) {
                cancelTask(taskId);
            }
        });
}
```

### 2. Supervisor 执行管理（AgentStreamingFacadeImpl）

**中断机制实现：**
```java
// 全局中断标志表
private static final ConcurrentHashMap<String, AtomicBoolean> 
    TASK_CANCELLATION_FLAGS = new ConcurrentHashMap<>();

// 线程注册表
private static final ConcurrentHashMap<String, Thread> 
    RUNNING_THREADS = new ConcurrentHashMap<>();

// 执行任务
public void executeTaskStream(String taskId) {
    // 1. 注册当前线程
    RUNNING_THREADS.put(taskId, Thread.currentThread());
    TASK_CANCELLATION_FLAGS.put(taskId, new AtomicBoolean(false));
    
    try {
        // 2. 多阶段执行，每阶段前检查中断标志
        for (int stage = 1; stage <= 5; stage++) {
            checkCancellation(taskId);  // 检查点
            executeStage(stage);        // 执行阶段
        }
    } catch (InterruptedException e) {
        // 捕获中断异常，提前返回
        Thread.currentThread().interrupt();
    } finally {
        // 3. 清理资源
        RUNNING_THREADS.remove(taskId);
        TASK_CANCELLATION_FLAGS.remove(taskId);
    }
}

// 中断检查
private void checkCancellation(String taskId) 
        throws InterruptedException {
    if (TASK_CANCELLATION_FLAGS
            .getOrDefault(taskId, new AtomicBoolean(false))
            .get()) {
        throw new InterruptedException("Task cancelled");
    }
}

// 取消任务
public void cancelTask(String taskId) {
    TASK_CANCELLATION_FLAGS
        .computeIfPresent(taskId, 
            (k, v) -> new AtomicBoolean(true));
    
    Thread thread = RUNNING_THREADS.get(taskId);
    if (thread != null) {
        thread.interrupt();  // 立即中断执行线程
    }
}
```

### 3. 消息定义（Proto）

```protobuf
service AgentStreamingService {
  // 服务端流式：客户端发送请求，服务端流式返回
  rpc ExecuteTaskStream(AgentStreamRequest) 
      returns (stream AgentStreamResponse);
  
  // 双向流式：支持客户端交互
  rpc BiDirectionalStream(stream AgentStreamRequest) 
      returns (stream AgentStreamResponse);
  
  // 取消任务
  rpc CancelTask(CancelTaskRequest) 
      returns (CancelTaskResponse);
}

message AgentStreamRequest {
  string prompt = 1;        // 任务指令
  string agent_name = 2;    // Agent 名称
  string task_id = 3;       // 任务 ID
}

message AgentStreamResponse {
  string data = 1;          // 响应数据
  int32 phase = 2;          // 执行阶段
  string status = 3;        // 状态
}
```

---

## ⚙️ 配置说明

### Gateway 配置（application.yml）
```yaml
server:
  port: 8080

dubbo:
  registry:
    address: nacos://localhost:8848
  consumer:
    timeout: 120000  # 长流式操作超时
  protocol:
    name: tri        # gRPC 协议
```

### Supervisor 配置（application.yml）
```yaml
server:
  port: 8081

dubbo:
  protocol:
    port: 50051      # Dubbo Triple 服务端口
    name: tri
  registry:
    address: nacos://localhost:8848
  provider:
    timeout: 120000
```

---

## 🚀 开发指南

### 本地开发环境要求

1. **Java 17+**
   ```bash
   java -version  # 确认版本
   ```

2. **Nacos 注册中心**
   ```bash
   # 启动 Nacos（默认 localhost:8848）
   cd deploy/nacos
   ./startup.sh  # Mac/Linux
   # 或 startup.cmd  # Windows
   ```

3. **Maven 3.8+**
   ```bash
   mvn --version
   ```

### 启动顺序

```bash
# 1. 启动 Supervisor（后端服务）
cd nexusmind-supervisor
mvn spring-boot:run

# 2. 启动 Gateway（网关）
cd nexusmind-gateway
mvn spring-boot:run

# 3. 前端测试
curl -N http://localhost:8080/api/v1/agent/stream?prompt=测试&agent=Supervisor
```

### 常见问题排查

| 问题 | 排查步骤 |
|------|----------|
| Dubbo 无法连接到 Nacos | 1. 确认 Nacos 已启动（localhost:8848）<br>2. 检查 application.yml 中 registry.address 配置 |
| SSE 连接超时 | 1. 检查 Gateway dubbo.consumer.timeout 配置<br>2. 检查网络连接 |
| 任务无法中断 | 1. 查看 TASK_CANCELLATION_FLAGS 中是否存在 taskId<br>2. 检查 Thread.interrupt() 是否被正确调用 |

---

## 📊 监控和调试

### 查看运行中的任务

```bash
# 通过日志查看任务执行状态
tail -f nexusmind-supervisor/logs/*.log

# 监听 Supervisor 的任务注册表
# 在代码中添加日志输出 TASK_CANCELLATION_FLAGS.size()
```

### Dubbo 管理后台

- 访问地址：`http://localhost:8848/nacos`
- 查看已注册服务
- 查看提供者/消费者信息

### API 文档

- Gateway Swagger UI: `http://localhost:8080/swagger-ui.html`
- Supervisor Swagger UI: `http://localhost:8081/swagger-ui.html`

---

## 🔮 扩展方向

### 短期（1-2 周）
1. **持久化存储** - 集成 MySQL，记录任务执行历史
2. **日志系统** - 集成 ELK / Loki，支持分布式日志查询
3. **错误恢复** - 任务异常时的重试机制

### 中期（1 月）
1. **多 Agent 调度** - 扩展 DAG 编排引擎
2. **认证授权** - 集成 JWT / OAuth
3. **限流熔断** - Dubbo 服务治理

### 长期（1-3 月）
1. **可观测性** - Prometheus + Grafana 监控
2. **链路追踪** - OpenTelemetry + Jaeger
3. **分布式事务** - Seata 分布式事务框架
4. **动态配置** - Nacos 配置中心集成

---

## 📚 参考资源

- [Dubbo 官方文档](https://dubbo.apache.org/zh-cn/docs/)
- [Spring WebFlux](https://spring.io/projects/spring-webflux)
- [Protocol Buffers](https://developers.google.com/protocol-buffers)
- [Server-Sent Events (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)
- [Nacos 官方文档](https://nacos.io/en-us/)

---

## 📅 更新日志

- **2026-10-22** - 初版开发笔记，记录架构设计与核心流程
