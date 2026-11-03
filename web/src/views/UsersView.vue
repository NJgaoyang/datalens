<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { session, type User } from '../api/auth';
import { errorMessage, getApi, requestApi } from '../api/http';
const users = ref<User[]>([]); const total = ref(0); const page = ref(1); const size = 20;
const loading = ref(false); const busy = ref(false); const error = ref(''); const dialog = ref(false); const resetTarget = ref<User | null>(null);
const form = reactive({ username: '', password: '', role: 'USER' as User['role'] });
let active: AbortController | undefined;
async function reload() {
  active?.abort(); const controller = new AbortController(); active = controller;
  loading.value = true; error.value = '';
  try {
    const { data } = await getApi<{ items: User[]; total: number; page: number; size: number }>(`/api/v1/admin/users?page=${page.value}&size=${size}`, controller.signal);
    if (!controller.signal.aborted) { users.value = data.items; total.value = data.total; }
  } catch (cause) { if (!controller.signal.aborted) { error.value = errorMessage(cause); users.value = []; total.value = 0; } }
  finally { if (!controller.signal.aborted) loading.value = false; }
}
function openCreate() { resetTarget.value = null; form.username = ''; form.password = ''; form.role = 'USER'; error.value = ''; dialog.value = true; }
function openReset(user: User) { resetTarget.value = user; form.password = ''; error.value = ''; dialog.value = true; }
function clearPassword() { form.password = ''; }
async function save() {
  if (busy.value) return;
  if (!resetTarget.value && !/^[a-zA-Z0-9_.-]{3,64}$/.test(form.username)) { error.value = '用户名需为 3–64 位字母、数字、下划线、点或短横线'; return; }
  if (form.password.length < 12 || new TextEncoder().encode(form.password).length > 72) { error.value = '密码需至少 12 个字符且不超过 72 字节'; return; }
  busy.value = true; error.value = '';
  try {
    if (resetTarget.value) await requestApi(`/api/v1/admin/users/${resetTarget.value.id}/password`, 'POST', { newPassword: form.password });
    else await requestApi('/api/v1/admin/users', 'POST', { username: form.username.toLowerCase(), password: form.password, role: form.role });
    ElMessage.success(resetTarget.value ? '密码已重置，该用户需重新登录' : '用户已创建'); dialog.value = false; clearPassword(); await reload();
  } catch (cause) { error.value = errorMessage(cause); }
  finally { busy.value = false; }
}
async function toggle(user: User) {
  if (busy.value) return;
  try { await ElMessageBox.confirm(`确认${user.enabled ? '禁用' : '启用'}账号 ${user.username}？${user.enabled ? '该用户所有会话将失效。' : ''}`, '更改账号状态', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }); }
  catch { return; }
  busy.value = true; error.value = '';
  try { await requestApi(`/api/v1/admin/users/${user.id}/enabled`, 'PUT', { enabled: !user.enabled }); await reload(); }
  catch (cause) { error.value = errorMessage(cause); }
  finally { busy.value = false; }
}
onMounted(reload); onUnmounted(() => { active?.abort(); clearPassword(); });
</script>
<template><section class="page">
  <div class="page-heading"><div><div class="eyebrow">ADMINISTRATION / IDENTITY</div><h1>用户管理</h1><p class="subtitle">创建账号、管理账号状态和重置密码。所有授权均由服务端验证。</p></div><el-button type="primary" :disabled="busy" @click="openCreate">创建用户</el-button></div>
  <el-alert v-if="error && !dialog" :title="error" type="error" :closable="false" show-icon class="form-alert" />
  <div class="panel"><div class="panel-heading"><h2>系统账号</h2><el-button :loading="loading" :disabled="busy" @click="reload">刷新列表</el-button></div>
    <el-table v-loading="loading" :data="users" empty-text="暂无用户" row-key="id">
      <el-table-column prop="id" label="账号 ID" width="100" />
      <el-table-column prop="username" label="用户名" min-width="150" />
      <el-table-column label="角色" min-width="120"><template #default="{ row }">{{ row.role === 'SUPER_ADMIN' ? '超级管理员' : '普通用户' }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '有效' : '已禁用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" min-width="210"><template #default="{ row }"><el-button size="small" :disabled="busy || row.id === session.user?.id" @click="toggle(row)">{{ row.enabled ? '禁用' : '启用' }}</el-button><el-button size="small" :disabled="busy || row.id === session.user?.id" @click="openReset(row)">重置密码</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="pagination" v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" :disabled="loading || busy" @current-change="reload" />
    <p class="scope-note">不可禁用自己的账号。修改自己的密码请进入「修改密码」。最后一个有效超管受服务端保护。</p>
  </div>
  <el-dialog v-model="dialog" :title="resetTarget ? `重置 ${resetTarget.username} 的密码` : '创建用户'" width="min(480px, 92vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @closed="clearPassword">
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert" />
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item v-if="!resetTarget" label="用户名"><el-input v-model="form.username" aria-label="用户名" autocomplete="off" :disabled="busy" maxlength="64" /></el-form-item>
      <el-form-item v-if="!resetTarget" label="角色"><el-select v-model="form.role" aria-label="角色" :disabled="busy"><el-option label="普通用户" value="USER" /><el-option label="超级管理员" value="SUPER_ADMIN" /></el-select></el-form-item>
      <el-form-item label="新密码"><el-input v-model="form.password" aria-label="新密码" type="password" autocomplete="new-password" show-password :disabled="busy" /></el-form-item>
      <p class="scope-note">至少 12 个字符且不超过 72 字节。重置密码会使该用户全部会话失效。</p>
      <div class="dialog-actions"><el-button :disabled="busy" @click="dialog = false">取消</el-button><el-button native-type="submit" type="primary" :loading="busy">{{ resetTarget ? '确认重置' : '创建' }}</el-button></div>
    </el-form>
  </el-dialog>
</section></template>
