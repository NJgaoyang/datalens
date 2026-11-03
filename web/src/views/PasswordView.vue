<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { clearSession } from '../api/auth';
import { errorMessage, requestApi } from '../api/http';
const router = useRouter();
const currentPassword = ref(''); const newPassword = ref(''); const confirmation = ref(''); const busy = ref(false); const error = ref('');
async function submit() {
  if (busy.value) return;
  if (!currentPassword.value || newPassword.value.length < 12 || new TextEncoder().encode(newPassword.value).length > 72) { error.value = '请输入当前密码，新密码需为 至少 12 个字符且不超过 72 字节'; return; }
  if (newPassword.value !== confirmation.value) { error.value = '两次输入的新密码不一致'; return; }
  busy.value = true; error.value = '';
  try {
    await requestApi('/api/v1/auth/password', 'POST', { currentPassword: currentPassword.value, newPassword: newPassword.value });
    clearSession(); await router.replace('/login');
  } catch (cause) { error.value = errorMessage(cause); }
  finally { currentPassword.value = ''; newPassword.value = ''; confirmation.value = ''; busy.value = false; }
}
</script>
<template><section class="page"><div class="page-heading"><div><div class="eyebrow">ACCOUNT / SECURITY</div><h1>修改密码</h1><p class="subtitle">修改后需重新登录。请使用独立且不易猜测的密码。</p></div></div>
  <div class="panel account-panel"><el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert" />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="当前密码"><el-input v-model="currentPassword" aria-label="当前密码" type="password" autocomplete="current-password" show-password :disabled="busy" /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="newPassword" aria-label="新密码" type="password" autocomplete="new-password" show-password :disabled="busy" /></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="confirmation" aria-label="确认新密码" type="password" autocomplete="new-password" :disabled="busy" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy">保存新密码</el-button>
    </el-form>
  </div></section></template>
