import { afterEach, expect, it, vi } from 'vitest';
import { listAuditEvents } from './audit';
afterEach(() => vi.unstubAllGlobals());
it('reads paginated server-filtered audit events without requesting sensitive payloads', async () => {
 const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'OK',message:'ok',data:{items:[],total:0,page:2,size:20},requestId:'audit'})));vi.stubGlobal('fetch',fetch);
 await listAuditEvents(2);expect(fetch.mock.calls[0]?.[0]).toBe('/api/v1/audit-events?page=2&size=20');
});
