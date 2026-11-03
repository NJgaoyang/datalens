import { getApi } from './http';
export interface AuditEvent { id: number; actorId: number|null; action: string; resourceType: string; resourceId: string|null; outcome: string; requestId: string; occurredAt: string }
export const listAuditEvents = async (page = 1, signal?: AbortSignal) => (await getApi<{items:AuditEvent[];total:number;page:number;size:number}>(`/api/v1/audit-events?page=${page}&size=20`,signal)).data;
