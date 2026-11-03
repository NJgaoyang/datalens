import { afterEach, expect, it, vi } from 'vitest';
import { clearCsrf, setUnauthorizedHandler } from './http';
import { createExport, readExport, downloadExport, exportPermission, setExportGrant, listExportGrants, exportPending } from './exports';
const response = (data:unknown,status=200) => new Response(JSON.stringify({code:status === 200 ? 'OK' : 'FORBIDDEN',message:'权限已撤销',data,requestId:'exports-test'}),{status});
afterEach(() => {clearCsrf();setUnauthorizedHandler(() => {});vi.unstubAllGlobals();});
it('binds creation to a chart result and stable key, never sends SQL or acting user',async () => {
 const mock = vi.fn().mockResolvedValueOnce(response({token:'csrf',headerName:'X-CSRF-TOKEN'})).mockImplementation(() => Promise.resolve(response({})));
 vi.stubGlobal('fetch',mock);await createExport('c1','t1','same-key');await createExport('c1','t1','same-key');
 expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/exports');expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual({chartId:'c1',taskId:'t1',idempotencyKey:'same-key'});expect(mock.mock.calls[1]?.[1].body).toBe(mock.mock.calls[2]?.[1].body);
});
it('encodes routes and separates independent grants from viewing',async () => {
 const mock=vi.fn().mockImplementation((path:string) => Promise.resolve(response(path.endsWith('/csrf') ? {token:'csrf',headerName:'X-CSRF-TOKEN'} : {})));vi.stubGlobal('fetch',mock);
 await exportPermission('c/1');await listExportGrants('c/1');await setExportGrant('c/1',7,4,false);await readExport('e/1');
 expect(mock.mock.calls[0]?.[0]).toBe('/api/v1/exports/charts/c%2F1/permission');expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/exports/charts/c%2F1/grants');
 expect(mock.mock.calls[3]?.[0]).toBe('/api/v1/exports/charts/c%2F1/grant');expect(JSON.parse(mock.mock.calls[3]?.[1].body)).toEqual({userId:7,expectedChartVersion:4,enabled:false});expect(mock.mock.calls[4]?.[0]).toBe('/api/v1/exports/e%2F1');
 expect(exportPending('QUEUED')).toBe(true);expect(exportPending('INTERRUPTED')).toBe(false);
});
it('downloads only authenticated no-store CSV and retains exact bytes',async () => {
 const mock=vi.fn().mockResolvedValue(new Response('value\r\n9007199254740993.0100\r\n',{headers:{'Content-Type':'text/csv;charset=utf-8'}}));vi.stubGlobal('fetch',mock);
 const blob=await downloadExport('e/1');expect(await blob.text()).toContain('9007199254740993.0100');expect(mock.mock.calls[0]?.[0]).toBe('/api/v1/exports/e%2F1/download');expect(mock.mock.calls[0]?.[1]).toMatchObject({credentials:'same-origin',cache:'no-store',redirect:'error'});
});
it('revoked or expired responses never become downloadable blobs',async () => {
 vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(null,403)));await expect(downloadExport('e1')).rejects.toMatchObject({status:403,code:'FORBIDDEN'});
});
it('invalid content type fails closed and expired login invokes session cleanup',async () => {
 const unauthorized=vi.fn();setUnauthorizedHandler(unauthorized);
 vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response('<html>login</html>',{headers:{'Content-Type':'text/html'}})).mockResolvedValueOnce(response(null,401)));
 await expect(downloadExport('e1')).rejects.toMatchObject({code:'INVALID_RESPONSE'});await expect(downloadExport('e1')).rejects.toMatchObject({status:401});expect(unauthorized).toHaveBeenCalledOnce();
});
it('bounds binary payloads even when Content-Length is absent',async () => {
 vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response('x',{headers:{'Content-Type':'text/csv','Content-Length':'8000001'}})).mockResolvedValueOnce(new Response(new Uint8Array(8_000_001),{headers:{'Content-Type':'text/csv'}})));
 await expect(downloadExport('e1')).rejects.toMatchObject({code:'EXPORT_TOO_LARGE'});await expect(downloadExport('e1')).rejects.toMatchObject({code:'EXPORT_TOO_LARGE'});
});
it('ignores a completed download if navigation canceled the request',async () => {
 const controller=new AbortController();controller.abort(new Error('navigated'));
 vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('x',{headers:{'Content-Type':'text/csv'}})));
 await expect(downloadExport('e1',controller.signal)).rejects.toThrow('navigated');
});
