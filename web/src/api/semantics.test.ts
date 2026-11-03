import { afterEach, expect, it, vi } from 'vitest';
import { clearCsrf } from './http';
import { emptySemanticDocument, parseSemanticDocument, readSemanticDraft, saveSemanticDraft, reviewSemanticDraft, publishSemantics, withdrawSemantics, searchSemantics, readPublication, listSemanticReleases } from './semantics';
const envelope=(data:unknown)=>new Response(JSON.stringify({code:'OK',message:'ok',requestId:'semantic-test',data}));
const document=emptySemanticDocument();
const draft={subjectId:'s/1',version:3,document,reviewed:false,warnings:[],publishedVersion:null,reviewerId:null,reviewedAt:null};
const publication={subjectId:'s/1',version:1,document,digest:'hash',scopeFingerprint:'scope',reviewerId:1,publisherId:1,publishedAt:'2026-11-03T12:00:00Z'};
afterEach(()=>{clearCsrf();vi.unstubAllGlobals();});
it('requires bounded structured document and rejects invalid JSON, unknown top-level keys and oversized assets',()=>{
  expect(parseSemanticDocument(JSON.stringify(document))).toEqual(document);
  for(const value of ['[]','null','nope',JSON.stringify({...document,actorId:1}),JSON.stringify({...document,fields:new Array(201).fill({})}),JSON.stringify({...document,metrics:[null]})])expect(()=>parseSemanticDocument(value)).toThrow();
  expect(()=>parseSemanticDocument(' '.repeat(300001))).toThrow(/300,000/);
});
it('preserves explicit reviewed temporal parameter metadata without inferring missing authority',()=>{
  const legacy={...document,metrics:[{id:'metric'}]};
  expect(parseSemanticDocument(JSON.stringify(legacy)).metrics[0]?.timeParameters).toBeUndefined();
  const timeParameters={startIndex:3,endIndex:1,timeZone:'America/New_York',parameterType:'TIMESTAMP'};
  const reviewed={...document,metrics:[{id:'metric',timeParameters}]};
  expect(parseSemanticDocument(JSON.stringify(reviewed)).metrics[0]?.timeParameters).toEqual(timeParameters);
});
it('encodes subject, preserves expected version, and separates explicit review, publication and withdrawal',async()=>{
  const mock=vi.fn().mockResolvedValueOnce(envelope({token:'csrf',headerName:'X-CSRF-TOKEN'})).mockResolvedValueOnce(envelope(draft)).mockResolvedValueOnce(envelope({...draft,reviewed:true})).mockResolvedValueOnce(envelope(publication)).mockResolvedValueOnce(envelope(draft));vi.stubGlobal('fetch',mock);
  await saveSemanticDraft('s/1',document,2);await reviewSemanticDraft('s/1',3);await publishSemantics('s/1',4);await withdrawSemantics('s/1',5);
  expect(mock.mock.calls[1]?.[0]).toBe('/api/v1/semantics/s%2F1/draft');expect(mock.mock.calls[1]?.[1].method).toBe('PUT');expect(JSON.parse(mock.mock.calls[1]?.[1].body)).toEqual({expectedVersion:2,document});
  expect(JSON.parse(mock.mock.calls[2]?.[1].body)).toEqual({expectedVersion:3,confirmation:true});expect(mock.mock.calls[2]?.[0]).toMatch(/\/review$/);
  expect(JSON.parse(mock.mock.calls[3]?.[1].body)).toEqual({expectedVersion:4});expect(mock.mock.calls[3]?.[0]).toMatch(/\/publish$/);
  expect(JSON.parse(mock.mock.calls[4]?.[1].body)).toEqual({expectedVersion:5});expect(mock.mock.calls[4]?.[0]).toMatch(/\/withdraw$/);
});
it('search uses only the published endpoint and encodes keywords and does not silently fallback to drafts',async()=>{
  const mock=vi.fn().mockResolvedValueOnce(envelope({publicationVersion:2,matches:[]}));vi.stubGlobal('fetch',mock);
  expect(await searchSemantics('s/1','销售 & orders')).toEqual({publicationVersion:2,matches:[]});expect(mock.mock.calls[0]?.[0]).toBe('/api/v1/semantics/s%2F1/search?q=%E9%94%80%E5%94%AE%20%26%20orders');
  await expect(searchSemantics('s/1','x'.repeat(201))).rejects.toThrow(/200/);expect(mock).toHaveBeenCalledTimes(1);
});
it('rejects malformed read envelopes and keeps admin history and draft reads separate from publication',async()=>{
  const mock=vi.fn().mockResolvedValueOnce(envelope(draft)).mockResolvedValueOnce(envelope(publication)).mockResolvedValueOnce(envelope([publication])).mockResolvedValueOnce(envelope({})).mockResolvedValueOnce(envelope({publicationVersion:1,matches:[{kind:'METRIC',id:'m',name:'x',reasons:null}]}));vi.stubGlobal('fetch',mock);
  await readSemanticDraft('s/1');await readPublication('s/1');await listSemanticReleases('s/1');
  expect(mock.mock.calls.map(call=>call[0])).toEqual(['/api/v1/semantics/s%2F1/draft','/api/v1/semantics/s%2F1/published','/api/v1/semantics/s%2F1/releases']);
  await expect(readPublication('s/1')).rejects.toThrow(/发布响应/);await expect(searchSemantics('s/1','x')).rejects.toThrow(/检索响应/);
});
