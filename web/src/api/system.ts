import { ApiError, getApi } from './http';

export interface SystemInfo {
  name: string;
  version: string;
  stage: string;
  enabledModules: string[];
}

export async function loadSystemInfo(signal?: AbortSignal): Promise<SystemInfo> {
  const { data } = await getApi<unknown>('/api/v1/system/info', signal);
  if (typeof data !== 'object' || data === null) throw new ApiError('系统信息格式错误', 'INVALID_RESPONSE');
  const info = data as Partial<SystemInfo>;
  if (typeof info.name !== 'string' || typeof info.version !== 'string'
      || typeof info.stage !== 'string' || !Array.isArray(info.enabledModules)
      || !info.enabledModules.every((value) => typeof value === 'string')) {
    throw new ApiError('系统信息格式错误', 'INVALID_RESPONSE');
  }
  return info as SystemInfo;
}

export async function loadReadiness(signal?: AbortSignal): Promise<boolean> {
  const response = await fetch('/actuator/health/readiness', {
    credentials: 'same-origin',
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10_000)]) : AbortSignal.timeout(10_000),
  });
  const data: unknown = await response.json();
  return response.ok && typeof data === 'object' && data !== null
    && (data as Record<string, unknown>).status === 'UP';
}
