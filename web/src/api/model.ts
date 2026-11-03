import { ApiError, getApi, requestApi } from './http';

export interface ModelConfig {
  version: number; enabled: boolean; provider: 'OPENAI_COMPATIBLE'; endpoint: string; modelId: string;
  timeoutSeconds: number; maxInputChars: number; maxOutputTokens: number; maxRepairs: number;
  dailyTokenBudget: number; credentialConfigured: boolean;
}
export interface ModelInput extends Omit<ModelConfig, 'version' | 'credentialConfigured'> { expectedVersion: number; apiKey: string | null }
export const modelDefaults = (): ModelConfig => ({ version: 0, enabled: false, provider: 'OPENAI_COMPATIBLE', endpoint: '', modelId: '', timeoutSeconds: 30, maxInputChars: 12000, maxOutputTokens: 2000, maxRepairs: 1, dailyTokenBudget: 100000, credentialConfigured: false });

export function modelPayload(config: ModelConfig, apiKey: string | null, original: ModelConfig): ModelInput {
  const endpoint = config.endpoint.trim(), modelId = config.modelId.trim();
  if (apiKey && (!/^[!-~]+$/.test(apiKey) || apiKey.length > 4096)) throw new Error('API Key 必须为不含空格的可打印 ASCII 字符，最多 4096 字符');
  if (endpoint.length > 500 || modelId.length > 128) throw new Error('接口地址或模型标识过长');
  if (config.provider !== 'OPENAI_COMPATIBLE') throw new Error('不支持的模型提供方');
  if (endpoint) {
    let url: URL;
    try { url = new URL(endpoint); } catch { throw new Error('请输入完整 HTTPS 模型接口地址'); }
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) throw new Error('模型接口必须为无凭据、查询参数或片段的 HTTPS 地址');
  }
  if (config.enabled && (!endpoint || !modelId)) throw new Error('启用模型前必须填写接口地址和模型标识');
  if (endpoint !== original.endpoint && !apiKey) throw new Error('更换模型接口地址必须提供新的 API Key');
  if (config.enabled && !original.credentialConfigured && !apiKey) throw new Error('启用模型前必须配置 API Key');
  for (const [name, value, minimum, maximum] of [
    ['版本', original.version, 0, Number.MAX_SAFE_INTEGER], ['超时秒数', config.timeoutSeconds, 1, 60], ['输入字符上限', config.maxInputChars, 256, 24000],
    ['输出 Token 上限', config.maxOutputTokens, 128, 4096], ['修复次数', config.maxRepairs, 0, 1], ['每日 Token 预算', config.dailyTokenBudget, 1024, 10000000],
  ] as const) if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${name}必须为 ${minimum} 至 ${maximum} 的整数`);
  return { expectedVersion: original.version, enabled: config.enabled, provider: config.provider, endpoint, modelId,
    timeoutSeconds: config.timeoutSeconds, maxInputChars: config.maxInputChars, maxOutputTokens: config.maxOutputTokens,
    maxRepairs: config.maxRepairs, dailyTokenBudget: config.dailyTokenBudget, apiKey: apiKey || null };
}

export function readModelConfig(data: unknown): ModelConfig {
  if (!data || typeof data !== 'object') throw new ApiError('模型配置响应无效', 'INVALID_RESPONSE');
  const value = data as Record<string, unknown>;
  for (const key of ['version', 'timeoutSeconds', 'maxInputChars', 'maxOutputTokens', 'maxRepairs', 'dailyTokenBudget']) {
    if (!Number.isSafeInteger(value[key]) || (value[key] as number) < 0) throw new ApiError('模型配置响应无效', 'INVALID_RESPONSE');
  }
  if (typeof value.enabled !== 'boolean' || typeof value.credentialConfigured !== 'boolean' || value.provider !== 'OPENAI_COMPATIBLE' || typeof value.endpoint !== 'string' || typeof value.modelId !== 'string') throw new ApiError('模型配置响应无效', 'INVALID_RESPONSE');
  // Explicit response projection: credentials and unexpected fields never enter form state.
  return { version: value.version as number, enabled: value.enabled, provider: value.provider, endpoint: value.endpoint, modelId: value.modelId,
    timeoutSeconds: value.timeoutSeconds as number, maxInputChars: value.maxInputChars as number, maxOutputTokens: value.maxOutputTokens as number,
    maxRepairs: value.maxRepairs as number, dailyTokenBudget: value.dailyTokenBudget as number, credentialConfigured: value.credentialConfigured };
}
export const loadModelConfig = async (signal?: AbortSignal) => readModelConfig((await getApi<unknown>('/api/v1/admin/model', signal)).data);
export const saveModelConfig = async (input: ModelInput) => readModelConfig((await requestApi<unknown>('/api/v1/admin/model', 'PUT', input)).data);
