import { afterEach, describe, expect, it, vi } from 'vitest';
import { getApi } from './http';
import { loadSystemInfo } from './system';

const envelope = { code: 'OK', message: '成功', data: { name: 'DataLens', version: '0.1.0', stage: 'M00', enabledModules: ['foundation'] }, requestId: 'test-id', timestamp: '2026-11-03T00:00:00Z' };
afterEach(() => vi.unstubAllGlobals());

describe('API contracts', () => {
  it('reads real envelope shape and uses same-origin credentials', async () => {
    const mock = vi.fn().mockResolvedValue(new Response(JSON.stringify(envelope), { status: 200 }));
    vi.stubGlobal('fetch', mock);
    expect(await loadSystemInfo()).toEqual(envelope.data);
    expect(mock).toHaveBeenCalledWith('/api/v1/system/info', expect.objectContaining({ credentials: 'same-origin' }));
  });
  it('does not turn an authorization error into success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...envelope, code: 'FORBIDDEN', message: '无权访问', data: null }), { status: 403 })));
    await expect(getApi('/api/v1/charts')).rejects.toMatchObject({ code: 'FORBIDDEN', requestId: 'test-id' });
  });
  it('rejects HTML reverse-proxy failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>failure</html>', { status: 502 })));
    await expect(loadSystemInfo()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
  it('does not invent success when offline', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
    await expect(loadSystemInfo()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });
  it('rejects malformed system data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...envelope, data: { name: 'DataLens' } }))));
    await expect(loadSystemInfo()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
  it('does not send requests to external model or database URLs', async () => {
    const mock = vi.fn();
    vi.stubGlobal('fetch', mock);
    await expect(getApi('https://example.com/api/v1/data')).rejects.toMatchObject({ code: 'INVALID_PATH' });
    expect(mock).not.toHaveBeenCalled();
  });
});
