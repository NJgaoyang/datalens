import { downloadApi, getApi, requestApi } from './http';
export type ExportStatus = 'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'INTERRUPTED';
export interface ExportJob { id: string; chartId: string; taskId: string; status: ExportStatus; errorCode: string|null; expiresAt: string }
export interface ExportGrant { userId: number; enabled: boolean }
const root = '/api/v1/exports';
export const exportPending = (status: ExportStatus) => status === 'QUEUED' || status === 'RUNNING';
export const exportLabels: Record<ExportStatus,string> = {QUEUED:'排队中',RUNNING:'生成文件中',SUCCEEDED:'文件已生成',FAILED:'导出失败',INTERRUPTED:'导出已中断'};
export const exportPermission = async (chartId: string, signal?: AbortSignal) => (await getApi<{allowed:boolean}>(`${root}/charts/${encodeURIComponent(chartId)}/permission`,signal)).data;
export const createExport = async (chartId: string, taskId: string, idempotencyKey: string, signal?: AbortSignal) => (await requestApi<ExportJob>(root,'POST',{chartId,taskId,idempotencyKey},signal)).data;
export const readExport = async (id: string, signal?: AbortSignal) => (await getApi<ExportJob>(`${root}/${encodeURIComponent(id)}`,signal)).data;
export const downloadExport = (id: string, signal?: AbortSignal) => downloadApi(`${root}/${encodeURIComponent(id)}/download`,signal);
export const setExportGrant = async (chartId: string, userId: number, expectedChartVersion: number, enabled: boolean, signal?: AbortSignal) => (await requestApi<unknown>(`${root}/charts/${encodeURIComponent(chartId)}/grant`,'PUT',{userId,expectedChartVersion,enabled},signal)).data;
export const listExportGrants = async (chartId: string, signal?: AbortSignal) => (await getApi<ExportGrant[]>(`${root}/charts/${encodeURIComponent(chartId)}/grants`,signal)).data;
