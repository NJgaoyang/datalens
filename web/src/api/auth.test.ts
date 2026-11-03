import { afterEach, expect, it, vi } from 'vitest';
import { clearSession, loadSession, login, logout, session } from './auth';
const user = { id: 1, username: 'admin', role: 'SUPER_ADMIN', enabled: true };
const response = (data: unknown, status = 200, code = 'OK') => new Response(JSON.stringify({ code, message: 'result', data, requestId: 'test-auth' }), { status });
afterEach(() => { clearSession(); vi.unstubAllGlobals(); });
it('refreshes CSRF after login and logout and never stores credentials', async () => {
  const mock = vi.fn()
    .mockResolvedValueOnce(response({ token: 'anonymous', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(response(user))
    .mockResolvedValueOnce(response({ token: 'authenticated', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(response(null))
    .mockResolvedValueOnce(response({ token: 'logged-out', headerName: 'X-CSRF-TOKEN' }));
  vi.stubGlobal('fetch', mock);
  await login('admin', 'example-password');
  expect(session.user).toEqual(user);
  expect(mock.mock.calls.map(call => call[0])).toEqual(['/api/v1/auth/csrf', '/api/v1/auth/login', '/api/v1/auth/csrf']);
  await logout();
  expect(session.user).toBeNull();
  expect(mock.mock.calls[3]?.[1].headers['X-CSRF-TOKEN']).toBe('authenticated');
  expect(mock.mock.calls[4]?.[0]).toBe('/api/v1/auth/csrf');
  expect(Object.keys(session).sort()).toEqual(['checked', 'error', 'user']);
});
it('rejects a disabled or malformed identity rather than trusting it', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...user, enabled: false })));
  await expect(loadSession()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  expect(session.user).toBeNull();
});
it('keeps incorrect login an error with no authenticated identity', async () => {
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(response({ token: 'anonymous', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(response(null, 401, 'UNAUTHORIZED')));
  await expect(login('admin', 'wrong-password')).rejects.toMatchObject({ status: 401 });
  expect(session.user).toBeNull();
});
