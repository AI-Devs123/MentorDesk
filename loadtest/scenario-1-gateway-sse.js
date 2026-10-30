// 场景 1：网关 SSE 流式问答高并发
// 入口：GET /api/v1/cognitive/stream（网关 WebFlux，SSE 推流）
// 目标：验证网关 Sentinel 接入门禁在并发 SSE 下生效——超 QPS 返回 429，
//       其余请求被放行进入 Dubbo 认知流。链路：网关 → Dubbo 认知流服务。
// 注意：SSE 是长连接，k6 以 timeout 截断每个请求来模拟「持续在线」的并发压力。

import http from 'k6/http';
import { check } from 'k6';
import { GATEWAY_BASE, login } from './common.js';

export const options = {
  scenarios: {
    sse_concurrent: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },   // 30s 内爬升到 50 并发 SSE
        { duration: '1m', target: 50 },    // 稳定保持 1 分钟
        { duration: '30s', target: 0 },    // 优雅回落
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 网关门禁产生的 429 属预期保护，不计入"失败"；仅统计连接级错误（含服务未起）
    http_req_failed: ['rate<0.10'],
  },
};

export function setup() {
  return { token: login(GATEWAY_BASE, 'admin', 'admin123') };
}

export default function (data) {
  const raw = '请帮我复盘今天的工作，总结三个关键产出并给出明天的计划';
  const url = `${GATEWAY_BASE}/api/v1/cognitive/stream?rawContent=${encodeURIComponent(raw)}`;
  const res = http.get(url, {
    headers: { Authorization: `Bearer ${data.token}` },
    timeout: '5s',
    responseType: 'text',
  });
  // 网关 Sentinel 前置门禁：超 QPS → 429；否则 200 并开始 SSE 推流
  check(res, {
    'admitted_or_throttled': (r) => r.status === 200 || r.status === 429,
  });
}
