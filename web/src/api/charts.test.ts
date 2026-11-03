import { afterEach, expect, it, vi } from 'vitest';
import { clearCsrf } from './http';
import { createPreview, saveChart, publishRevision, refreshChart, refreshResult, previewResult, listCharts, type ChartDefinition } from './charts';
const response = (data: unknown) => new Response(JSON.stringify({code:'OK',message:'ok',data,requestId:'chart-test'}));
const definition: ChartDefinition = {subjectId:'s1',sql:'SELECT t.x FROM t',parameters:[],metricDescription:'metric',timeRule:{kind:'NONE'},spec:{type:'TABLE',dimension:null,measures:[]}};
afterEach(() => {clearCsrf();vi.unstubAllGlobals();});
it('separates preview execution from explicit save and immutable revision publication', async () => {
  const mock = vi.fn().mockResolvedValueOnce(response({token:'csrf',headerName:'X-CSRF-TOKEN'})).mockImplementation(() => Promise.resolve(response({id:'p1',definition,taskId:'t1'})));vi.stubGlobal('fetch',mock);
  await createPreview(definition,'stable-preview'); await saveChart('Private','p1'); await publishRevision('c/1','Private','p1',7);
  expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/charts/previews'); expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual({definition,idempotencyKey:'stable-preview'});
  expect(JSON.parse(mock.mock.calls[2]?.[1].body)).toEqual({name:'Private',previewId:'p1'}); expect(mock.mock.calls[3]?.[0]).toBe('/api/v1/charts/c%2F1/revisions'); expect(JSON.parse(mock.mock.calls[3]?.[1].body)).toEqual({name:'Private',previewId:'p1',expectedVersion:7});
});
it('uses independently authorized chart result routes and preserves exact values', async () => {
  const data = {columns:[{name:'x',jdbcType:'BIGINT'}],rows:[['9007199254740993']],truncated:false,rowLimit:1000};const mock = vi.fn().mockImplementation(() => Promise.resolve(response(data)));vi.stubGlobal('fetch',mock);
  expect(await refreshResult('c/1','t/1')).toEqual(data); await previewResult('p/1'); expect(mock.mock.calls[0]?.[0]).toBe('/api/v1/charts/c%2F1/refreshes/t%2F1/result');expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/charts/previews/p%2F1/result');
});
it('stable refresh keys can reconcile uncertain outcomes, and list supports pagination', async () => {
  const mock = vi.fn().mockResolvedValueOnce(response({token:'csrf',headerName:'X-CSRF-TOKEN'})).mockImplementation(() => Promise.resolve(response({})));vi.stubGlobal('fetch',mock);
  await refreshChart('c1','same-key');await refreshChart('c1','same-key');expect(mock.mock.calls[1]?.[1].body).toBe(mock.mock.calls[2]?.[1].body);await listCharts(undefined,2);expect(mock.mock.calls[3]?.[0]).toBe('/api/v1/charts?page=2&size=20&scope=mine');
});
it('encodes management routes, includes optimistic versions and scopes, and sends no SQL', async () => {
  const {listChartRecipients,updateChartSharing,deleteChart,restoreChart,transferChart} = await import('./charts');
  const mock = vi.fn().mockResolvedValueOnce(response({token:'csrf',headerName:'X-CSRF-TOKEN'})).mockImplementation(() => Promise.resolve(response({})));
  vi.stubGlobal('fetch',mock);
  await updateChartSharing('c/1','SPECIFIC',[3],4);
  await deleteChart('c/1',5); await restoreChart('c/1',6); await transferChart('c/1',3,7);
  await listCharts(undefined,3,'trash'); await listChartRecipients();
  expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/charts/c%2F1/sharing');
  expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual({visibility:'SPECIFIC',userIds:[3],expectedVersion:4});
  expect(mock.mock.calls[2]?.[0]).toBe('/api/v1/charts/c%2F1?expectedVersion=5');
  expect(mock.mock.calls[2]?.[1].method).toBe('DELETE');
  expect(mock.mock.calls[2]?.[1].body).toBeUndefined();
  expect(JSON.parse(mock.mock.calls[3]?.[1].body)).toEqual({expectedVersion:6});
  expect(JSON.parse(mock.mock.calls[4]?.[1].body)).toEqual({ownerId:3,expectedVersion:7});
  expect(mock.mock.calls[5]?.[0]).toBe('/api/v1/charts?page=3&size=20&scope=trash');
  expect(mock.mock.calls[6]?.[0]).toBe('/api/v1/charts/recipients');
});
