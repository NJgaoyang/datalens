import { reactive } from 'vue';
import { ApiError, clearCsrf, getApi, refreshCsrf, requestApi } from './http';
export interface User { id: number; username: string; role: 'SUPER_ADMIN' | 'USER'; enabled: boolean }
export const session = reactive<{ user: User | null; checked: boolean; error: string }>({ user: null, checked: false, error: '' });
function validUser(data: User): User {
  if (!data || !Number.isInteger(data.id) || typeof data.username !== 'string' || !['SUPER_ADMIN', 'USER'].includes(data.role) || data.enabled !== true) throw new ApiError('账号响应无效', 'INVALID_RESPONSE');
  return data;
}
export async function loadSession() {
  const { data } = await getApi<User>('/api/v1/auth/me');
  session.user = validUser(data); session.checked = true; session.error = '';
}
export function clearSession() { session.user = null; session.checked = false; clearCsrf(); }
export async function login(username: string, password: string) {
  const { data } = await requestApi<User>('/api/v1/auth/login', 'POST', { username, password });
  session.user = validUser(data); session.checked = true; session.error = '';
  clearCsrf(); await refreshCsrf();
}
export async function logout() {
  await requestApi('/api/v1/auth/logout', 'POST');
  clearSession(); await refreshCsrf();
}
