# NexusMind 全链路压测 · 三场景

本目录是 **切片4（高可用 / 全链路压测）** 的交付物：用 [k6](https://k6.io/) 定义三条覆盖核心链路的压测场景，
并配套离线 JUnit 并发验证（见下文「离线验证」）。

## 场景总览

| 场景 | 入口 | 链路 | 验证点 |
|------|------|------|--------|
| 1. 网关 SSE 流式问答 | `GET /api/v1/cognitive/stream` | 网关 → Dubbo 认知流 | 并发 SSE 下网关 Sentinel 门禁生效（超额 429） |
| 2. Supervisor 日记审查 | `POST /api/v1/journal/review` | Supervisor → Dubbo agent-workers | 突增流量被 QPS 门禁挡成 429；下游故障时熔断降级 500 |
| 3. RAG 混合检索 | `GET /api/v1/search/stream` | 网关 → Dubbo rag（Lucene+HNSW） | 检索全链路在负载下稳定；PushGateway 指标外推不丢 |

三条链路均经过切片3 的 Sentinel 接入门禁（网关 WebFilter / Supervisor ServletFilter）与下游熔断
（`ResilientDownstreamCaller` + `DegradeRule`）。

## 前置条件

1. **服务已起**：`nexusmind-gateway`(:8080)、`nexusmind-supervisor`(:8081) 已启动且能注册到 Nacos。
   监控栈可一并起：`docker compose -f ../deploy/observability/docker-compose.yml up -d`
   （Prometheus 抓 `/actuator/prometheus`，Grafana:3000 看板）。
2. **k6**：`docker compose` 方式（本目录已备 `docker-compose.k6.yml`）或本地安装 `k6`。
3. **鉴权**：脚本通过 `POST /api/v1/auth/login`（admin/admin123 → supervisor 角色）取 token，
   无需手动传 JWT。

## 运行方式

### 方式 A：Docker（推荐，宿主机即靶机）

`docker-compose.k6.yml` 用 `network_mode: host`，容器内 `localhost` 即宿主。

```bash
# 场景 1
GATEWAY_BASE=http://localhost:8080 \
SUPERVISOR_BASE=http://localhost:8081 \
  docker compose -f loadtest/docker-compose.k6.yml run k6 run /scripts/scenario-1-gateway-sse.js

# 场景 2
GATEWAY_BASE=http://localhost:8080 \
SUPERVISOR_BASE=http://localhost:8081 \
  docker compose -f loadtest/docker-compose.k6.yml run k6 run /scripts/scenario-2-supervisor-journal.js

# 场景 3
GATEWAY_BASE=http://localhost:8080 \
SUPERVISOR_BASE=http://localhost:8081 \
  docker compose -f loadtest/docker-compose.k6.yml run k6 run /scripts/scenario-3-rag-search.js
```

### 方式 B：本地 k6

```bash
k6 run loadtest/scenario-1-gateway-sse.js
k6 run loadtest/scenario-2-supervisor-journal.js
k6 run loadtest/scenario-3-rag-search.js
```

## 成功判据（验收铁证）

- **场景1**：`admitted_or_throttled` 检查 100% 通过；在 Grafana `gateway-sse` 面板应能看到
  `nexusmind_sse_active_sessions` 随 VU 抬升，且 `TOO_MANY_REQUESTS`(429) 占比随突增上升。
- **场景2**：`handled_200_429_500` 100% 通过；高压段 `429` 占比显著（门禁生效）；若临时关停
  agent-workers，应出现 `500` 且比例≈熔断窗口占空比（下游熔断降级，未雪崩）。
- **场景3**：`admitted_or_throttled` 100% 通过；Prometheus `pushgateway` job 持续收到 rag 的
  `nexusmind_rag_*` 指标，无中断。

> 注：本环境（无 Docker/Nacos 常驻）仅完成「脚本就绪 + 离线并发验证」。真链路压测需按上述前置
> 起服务后执行；k6 连不上靶机时会报连接错误（属预期，非脚本缺陷）。

## 离线验证（无需起服务）

切片3/4 附带离线 JUnit 并发测试，证明限流门禁与熔断在**并发**下真实生效：

- `nexusmind-gateway` → `GatewayLoadSimulationTest`：50 线程并发打 `SentinelWebFilter`，
  QPS=5 资源下断言多数请求被挡成 429、少数放行。
- `nexusmind-supervisor` → `SupervisorLoadSimulationTest`：同构打 `SentinelServletFilter`。
- `nexusmind-supervisor` → `DownstreamCircuitBreakerLoadTest`：100 次失败调用触发
  `DegradeRule` 打开，断言降级（fallback）被命中。

这三项是本环境可直接 `mvn test` 复现的「保护在负载下成立」的硬证据；真链路压测脚本（上文）为
生产环境执行用。
