import { afterEach, expect, it, vi } from 'vitest';
import { clearCsrf } from './http';
import { cancelQuery, isPending, isUnconfirmed, parseParameters, readQuery, readResult, submitQuery, validateResult } from './queries';
const response = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'ok', data, requestId: 'query-test' }));
const task = { id: 'q1', subjectId: 's1', status: 'QUEUED', errorCode: null, createdAt: '', finishedAt: null, durationMs: null, truncated: false };
afterEach(() => { clearCsrf(); vi.unstubAllGlobals(); });
it('preserves typed decimal and bigint strings and strips unknown parameter fields', () => {
  expect(parseParameters('[{"type":"DECIMAL","value":"9007199254740993.01","userId":2},{"type":"NULL","value":null}]')).toEqual([{ type: 'DECIMAL', value: '9007199254740993.01' }, { type: 'NULL', value: null }]);
  for (const input of ['{}','invalid','[1]','[{"type":"INTEGER","value":1}]','[{"type":"NULL","value":"null"}]']) expect(() => parseParameters(input)).toThrow();
});
it('submits only subject SQL parameters and stable idempotency key with CSRF', async () => {
  const mock = vi.fn().mockResolvedValueOnce(response({ token: 'token', headerName: 'X-CSRF-TOKEN' })).mockImplementation(() => Promise.resolve(response(task))); vi.stubGlobal('fetch', mock);
  const input = { subjectId: 's1', sql: 'SELECT orders.total FROM orders', parameters: [], idempotencyKey: 'stable-key' };
  await submitQuery({ ...input, userId: 9 } as typeof input); await submitQuery(input);
  expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual(input);
  expect(mock.mock.calls[1]?.[1].headers['X-CSRF-TOKEN']).toBe('token');
  expect(mock.mock.calls[1]?.[1].body).toBe(mock.mock.calls[2]?.[1].body);
});
it('keeps cancellation and recovery unconfirmed distinct from confirmed cancellation', () => {
  expect(isPending('CANCELLING')).toBe(true); expect(isPending('CANCELLED')).toBe(false);
  expect(isUnconfirmed('CANCEL_UNCONFIRMED')).toBe(true); expect(isUnconfirmed('RECOVERY_UNCONFIRMED')).toBe(true); expect(isUnconfirmed('CANCELLED')).toBe(false);
});
it('rejects unknown task states and excessive or precision-losing result payloads', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...task, status: 'MADE_UP' })));
  await expect(readQuery('q1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  const result = { columns: [{ name: 'total', jdbcType: 'DECIMAL' }], rows: [['9007199254740993.01']], truncated: true, rowLimit: 1000 };
  expect(validateResult(result)).toEqual(result);
  for (const invalid of [{ ...result, rows: [[123]] }, { ...result, rows: Array(1001).fill(['1']) }, { ...result, rows: [[]] }]) expect(() => validateResult(invalid as typeof result)).toThrow();
});
it('uses encoded task identity for independently authorized result and cancellation endpoints', async () => {
  const mock = vi.fn().mockResolvedValueOnce(response({ token: 'token', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(response({ ...task, status: 'CANCEL_UNCONFIRMED' })).mockResolvedValueOnce(response({ columns: [], rows: [], truncated: false, rowLimit: 1000 })); vi.stubGlobal('fetch', mock);
  expect((await cancelQuery('q/1')).status).toBe('CANCEL_UNCONFIRMED'); await readResult('q/1');
  expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/query-tasks/q%2F1/cancel'); expect(mock.mock.calls[1]?.[1].body).toBeUndefined(); expect(mock.mock.calls[2]?.[0]).toBe('/api/v1/query-tasks/q%2F1/result');
});
