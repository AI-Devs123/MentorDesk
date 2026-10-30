// 场景 2：Supervisor 日记审查批量突增（验证 Sentinel 门禁 + 下游熔断）
// 入口：POST /api/v1/journal/review（Supervisor Servlet MVC）
// 目标：① 突增流量下网关/Supervisor 的 Sentinel QPS 门禁将超额请求挡成 429；
//       ② 若下游 agent-workers 故障，ResilientDownstreamCaller 的 DegradeRule
//          （异常比例≥0.5）将打开熔断，请求转为 500 降级而非压垮已故障的下游（防雪崩）。
// 链路：Supervisor → Dubbo agent-workers（JournalReviewServiceImpl）

import http from 'k6/http';
import { check } from 'k6';
import { SUPERVISOR_BASE, login } from './common.js';

export const options = {
  scenarios: {
    journal_burst: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '20s', target: 120 },  // 20s 内突增到 120 req/s
        { duration: '40s', target: 120 },  // 持续高压 40s
        { duration: '20s', target: 0 },     // 回落
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<3000'],
  },
};

export function setup() {
  return { token: login(SUPERVISOR_BASE, 'admin', 'admin123') };
}

export default function (data) {
  const rawContent =
    '今天完成了全链路压测方案设计，覆盖网关 SSE 流式问答、Supervisor 日记审查与 RAG 混合检索三个场景；' +
    '下午与团队对齐了 Sentinel 限流与下游熔断的阈值，并补充了 Grafana 监控面板；' +
    '晚上复盘了本周的检索准确率指标，整理了下周的知识库增量计划。';
  const res = http.post(
    `${SUPERVISOR_BASE}/api/v1/journal/review`,
    JSON.stringify({ rawContent }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${data.token}`,
      },
      timeout: '15s',
    }
  );
  // 三态皆属正常处理：200=成功，429=被限流门禁挡下，500=下游熔断降级
  check(res, {
    'handled_200_429_500': (r) => [200, 429, 500].includes(r.status),
  });
}
