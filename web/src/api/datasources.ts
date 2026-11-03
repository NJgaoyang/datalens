import { ApiError, getApi, requestApi } from './http';
export interface Field { tableName: string; columnName: string }
export interface Column extends Field { sourceId: string; typeName: string; ordinalPosition: number; nullable: boolean }
export interface SourceInput { name: string; host: string; port: number; catalog: string; database: string; username: string; password: string; enabled: boolean; tables: string[] }
export interface Source extends Omit<SourceInput, 'password'> { id: string; version: number; harvestStatus: 'NEVER'|'RUNNING'|'SUCCEEDED'|'FAILED'; lastHarvestAt: string|null; lastErrorCode: string|null }
export interface SubjectInput { name: string; sourceId: string; fields: Field[]; deniedFields: Field[]; userIds: number[]; shareScope: 'NONE'|'AUTHORIZED_USERS' }
export interface Subject extends SubjectInput { id: string }
export const fieldKey = (field: Field) => `${field.tableName}.${field.columnName}`;
export function parseFields(text: string): Field[] {
  const values = [...new Set(text.split(/[\s,]+/).filter(Boolean))];
  if (values.length > 1000 || values.some(value => !/^[a-zA-Z_][a-zA-Z0-9_]*\.[a-zA-Z_][a-zA-Z0-9_]*$/.test(value))) throw new Error('字段需使用 table.column 格式，最多 1000 项');
  return values.map(value => { const [tableName, columnName] = value.split('.'); return { tableName: tableName!, columnName: columnName! }; });
}
export function parseUserIds(text: string): number[] {
  const values = [...new Set(text.split(/[\s,]+/).filter(Boolean))];
  if (values.length > 100 || values.some(value => !/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value)))) throw new Error('用户 ID 需为正整数，最多 100 项');
  return values.map(Number);
}
export function sourcePayload(input: SourceInput): SourceInput {
  if (!input.name.trim() || !input.username.trim() || !/^\d{1,3}(\.\d{1,3}){3}$/.test(input.host) || input.host.split('.').some(v => Number(v) > 255) || !Number.isInteger(input.port) || input.port < 1 || input.port > 65535) throw new Error('请输入名称、只读账号、有效 IPv4 地址及端口');
  const identifiers = [input.catalog, input.database, ...input.tables];
  if (input.tables.length === 0 || input.tables.length > 100 || identifiers.some(v => !/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(v))) throw new Error('Catalog、数据库和授权表需为有效标识符；至少授权一张表，最多 100 张');
  // Explicit projection: never echo response-only fields or unknown credentials.
  return { name: input.name.trim(), host: input.host, port: input.port, catalog: input.catalog, database: input.database, username: input.username.trim(), password: input.password, enabled: input.enabled, tables: [...new Set(input.tables)] };
}
async function list<T>(path: string, signal?: AbortSignal): Promise<T[]> {
  const { data } = await getApi<T[]>(path, signal);
  if (!Array.isArray(data)) throw new ApiError('列表响应无效', 'INVALID_RESPONSE');
  return data;
}
const key = encodeURIComponent;
export const listSources = (signal?: AbortSignal) => list<Source>('/api/v1/admin/sources', signal);
export const listSubjects = (admin = false, signal?: AbortSignal) => list<Subject>(`/api/v1/${admin ? 'admin/' : ''}subjects`, signal);
export const metadata = (id: string, admin = false, signal?: AbortSignal) => list<Column>(`/api/v1/${admin ? 'admin/sources' : 'subjects'}/${key(id)}/metadata`, signal);
export const saveSource = async (input: SourceInput, id?: string) => (await requestApi<Source>(`/api/v1/admin/sources${id ? `/${key(id)}` : ''}`, id ? 'PUT' : 'POST', sourcePayload(input))).data;
export const sourceAction = async (id: string, action: 'test'|'harvest') => {
  const result = await requestApi<Source|{success: true}>(`/api/v1/admin/sources/${key(id)}/${action}`, 'POST', {});
  if (action === 'test' && (!result.data || !('success' in result.data) || result.data.success !== true)) throw new ApiError('连接测试未返回成功结果', 'INVALID_RESPONSE');
  return result.data;
};
export const saveSubject = async (input: SubjectInput, id?: string) => (await requestApi<Subject>(`/api/v1/admin/subjects${id ? `/${key(id)}` : ''}`, id ? 'PUT' : 'POST', input)).data;
