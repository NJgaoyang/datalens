import { getApi, requestApi } from './http';
import { validateResult, type Parameter, type QueryResult, type QueryTask } from './queries';
export const chartTypes = ['TABLE', 'KPI', 'BAR', 'LINE', 'PIE'] as const;
export type ChartType = typeof chartTypes[number];
export interface ChartSpec { type: ChartType; dimension: string|null; measures: string[] }
export interface ChartDefinition { subjectId: string; sql: string; parameters: Parameter[]; timeRule: { kind: 'NONE'|'LAST_DAYS'; days?: number|null; timeZone?: string|null; startIndex?: number|null; endIndex?: number|null }; metricDescription: string; spec: ChartSpec }
export interface Preview { id: string; definition: ChartDefinition; taskId: string }
export interface ChartSummary { id: string; name: string; ownerId: number; version: number; revisionId: string; updatedAt: string }
export interface SavedChart { id: string; name: string; ownerId: number; version: number; revisionId: string; definition: ChartDefinition; lastSuccessAt: string|null; latestTaskId?: string|null }
const root = '/api/v1/charts';
export const listCharts = async (signal?: AbortSignal, page = 1) => (await getApi<{items: ChartSummary[]; total: number; page: number; size: number}>(`${root}?page=${page}&size=20`, signal)).data;
export const readChart = async (id: string, signal?: AbortSignal) => (await getApi<SavedChart>(`${root}/${encodeURIComponent(id)}`, signal)).data;
export const createPreview = async (definition: ChartDefinition, idempotencyKey: string, signal?: AbortSignal) => (await requestApi<Preview>(`${root}/previews`, 'POST', { definition, idempotencyKey }, signal)).data;
export const readPreview = async (id: string, signal?: AbortSignal) => (await getApi<Preview>(`${root}/previews/${encodeURIComponent(id)}`, signal)).data;
export const previewResult = async (id: string, signal?: AbortSignal) => validateResult((await getApi<QueryResult>(`${root}/previews/${encodeURIComponent(id)}/result`, signal)).data);
export const saveChart = async (name: string, previewId: string, signal?: AbortSignal) => (await requestApi<ChartSummary>(root, 'POST', { name, previewId }, signal)).data;
export const publishRevision = async (id: string, name: string, previewId: string, expectedVersion: number, signal?: AbortSignal) => (await requestApi<ChartSummary>(`${root}/${encodeURIComponent(id)}/revisions`, 'POST', { name, previewId, expectedVersion }, signal)).data;
export const refreshChart = async (id: string, idempotencyKey: string, signal?: AbortSignal) => (await requestApi<QueryTask>(`${root}/${encodeURIComponent(id)}/refreshes`, 'POST', { idempotencyKey }, signal)).data;

export const refreshResult = async (chartId: string, taskId: string, signal?: AbortSignal) => validateResult((await getApi<QueryResult>(`${root}/${encodeURIComponent(chartId)}/refreshes/${encodeURIComponent(taskId)}/result`, signal)).data);
