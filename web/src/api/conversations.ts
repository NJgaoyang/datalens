import { ApiError, getApi, requestApi } from './http';
import { chartTypes, type ChartSpec, type ChartSummary } from './charts';
import { validateResult, type QueryResult } from './queries';

export const turnStatuses = ['PLANNING', 'CLARIFICATION_REQUIRED', 'QUERYING', 'READY', 'NO_DATA', 'FAILED', 'INTERRUPTED'] as const;
export type TurnStatus = typeof turnStatuses[number];
export interface ConversationTimeRule { kind: 'NONE'|'LAST_DAYS'|'LAST_MONTH'; days?: number|null; timeZone?: string|null; startIndex?: number|null; endIndex?: number|null }
export interface ConversationTurn {
  id: string; sequence: number; question: string; status: TurnStatus; clarification: string|null;
  metricDescription: string|null; previewId: string|null; taskId: string|null; chartId: string|null;
  errorCode: string|null; createdAt: string; queryTime: string|null; truncated: boolean|null; spec: ChartSpec|null; timeRule: ConversationTimeRule|null;
}
export interface ConversationSummary { id: string; subjectId: string; title: string; version: number }
export interface Conversation extends ConversationSummary { turns: ConversationTurn[] }
export const turnStatusLabels: Record<TurnStatus, string> = { PLANNING: '正在理解问题', CLARIFICATION_REQUIRED: '需要澄清', QUERYING: '正在安全查询', READY: '结果预览', NO_DATA: '没有匹配数据', FAILED: '本轮失败', INTERRUPTED: '本轮已中断' };
export const isTurnPending = (status: TurnStatus) => status === 'PLANNING' || status === 'QUERYING';
const root = '/api/v1/conversations';
const invalid = () => new ApiError('会话响应不符合接口约定', 'INVALID_RESPONSE');
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const nullableString = (value: unknown): value is string|null => value === null || typeof value === 'string';
function summary(value: unknown): ConversationSummary {
  if (!record(value) || typeof value.id !== 'string' || !value.id || typeof value.subjectId !== 'string' || !value.subjectId || typeof value.title !== 'string' || !Number.isSafeInteger(value.version) || Number(value.version) < 0) throw invalid();
  return { id: value.id, subjectId: value.subjectId, title: value.title, version: Number(value.version) };
}
export function validateTurn(value: unknown): ConversationTurn {
  if (!record(value) || typeof value.id !== 'string' || !value.id || !Number.isSafeInteger(value.sequence) || Number(value.sequence) < 1 || typeof value.question !== 'string' || !turnStatuses.includes(value.status as TurnStatus) || typeof value.createdAt !== 'string' || !nullableString(value.queryTime) || (value.truncated !== null && typeof value.truncated !== 'boolean')) throw invalid();
  for (const field of ['clarification', 'metricDescription', 'previewId', 'taskId', 'chartId', 'errorCode']) if (!nullableString(value[field])) throw invalid();
  let spec: ChartSpec|null = null;
  if (value.spec !== null) {
    const candidate = value.spec;
    if (!record(candidate) || !chartTypes.includes(candidate.type as ChartSpec['type']) || !nullableString(candidate.dimension) || !Array.isArray(candidate.measures) || candidate.measures.length > 20 || !candidate.measures.every(item => typeof item === 'string')) throw invalid();
    spec = { type: candidate.type as ChartSpec['type'], dimension: candidate.dimension, measures: [...candidate.measures] };
  }
  let timeRule: ConversationTimeRule|null = null;
  if (value.timeRule !== null) {
    const candidate = value.timeRule;
    if (!record(candidate) || !['NONE', 'LAST_DAYS', 'LAST_MONTH'].includes(String(candidate.kind)) || (candidate.timeZone !== undefined && !nullableString(candidate.timeZone))) throw invalid();
    for (const field of ['days', 'startIndex', 'endIndex']) if (candidate[field] !== undefined && candidate[field] !== null && !Number.isSafeInteger(candidate[field])) throw invalid();
    timeRule = { kind: candidate.kind as ConversationTimeRule['kind'], days: candidate.days as number|null|undefined, timeZone: candidate.timeZone as string|null|undefined, startIndex: candidate.startIndex as number|null|undefined, endIndex: candidate.endIndex as number|null|undefined };
  }
  if (['READY', 'NO_DATA'].includes(String(value.status)) && (!spec || !timeRule || !value.previewId || !value.taskId)) throw invalid();
  return { id: value.id, sequence: Number(value.sequence), question: value.question, status: value.status as TurnStatus, clarification: value.clarification as string|null, metricDescription: value.metricDescription as string|null, previewId: value.previewId as string|null, taskId: value.taskId as string|null, chartId: value.chartId as string|null, errorCode: value.errorCode as string|null, createdAt: value.createdAt, queryTime: value.queryTime, truncated: value.truncated as boolean|null, spec, timeRule };
}
export function validateConversation(value: unknown): Conversation {
  const metadata = summary(value);
  if (!record(value) || !Array.isArray(value.turns) || value.turns.length > 1000) throw invalid();
  const turns = value.turns.map(validateTurn);
  if (new Set(turns.map(turn => turn.id)).size !== turns.length || turns.some((turn, index) => index > 0 && turn.sequence <= turns[index - 1]!.sequence)) throw invalid();
  return { ...metadata, turns };
}
export function describeTimeRule(rule: ConversationTimeRule|null) {
  if (!rule) return '等待确认时间范围';
  if (rule.kind === 'LAST_MONTH') return `上一个完整自然月 · 业务时区：${rule.timeZone || '未提供'}`;
  if (rule.kind === 'LAST_DAYS') return `最近 ${rule.days} 天（含今天） · 业务时区：${rule.timeZone || '未提供'}`;
  return `固定时间范围${rule.timeZone ? ` · 业务时区：${rule.timeZone}` : ' · 时区以已审核指标定义为准'}`;
}
export const listConversations = async (signal?: AbortSignal): Promise<ConversationSummary[]> => {
  const { data } = await getApi<unknown>(root, signal);
  if (!Array.isArray(data) || data.length > 1000) throw invalid();
  return data.map(summary);
};
export const createConversation = async (subjectId: string, title: string, idempotencyKey: string, signal?: AbortSignal) => summary((await requestApi(root, 'POST', { subjectId, title, idempotencyKey }, signal)).data);
export const readConversation = async (id: string, signal?: AbortSignal) => validateConversation((await getApi(`${root}/${encodeURIComponent(id)}`, signal)).data);
export const submitTurn = async (id: string, question: string, expectedVersion: number, idempotencyKey: string, signal?: AbortSignal) => validateTurn((await requestApi(`${root}/${encodeURIComponent(id)}/turns`, 'POST', { question, expectedVersion, idempotencyKey }, signal, 150_000)).data);
export const readTurn = async (id: string, turnId: string, signal?: AbortSignal) => validateTurn((await getApi(`${root}/${encodeURIComponent(id)}/turns/${encodeURIComponent(turnId)}`, signal)).data);
export const readTurnResult = async (id: string, turnId: string, signal?: AbortSignal) => validateResult((await getApi<QueryResult>(`${root}/${encodeURIComponent(id)}/turns/${encodeURIComponent(turnId)}/result`, signal)).data);
export const saveTurnChart = async (id: string, turnId: string, name: string, signal?: AbortSignal): Promise<ChartSummary> => {
  const { data } = await requestApi<ChartSummary>(`${root}/${encodeURIComponent(id)}/turns/${encodeURIComponent(turnId)}/save`, 'POST', { name, confirmation: true }, signal);
  if (!data || typeof data.id !== 'string' || !data.id || typeof data.name !== 'string' || data.visibility !== 'PRIVATE') throw invalid();
  return data;
};
