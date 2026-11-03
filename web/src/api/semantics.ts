import { ApiError, getApi, requestApi } from './http';
import type { Field } from './datasources';
import type { Parameter } from './queries';
export interface BusinessField { id:string; name:string; aliases:string[]; physical:Field; type:string; unit:string; sensitivity:'PUBLIC'|'INTERNAL'|'SENSITIVE' }
export interface Metric { id:string; name:string; aliases:string[]; calculation:string; filters:string; timeFieldId:string; owner:string; unit:string; grain:string; nullHandling:string; timePolicy:string; fieldIds:string[]; sql:string; parameters:Parameter[] }
export interface Dimension { id:string; name:string; aliases:string[]; fieldId:string }
export interface Relation { id:string; name:string; leftFieldId:string; rightFieldId:string; cardinality:'ONE_TO_ONE'|'MANY_TO_ONE'|'ONE_TO_MANY'|'MANY_TO_MANY'|'UNKNOWN'; leftGrain:string; rightGrain:string }
export interface Example { id:string; question:string; metricIds:string[]; sql:string; parameters:Parameter[]; verification:string }
export interface SemanticDocument { fields:BusinessField[]; metrics:Metric[]; dimensions:Dimension[]; relations:Relation[]; examples:Example[] }
export interface SemanticDraft { subjectId:string; version:number; document:SemanticDocument|null; reviewed:boolean; reviewerId:number|null; reviewedAt:string|null; publishedVersion:number|null; warnings:string[] }
export interface Publication { subjectId:string; version:number; document:SemanticDocument; digest:string; scopeFingerprint:string; reviewerId:number; reviewedAt?:string; publisherId:number; publishedAt:string }
export interface SemanticMatch { kind:string; id:string; name:string; reasons:string[] }
export interface SemanticSearch { publicationVersion:number; matches:SemanticMatch[] }
export const emptySemanticDocument = (): SemanticDocument => ({fields:[],metrics:[],dimensions:[],relations:[],examples:[]});
export const documentSections = ['fields','metrics','dimensions','relations','examples'] as const;
const limits = {fields:200,metrics:100,dimensions:100,relations:100,examples:100};
// Client checks improve editor feedback; server owns validation, authorization and publication.
export function parseSemanticDocument(text:string): SemanticDocument {
  if (text.length > 300_000) throw new Error('语义文档不能超过 300,000 个字符');
  let data:unknown;
  try { data=JSON.parse(text); } catch { throw new Error('语义文档必须是有效的 JSON 对象'); }
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('语义文档必须是 JSON 对象');
  const value=data as Record<string,unknown>;
  if (Object.keys(value).some(key => !documentSections.includes(key as typeof documentSections[number])) || documentSections.some(key => !Array.isArray(value[key]) || (value[key] as unknown[]).length>limits[key])) throw new Error('文档需包含 fields、metrics、dimensions、relations、examples 数组；字段最多 200 项，其他各 100 项');
  if (documentSections.some(key => (value[key] as unknown[]).some(item => !item || typeof item !== 'object' || Array.isArray(item)))) throw new Error('每个语义资产必须是 JSON 对象');
  return value as unknown as SemanticDocument;
}
const root=(id:string)=>`/api/v1/semantics/${encodeURIComponent(id)}`;
function draft(data:SemanticDraft) {
  if (!data || typeof data.subjectId!=='string' || !Number.isSafeInteger(data.version) || data.version<0 || typeof data.reviewed!=='boolean' || !Array.isArray(data.warnings) || !data.warnings.every(x=>typeof x==='string')) throw new ApiError('草稿响应无效','INVALID_RESPONSE');
  if(data.document) parseSemanticDocument(JSON.stringify(data.document));
  return data;
}
function publication(data:Publication) {
  if (!data || typeof data.subjectId!=='string' || !Number.isSafeInteger(data.version) || data.version<1 || typeof data.digest!=='string' || typeof data.publishedAt!=='string') throw new ApiError('发布响应无效','INVALID_RESPONSE');
  parseSemanticDocument(JSON.stringify(data.document));return data;
}
export const readSemanticDraft=async(id:string,signal?:AbortSignal)=>draft((await getApi<SemanticDraft>(`${root(id)}/draft`,signal)).data);
export const saveSemanticDraft=async(id:string,document:SemanticDocument,expectedVersion:number,signal?:AbortSignal)=>draft((await requestApi<SemanticDraft>(`${root(id)}/draft`,'PUT',{expectedVersion,document},signal)).data);
export const reviewSemanticDraft=async(id:string,expectedVersion:number,signal?:AbortSignal)=>draft((await requestApi<SemanticDraft>(`${root(id)}/review`,'POST',{expectedVersion,confirmation:true},signal)).data);
export const publishSemantics=async(id:string,expectedVersion:number,signal?:AbortSignal)=>publication((await requestApi<Publication>(`${root(id)}/publish`,'POST',{expectedVersion},signal)).data);
export const withdrawSemantics=async(id:string,expectedVersion:number,signal?:AbortSignal)=>draft((await requestApi<SemanticDraft>(`${root(id)}/withdraw`,'POST',{expectedVersion},signal)).data);
export const readPublication=async(id:string,signal?:AbortSignal)=>publication((await getApi<Publication>(`${root(id)}/published`,signal)).data);
export const searchSemantics=async(id:string,query:string,signal?:AbortSignal)=>{
  if(query.length>200)throw new Error('检索词最多 200 个字符');
  const data=(await getApi<SemanticSearch>(`${root(id)}/search?q=${encodeURIComponent(query)}`,signal)).data;
  if(!data || !Number.isSafeInteger(data.publicationVersion) || !Array.isArray(data.matches) || !data.matches.every(match=>match && typeof match.kind==='string' && typeof match.id==='string' && typeof match.name==='string' && Array.isArray(match.reasons) && match.reasons.every(reason=>typeof reason==='string')))throw new ApiError('检索响应无效','INVALID_RESPONSE');
  return data;
};
export const listSemanticReleases=async(id:string,signal?:AbortSignal)=>{
  const data=(await getApi<Publication[]>(`${root(id)}/releases`,signal)).data;
  if(!Array.isArray(data))throw new ApiError('版本列表响应无效','INVALID_RESPONSE');
  return data.map(publication);
};
