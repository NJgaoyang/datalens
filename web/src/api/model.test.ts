import { afterEach, expect, it, vi } from 'vitest';
import { clearCsrf } from './http';
import { loadModelConfig, modelDefaults, modelPayload, readModelConfig, saveModelConfig } from './model';
const configured = { ...modelDefaults(), version: 3, endpoint: 'https://api.example.com/v1/chat/completions', modelId: 'test-model', credentialConfigured: true };
const envelope = (data: unknown, status = 200, code = 'OK') => new Response(JSON.stringify({ code, message: 'result', data, requestId: 'model-test' }), { status });
afterEach(() => { clearCsrf(); vi.unstubAllGlobals(); });
it('loads disabled configuration without calling a provider', async () => {
  const mock = vi.fn().mockResolvedValue(envelope(modelDefaults())); vi.stubGlobal('fetch', mock);
  expect(await loadModelConfig()).toEqual(modelDefaults()); expect(mock).toHaveBeenCalledExactlyOnceWith('/api/v1/admin/model', expect.anything());
});
it('projects configuration and preserves blank credentials with optimistic version and CSRF', async () => {
  const mock = vi.fn().mockResolvedValueOnce(envelope({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(envelope({ ...configured, version: 4 })); vi.stubGlobal('fetch', mock);
  const payload = modelPayload({ ...configured, apiKey: 'must-not-echo' } as typeof configured, '', configured);
  await saveModelConfig(payload);
  expect(payload).not.toHaveProperty('version'); expect(payload).not.toHaveProperty('credentialConfigured');
  expect(payload).toMatchObject({ expectedVersion: 3, apiKey: null });
  expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/admin/model');
  expect(mock.mock.calls[1]?.[1].headers['X-CSRF-TOKEN']).toBe('csrf');
  expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual(payload);
});
it('requires new credentials when changing endpoint and credentials when first enabling', () => {
  expect(() => modelPayload({ ...configured, endpoint: 'https://other.example.com/chat' }, '', configured)).toThrow('新的 API Key');
  expect(() => modelPayload({ ...configured, enabled: true }, '', { ...configured, credentialConfigured: false })).toThrow('配置 API Key');
  expect(modelPayload(modelDefaults(), null, modelDefaults()).apiKey).toBeNull();
});
it('rejects unsafe endpoints and out-of-bound controls', () => {
  for (const endpoint of ['http://host/chat', 'https://user:pass@host/chat', 'https://host/chat?key=secret', 'https://host/chat#secret', 'garbage']) expect(() => modelPayload({ ...configured, endpoint }, 'fixture-key', configured)).toThrow();
  for (const change of [{ timeoutSeconds: 61 }, { maxInputChars: 255 }, { maxOutputTokens: 4097 }, { maxRepairs: 2 }, { dailyTokenBudget: 10000001 }, { maxRepairs: 0.5 }]) expect(() => modelPayload({ ...configured, ...change }, '', configured)).toThrow();
});
it('discards unexpected response credentials and rejects malformed responses', () => {
  expect(readModelConfig({ ...configured, apiKey: 'never-render', secret: 'never-render' })).toEqual(configured);
  for (const value of [null, {}, { ...configured, enabled: 'true' }, { ...configured, version: -1 }, { ...configured, provider: 'OTHER' }]) expect(() => readModelConfig(value)).toThrow();
});
it('does not retry or manufacture success on optimistic conflicts', async () => {
  const mock = vi.fn().mockResolvedValueOnce(envelope({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(envelope(null, 409, 'CONFLICT')); vi.stubGlobal('fetch', mock);
  await expect(saveModelConfig(modelPayload(configured, '', configured))).rejects.toMatchObject({ code: 'CONFLICT' }); expect(mock).toHaveBeenCalledTimes(2);
});
