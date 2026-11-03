import { afterEach, expect, it, vi } from 'vitest';
import { clearCsrf } from './http';
import { createConversation, describeTimeRule, isTurnPending, listConversations, readConversation, readTurn, readTurnResult, saveTurnChart, submitTurn, validateConversation, validateTurn } from './conversations';
const base = { id: 'c1', subjectId: 'subject-1', title: '月度销售', version: 1, turns: [] };
const turn = { id: 't1', sequence: 1, question: '销售额', status: 'READY', clarification: null, metricDescription: '已审核口径', previewId: 'p1', taskId: 'q1', chartId: null, errorCode: null, createdAt: '2026-11-03T00:00:00Z', queryTime: '2026-11-03T00:00:01Z', truncated: false, spec: { type: 'TABLE', dimension: null, measures: [] }, timeRule: { kind: 'LAST_MONTH', days: null, timeZone: 'Asia/Shanghai', startIndex: 0, endIndex: 1 } };
const envelope = (data: unknown, status = 200, code = 'OK') => new Response(JSON.stringify({ code, message: 'result', data, requestId: 'conversation-test' }), { status });
const csrf = () => envelope({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
afterEach(() => { clearCsrf(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it('creates a private conversation with explicit subject, title and actor-scoped idempotency key', async () => {
  const mock = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(envelope(base)); vi.stubGlobal('fetch', mock);
  expect(await createConversation(base.subjectId, base.title, 'intent-1')).toEqual({ id: base.id, subjectId: base.subjectId, title: base.title, version: base.version });
  expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/conversations');
  expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual({ subjectId: base.subjectId, title: base.title, idempotencyKey: 'intent-1' });
  expect(mock.mock.calls[1]?.[1].headers['X-CSRF-TOKEN']).toBe('csrf');
  expect(mock.mock.calls[1]?.[1].credentials).toBe('same-origin');
});
it('lists and reads only session-scoped conversation endpoints without user IDs', async () => {
  const mock = vi.fn().mockResolvedValueOnce(envelope([base])).mockResolvedValueOnce(envelope({ ...base, turns: [turn] })); vi.stubGlobal('fetch', mock);
  expect(await listConversations()).toEqual([{ id: base.id, subjectId: base.subjectId, title: base.title, version: base.version }]);
  expect((await readConversation('a/b')).turns[0]?.id).toBe('t1');
  expect(mock.mock.calls.map(call => call[0])).toEqual(['/api/v1/conversations', '/api/v1/conversations/a%2Fb']);
});
it('submits the exact follow-up and optimistic version without browser SQL or context replay', async () => {
  const mock = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(envelope(turn)); vi.stubGlobal('fetch', mock);
  await submitTurn('c1', '改成上个月', 9, 'turn-key');
  expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual({ question: '改成上个月', expectedVersion: 9, idempotencyKey: 'turn-key' });
});
it('polls turn status and validates result cell precision', async () => {
  const result = { columns: [{ name: 'amount', jdbcType: 'DECIMAL' }], rows: [['9007199254740993.25']], rowLimit: 1000, truncated: false };
  const mock = vi.fn().mockResolvedValueOnce(envelope(turn)).mockResolvedValueOnce(envelope(result)); vi.stubGlobal('fetch', mock);
  expect((await readTurn('c/1', 't/1')).status).toBe('READY');
  expect(await readTurnResult('c/1', 't/1')).toEqual(result);
  expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/conversations/c%2F1/turns/t%2F1/result');
});
it('saves only through the explicit confirmation endpoint without a client definition', async () => {
  const saved = { id: 'chart-1', name: '月度图表', visibility: 'PRIVATE' };
  const mock = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(envelope(saved)); vi.stubGlobal('fetch', mock);
  expect(await saveTurnChart('c1', 't1', '月度图表')).toEqual(saved);
  expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual({ name: '月度图表', confirmation: true });
});
it('never silently retries a version conflict or reports a successful save', async () => {
  const mock = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(envelope(null, 409, 'CONVERSATION_VERSION_CONFLICT')); vi.stubGlobal('fetch', mock);
  await expect(submitTurn('c1', '销售额', 1, 'key')).rejects.toMatchObject({ code: 'CONVERSATION_VERSION_CONFLICT' });
  expect(mock).toHaveBeenCalledTimes(2);
});
it('rejects malformed turns, incomplete ready evidence and unknown status', () => {
  for (const changed of [{ status: 'SUCCESS' }, { sequence: 0 }, { spec: null }, { previewId: null }, { taskId: null }, { truncated: 'false' }, { queryTime: 7 }, { timeRule: { kind: 'UNREVIEWED' } }, { spec: { type: 'HTML', dimension: null, measures: [] } }]) expect(() => validateTurn({ ...turn, ...changed })).toThrow();
});
it('projects known fields and rejects duplicate or unordered persisted turns', () => {
  expect(validateTurn({ ...turn, providerKey: 'do-not-copy', narrative: 'do-not-copy' })).not.toHaveProperty('providerKey');
  expect(validateTurn({ ...turn, narrative: 'do-not-copy' })).not.toHaveProperty('narrative');
  for (const turns of [[turn, turn], [{ ...turn, id: 't2', sequence: 2 }, turn]]) expect(() => validateConversation({ ...base, turns })).toThrow();
});
it('fails closed on malformed result and non-private save response', async () => {
  const mock = vi.fn().mockResolvedValueOnce(envelope({ columns: [], rows: [[42]], rowLimit: 1000, truncated: false })).mockResolvedValueOnce(csrf()).mockResolvedValueOnce(envelope({ id: 'chart-1', name: 'x', visibility: 'ALL' })); vi.stubGlobal('fetch', mock);
  await expect(readTurnResult('c1', 't1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  await expect(saveTurnChart('c1', 't1', 'x')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('distinguishes pending, no-data and interruption and labels business-calendar time', () => {
  expect(isTurnPending('PLANNING')).toBe(true); expect(isTurnPending('QUERYING')).toBe(true);
  for (const status of ['CLARIFICATION_REQUIRED', 'NO_DATA', 'INTERRUPTED', 'FAILED', 'READY'] as const) expect(isTurnPending(status)).toBe(false);
  expect(describeTimeRule({ kind: 'LAST_MONTH', timeZone: 'Asia/Shanghai' })).toContain('上一个完整自然月');
  expect(describeTimeRule({ kind: 'LAST_MONTH', timeZone: 'Asia/Shanghai' })).toContain('Asia/Shanghai');
});

it('allows a bounded model budget for turn submission while retaining normal request limits', async () => {
  const timeout = vi.spyOn(AbortSignal, 'timeout');
  const mock = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(envelope(turn)); vi.stubGlobal('fetch', mock);
  await submitTurn('c1', '销售额', 1, 'key');
  expect(timeout).toHaveBeenCalledWith(10_000);
  expect(timeout).toHaveBeenCalledWith(150_000);
});
