import { ApiError, getApi, requestApi } from './http';
export const statuses = ['QUEUED', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'CANCEL_UNCONFIRMED', 'RECOVERY_UNCONFIRMED'] as const;
export type QueryStatus = typeof statuses[number];
export interface QueryTask { id: string; subjectId: string; status: QueryStatus; errorCode: string|null; createdAt: string; finishedAt: string|null; durationMs: number|null; truncated: boolean }
export interface QueryResult { columns: { name: string; jdbcType: string }[]; rows: (string|null)[][]; truncated: boolean; rowLimit: number }
export interface Parameter { type: 'STRING'|'DECIMAL'|'INTEGER'|'BOOLEAN'|'DATE'|'TIMESTAMP'|'NULL'; value: string|null }
export interface QueryInput { subjectId: string; sql: string; parameters: Parameter[]; idempotencyKey: string }
export const statusLabels: Record<QueryStatus, string> = { QUEUED: '排队中', RUNNING: '执行中', CANCELLING: '取消中，等待数据库确认', SUCCEEDED: '查询成功', FAILED: '查询失败', CANCELLED: '已取消', TIMED_OUT: '执行超时', CANCEL_UNCONFIRMED: '取消未确认', RECOVERY_UNCONFIRMED: '重启后执行状态未确认' };
export const isPending = (status: QueryStatus) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(status);
export const isUnconfirmed = (status: QueryStatus) => ['CANCEL_UNCONFIRMED', 'RECOVERY_UNCONFIRMED'].includes(status);
export function parseParameters(text: string): Parameter[] {
  let values: unknown;
  try { values = JSON.parse(text); } catch { throw new Error('参数必须是 JSON 数组'); }
  if (!Array.isArray(values) || values.length > 100) throw new Error('参数必须是数组，最多 100 项');
  return values.map(value => {
    if (!value || typeof value !== 'object' || !['STRING','DECIMAL','INTEGER','BOOLEAN','DATE','TIMESTAMP','NULL'].includes(value.type)
      || (value.value !== null && typeof value.value !== 'string') || (typeof value.value === 'string' && value.value.length > 4000)
      || (value.type === 'NULL' && value.value !== null)) throw new Error('每项参数需包含 type 和字符串 value；NULL 类型的 value 应为 null');
    return { type: value.type, value: value.value };
  });
}
function task(value: QueryTask): QueryTask {
  if (!value || typeof value.id !== 'string' || !value.id || typeof value.subjectId !== 'string' || !statuses.includes(value.status)) throw new ApiError('查询任务响应无效', 'INVALID_RESPONSE');
  return value;
}
export function validateResult(value: QueryResult): QueryResult {
  if (!value || !Array.isArray(value.columns) || value.columns.length > 1000 || !value.columns.every(column => column && typeof column.name === 'string' && typeof column.jdbcType === 'string')
    || !Array.isArray(value.rows) || value.rows.length > 1000 || !Number.isInteger(value.rowLimit) || value.rowLimit < 1 || value.rowLimit > 1000 || value.rows.length > value.rowLimit
    || typeof value.truncated !== 'boolean' || !value.rows.every(row => Array.isArray(row) && row.length === value.columns.length && row.every(cell => cell === null || typeof cell === 'string'))) throw new ApiError('查询结果格式或大小不符合约定', 'INVALID_RESPONSE');
  return value;
}
const root = '/api/v1/query-tasks';
export const submitQuery = async (input: QueryInput, signal?: AbortSignal) => task((await requestApi<QueryTask>(root, 'POST', { subjectId: input.subjectId, sql: input.sql, parameters: input.parameters, idempotencyKey: input.idempotencyKey }, signal)).data);
export const readQuery = async (id: string, signal?: AbortSignal) => task((await getApi<QueryTask>(`${root}/${encodeURIComponent(id)}`, signal)).data);
export const readResult = async (id: string, signal?: AbortSignal) => validateResult((await getApi<QueryResult>(`${root}/${encodeURIComponent(id)}/result`, signal)).data);
export const cancelQuery = async (id: string, signal?: AbortSignal) => task((await requestApi<QueryTask>(`${root}/${encodeURIComponent(id)}/cancel`, 'POST', undefined, signal)).data);
