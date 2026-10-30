// 场景 3：RAG 混合检索全链路压测
// 入口：GET /api/v1/search/stream（网关 SSE 桥接 → Dubbo rag 混合检索）
// 目标：在并发检索下验证 rag 模块的 BM25+HNSW 双通道检索、Lucene 索引与
//       PushGateway 指标外推在负载下的稳定性；网关 Sentinel 门禁同样对检索入口生效。
// 链路：网关 → Dubbo rag（HybridSearchServiceImpl，Lucene 检索 + 指标外推）

import http from 'k6/http';
import { check } from 'k6';
import { GATEWAY_BASE, login } from './common.js';

export const options = {
  scenarios: {
    rag_search: {
      executor: 'constant-vus',
      vus: 30,
      duration: '2m',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<3000'],
  },
};

export function setup() {
  return { token: login(GATEWAY_BASE, 'admin', 'admin123') };
}

export default function (data) {
  const query = '机器学习中过拟合应该如何缓解';
  const url =
    `${GATEWAY_BASE}/api/v1/search/stream?query=${encodeURIComponent(query)}` +
    `&collection=journal&topN=5`;
  const res = http.get(url, {
    headers: { Authorization: `Bearer ${data.token}` },
    timeout: '15s',
    responseType: 'text',
  });
  check(res, {
    'admitted_or_throttled': (r) => r.status === 200 || r.status === 429,
  });
}
