// 全链路压测公共模块（k6）
// 提供网关/Supervisor 基础地址与登录取 token 的能力。
// 通过环境变量覆盖：GATEWAY_BASE / SUPERVISOR_BASE
// 在 docker-compose host 网络模式下指向宿主 localhost；否则用 host.docker.internal。

import http from 'k6/http';

export const GATEWAY_BASE = __ENV.GATEWAY_BASE || 'http://host.docker.internal:8080';
export const SUPERVISOR_BASE = __ENV.SUPERVISOR_BASE || 'http://host.docker.internal:8081';

/**
 * 登录并取 JWT（admin/admin123 映射为 supervisor 角色）。
 * 返回的 token 用于各场景的 Authorization: Bearer 头。
 */
export function login(baseUrl, username, password) {
  const res = http.post(
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' }
  );
  if (res.status !== 200) {
    throw new Error(`login failed: ${res.status} ${res.body}`);
  }
  return res.json('token');
}
