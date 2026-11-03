import { afterEach, expect, it, vi } from 'vitest';
import { clearCsrf } from './http';
import { listSources, metadata, parseFields, parseUserIds, saveSource, sourceAction, sourcePayload } from './datasources';
const input = { name: 'Warehouse', host: '10.1.2.3', port: 9030, catalog: 'default_catalog', database: 'sales', username: 'reader', password: '', enabled: true, tables: ['orders'] };
const response = (data: unknown, status = 200, code = 'OK') => new Response(JSON.stringify({ code, message: 'result', data, requestId: 'sources-test' }), { status });
afterEach(() => { clearCsrf(); vi.unstubAllGlobals(); });
it('projects only explicit source input and preserves blank update password', async () => {
  const mock = vi.fn().mockResolvedValueOnce(response({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(response({ id: 'source' })); vi.stubGlobal('fetch', mock);
  await saveSource({ ...input, password: '', secret: 'never-echo' } as typeof input, 'source');
  expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/admin/sources/source');
  expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual(input);
  expect(mock.mock.calls[1]?.[1].headers['X-CSRF-TOKEN']).toBe('csrf');
});
it('rejects JDBC URLs, malformed endpoints and wildcard tables before sending', () => {
  for (const host of ['jdbc:mysql://host', 'https://host', 'localhost', '256.1.1.1']) expect(() => sourcePayload({ ...input, host })).toThrow();
  expect(() => sourcePayload({ ...input, tables: ['*'] })).toThrow();
  expect(() => sourcePayload({ ...input, tables: [] })).toThrow();
});
it('parses exact fields and positive user IDs without accepting wildcard or unsafe numbers', () => {
  expect(parseFields('orders.id, orders.id\norders.total')).toEqual([{ tableName: 'orders', columnName: 'id' }, { tableName: 'orders', columnName: 'total' }]);
  expect(() => parseFields('orders.*')).toThrow();
  expect(parseUserIds('2, 3 2')).toEqual([2, 3]);
  for (const value of ['-1', '1.5', '0', '1e2', '9007199254740992']) expect(() => parseUserIds(value)).toThrow();
});
it('uses permission-filtered subject metadata endpoint for ordinary users', async () => {
  const mock = vi.fn().mockResolvedValue(response([])); vi.stubGlobal('fetch', mock);
  expect(await metadata('subject-id')).toEqual([]);
  expect(mock.mock.calls[0]?.[0]).toBe('/api/v1/subjects/subject-id/metadata');
});
it('rejects malformed lists and does not claim success from a malformed connection test', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({})));
  await expect(listSources()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(response({ success: false })));
  await expect(sourceAction('source', 'test')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('does not replace failed harvest with a successful fixture or auto-retry', async () => {
  const mock = vi.fn().mockResolvedValueOnce(response({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(response(null, 503, 'SOURCE_CONNECTION_FAILED')); vi.stubGlobal('fetch', mock);
  await expect(sourceAction('source', 'harvest')).rejects.toMatchObject({ code: 'SOURCE_CONNECTION_FAILED' });
  expect(mock).toHaveBeenCalledTimes(2);
});
