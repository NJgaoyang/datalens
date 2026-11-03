export interface ApiResponse<T> {
  code: string;
  message: string;
  data: T;
  requestId: string;
  timestamp: string;
}

export class ApiError extends Error {
  constructor(message: string, readonly code: string, readonly requestId?: string) {
    super(message);
    this.name = 'ApiError';
  }
}

function isEnvelope(value: unknown): value is ApiResponse<unknown> {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.code === 'string' && typeof candidate.message === 'string'
    && typeof candidate.requestId === 'string' && 'data' in candidate;
}

export async function getApi<T>(path: string, signal?: AbortSignal): Promise<ApiResponse<T>> {
  if (!path.startsWith('/api/v1/')) throw new ApiError('只允许同源业务接口', 'INVALID_PATH');
  let response: Response;
  try {
    response = await fetch(path, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10_000)]) : AbortSignal.timeout(10_000),
    });
  } catch (cause) {
    if (signal?.aborted) throw cause;
    throw new ApiError('无法连接后端服务，请检查服务是否启动', 'NETWORK_ERROR');
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new ApiError('服务返回了无法识别的响应', 'INVALID_RESPONSE', response.headers.get('X-Request-ID') ?? undefined);
  }
  if (!isEnvelope(body)) throw new ApiError('响应不符合接口契约', 'INVALID_RESPONSE');
  if (!response.ok || body.code !== 'OK') throw new ApiError(body.message, body.code, body.requestId);
  return body as ApiResponse<T>;
}
