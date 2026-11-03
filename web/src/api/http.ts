export interface ApiResponse<T> {
  code: string;
  message: string;
  data: T;
  requestId: string;
  timestamp?: string;
}

export class ApiError extends Error {
  constructor(message: string, readonly code: string, readonly requestId?: string, readonly status?: number) {
    super(message);
    this.name = 'ApiError';
  }
}

let csrf: { token: string; headerName: string } | null = null;
let onUnauthorized: (() => void) | undefined;
export function setUnauthorizedHandler(handler: () => void) { onUnauthorized = handler; }
export function clearCsrf() { csrf = null; }

function isEnvelope(value: unknown): value is ApiResponse<unknown> {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.code === 'string' && typeof candidate.message === 'string'
    && typeof candidate.requestId === 'string' && 'data' in candidate;
}

export async function refreshCsrf() {
  csrf = null;
  const { data } = await getApi<{ token: string; headerName: string }>('/api/v1/auth/csrf');
  if (!data || typeof data.token !== 'string' || !data.token || typeof data.headerName !== 'string' || !/^[-a-zA-Z0-9]+$/.test(data.headerName)) {
    throw new ApiError('安全令牌响应无效，请刷新重试', 'INVALID_RESPONSE');
  }
  csrf = data;
}

export async function requestApi<T>(path: string, method = 'GET', data?: unknown, signal?: AbortSignal): Promise<ApiResponse<T>> {
  if (!path.startsWith('/api/v1/') || path.includes('\\')) throw new ApiError('只允许同源业务接口', 'INVALID_PATH');
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') {
    if (!csrf) await refreshCsrf();
    headers[csrf!.headerName] = csrf!.token;
    if (data !== undefined) headers['Content-Type'] = 'application/json';
  }
  let response: Response;
  try {
    response = await fetch(path, {
      method, credentials: 'same-origin', headers,
      body: data === undefined ? undefined : JSON.stringify(data),
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10_000)]) : AbortSignal.timeout(10_000),
    });
  } catch (cause) {
    if (signal?.aborted) throw cause;
    throw new ApiError('无法连接后端服务，请检查服务是否启动', 'NETWORK_ERROR');
  }
  if (response.status === 401 && path !== '/api/v1/auth/login' && path !== '/api/v1/auth/me') { clearCsrf(); onUnauthorized?.(); }
  let body: unknown;
  try { body = await response.json(); }
  catch { throw new ApiError('服务返回了无法识别的响应', 'INVALID_RESPONSE', response.headers.get('X-Request-ID') ?? undefined, response.status); }
  if (!isEnvelope(body)) throw new ApiError('响应不符合接口契约', 'INVALID_RESPONSE', undefined, response.status);
  if (!response.ok || body.code !== 'OK') {
    if (response.status === 403) clearCsrf();
    throw new ApiError(body.message, body.code, body.requestId, response.status);
  }
  return body as ApiResponse<T>;
}
export function getApi<T>(path: string, signal?: AbortSignal) { return requestApi<T>(path, 'GET', undefined, signal); }
export function errorMessage(error: unknown) {
  return error instanceof ApiError ? `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}` : error instanceof Error ? error.message : '操作失败，请重试';
}
